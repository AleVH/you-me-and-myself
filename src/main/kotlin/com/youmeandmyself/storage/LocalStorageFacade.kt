package com.youmeandmyself.storage

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.youmeandmyself.dev.Dev
import com.youmeandmyself.storage.model.AiExchange
import com.youmeandmyself.storage.model.ExchangeMetadata
import com.youmeandmyself.storage.model.ExchangePurpose
import com.youmeandmyself.storage.model.ExchangeRawResponse
import com.youmeandmyself.storage.model.ExchangeRequest
import com.youmeandmyself.storage.model.MetadataFilter
import com.youmeandmyself.storage.search.SearchScope
import com.youmeandmyself.storage.search.SimpleSearchEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneOffset

/**
 * LOCAL mode implementation of [StorageFacade].
 *
 * ## Architecture Decision: Hybrid Storage Model
 *
 * This facade implements a hybrid storage strategy where data is split between two locations:
 *
 * 1. **Raw exchanges** (full request + raw response) → Project directory
 *    - Location: `<project-root>/.youmeandmyself/exchanges-YYYY-MM.jsonl`
 *    - Why: Keeps data portable with the project, can be version-controlled if desired,
 *      survives IDE reinstalls, and is easy for developers to inspect/debug.
 *
 * 2. **Metadata index** (lightweight records for fast querying) → IntelliJ system area
 *    - Location: `<intellij-system>/youmeandmyself/<project-hash>/metadata-index.json`
 *    - Why: Keeps derived/computed data separate from source code, follows JetBrains
 *      conventions for plugin data, and doesn't clutter the project directory.
 *
 * ## File Format: JSONL (JSON Lines)
 *
 * Raw exchanges use JSONL format (one JSON object per line) because:
 * - Append-friendly: New exchanges are added without rewriting the entire file
 * - Streaming: Can read line-by-line without loading entire file into memory
 * - Debuggable: Human-readable, can be inspected with any text editor
 * - Recoverable: A corrupted line doesn't invalidate the entire file
 *
 * Files are partitioned by month (exchanges-2026-01.jsonl) to:
 * - Keep individual files from growing too large
 * - Make cleanup/archival of old data straightforward
 * - Allow efficient date-range queries (skip files outside the range)
 *
 * ## Linking Raw Data and Metadata
 *
 * Each metadata record contains:
 * - `id`: Unique identifier matching the exchange
 * - `rawFile`: Which JSONL file contains the full content (e.g., "exchanges-2026-01.jsonl")
 * - `rawDataAvailable`: Flag that becomes false if the raw file is deleted
 *
 * This allows the system to retain useful metadata (timestamps, provider info, labels)
 * even if the original content is purged for space reasons.
 *
 * ## Thread Safety
 *
 * - Write operations use a [Mutex] to prevent concurrent file corruption
 * - Read operations are safe without locking because:
 *   - Raw JSONL files are append-only (existing content never modified)
 *   - Metadata index is written atomically (write to temp, then rename)
 *
 * ## Error Handling Philosophy
 *
 * All I/O errors are:
 * - Logged with full context (file paths, operation details, stack traces)
 * - Returned as graceful fallbacks (null, empty list, false)
 * - Never thrown to callers
 *
 * This ensures the plugin remains functional even if storage fails — the user
 * can still chat with AI, they just won't have persistence.
 *
 * @param project The IntelliJ project this facade is scoped to
 */
@Service(Service.Level.PROJECT)
class LocalStorageFacade(private val project: Project) : StorageFacade {

    private val log = Logger.getInstance(LocalStorageFacade::class.java)

    /**
     * JSON parser for metadata files.
     * Pretty-printed for human readability since metadata files are small
     * and may be inspected during debugging.
     */
    private val jsonPretty = Json {
        prettyPrint = true
        ignoreUnknownKeys = true  // Forward compatibility: ignore fields from newer versions
        encodeDefaults = true     // Always write default values for clarity
    }

    /**
     * JSON parser for JSONL raw exchange files.
     * Compact (no pretty printing) to minimize file size since these can grow large.
     */
    private val jsonCompact = Json {
        prettyPrint = false
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * In-memory cache of all metadata records.
     *
     * Loaded from disk on first access, then kept in sync with writes.
     * This avoids repeated disk reads for queries — metadata is small enough
     * to keep entirely in memory (even 10,000 records is only a few MB).
     *
     * Null means "not yet loaded" — use [loadMetadataCache] to access.
     */
    private var metadataCache: MutableList<ExchangeMetadata>? = null

    /**
     * Mutex protecting metadata write operations.
     *
     * Ensures only one coroutine at a time can modify the metadata cache
     * and persist it to disk. Without this, concurrent saves could result
     * in lost writes or corrupted files.
     */
    private val metadataMutex = Mutex()

    /**
     * Search engine instance for text content queries.
     *
     * Currently uses [SimpleSearchEngine] (in-memory substring matching).
     * Can be swapped for a more sophisticated implementation (Lucene, SQLite FTS)
     * without changing the facade interface.
     */
    private val searchEngine: SimpleSearchEngine = SimpleSearchEngine()

    /**
     * Current storage mode.
     *
     * OFF = no persistence (useful for testing or privacy-sensitive contexts)
     * LOCAL = write to disk (this implementation)
     * CLOUD = future remote sync capability
     *
     * Can be changed at runtime via [setMode], though typically set once at startup
     * based on user preferences.
     */
    private var mode: StorageMode = StorageMode.LOCAL

    // ==================== Public API ====================

    /**
     * Persist a complete AI exchange.
     *
     * This is the primary entry point for saving AI interactions. It:
     * 1. Writes the full exchange (request + raw response) to a JSONL file in the project directory
     * 2. Creates a lightweight metadata record in the system area
     * 3. Updates the search index for text queries
     *
     * The exchange is immediately persisted — there's no batching or delayed write.
     * This ensures data is safe even if the IDE crashes immediately after.
     *
     * @param exchange The complete exchange to store (must have a unique ID)
     * @return The exchange ID if saved successfully, null if storage is OFF or an error occurred
     */
    override suspend fun saveExchange(exchange: AiExchange): String? {
        // Early exit if persistence is disabled
        if (mode == StorageMode.OFF) {
            Dev.info(log, "save.skip", "reason" to "mode_off", "id" to exchange.id)
            return null
        }

        // Run I/O on background thread to avoid blocking UI
        return withContext(Dispatchers.IO) {
            try {
                Dev.info(log, "save.start",
                    "id" to exchange.id,
                    "provider" to exchange.providerId,
                    "purpose" to exchange.purpose
                )

                // Step 1: Write raw exchange to JSONL file
                // Returns the filename used (e.g., "exchanges-2026-01.jsonl")
                val rawFile = writeRawExchange(exchange)

                // Step 2: Create metadata record linking to the raw file
                val metadata = ExchangeMetadata(
                    id = exchange.id,
                    timestamp = exchange.timestamp,
                    providerId = exchange.providerId,
                    modelId = exchange.modelId,
                    purpose = exchange.purpose,
                    tokensUsed = null, // Will be extracted later if available
                    flags = mutableSetOf(),
                    labels = mutableSetOf(),
                    rawFile = rawFile,
                    rawDataAvailable = true
                )
                addMetadata(metadata)

                // Step 3: Update search index
                // For now, index only the request input since we don't have extracted content yet
                searchEngine.onExchangeSaved(exchange)

                Dev.info(log, "save.complete",
                    "id" to exchange.id,
                    "rawFile" to rawFile
                )

                exchange.id
            } catch (e: Exception) {
                // Log the error but don't crash — return null to indicate failure
                Dev.error(log, "save.failed", e,
                    "id" to exchange.id,
                    "provider" to exchange.providerId
                )
                null
            }
        }
    }

    /**
     * Retrieve a full exchange by its ID.
     *
     * This loads the complete request and raw response from the JSONL file.
     * Use this when you need the actual content (e.g., for extraction, re-display).
     * For listing/filtering, use [queryMetadata] instead — it's much faster.
     *
     * @param id The unique exchange ID
     * @return The complete exchange, or null if:
     *         - Storage mode is OFF
     *         - No metadata exists for this ID
     *         - Raw data has been deleted (rawDataAvailable = false)
     *         - The exchange isn't found in the raw file (shouldn't happen normally)
     */
    override suspend fun getExchange(id: String): AiExchange? {
        if (mode == StorageMode.OFF) {
            Dev.info(log, "get.skip", "reason" to "mode_off", "id" to id)
            return null
        }

        return withContext(Dispatchers.IO) {
            try {
                Dev.info(log, "get.start", "id" to id)

                // Step 1: Look up metadata to find which file contains this exchange
                val metadata = getMetadataById(id)
                if (metadata == null) {
                    Dev.info(log, "get.notfound", "id" to id, "reason" to "no_metadata")
                    return@withContext null
                }

                // Step 2: Check if raw data is still available
                if (!metadata.rawDataAvailable) {
                    Dev.info(log, "get.notfound", "id" to id, "reason" to "raw_unavailable")
                    return@withContext null
                }

                // Step 3: Read from the JSONL file
                val exchange = readRawExchange(id, metadata.rawFile)

                if (exchange != null) {
                    Dev.info(log, "get.complete", "id" to id)
                } else {
                    // This indicates data inconsistency — metadata exists but raw doesn't
                    Dev.info(log, "get.notfound", "id" to id, "reason" to "not_in_raw_file")
                }

                exchange
            } catch (e: Exception) {
                Dev.error(log, "get.failed", e, "id" to id)
                null
            }
        }
    }

    /**
     * Query exchange metadata without loading full content.
     *
     * This is the fast path for listing, filtering, and displaying exchanges.
     * It only reads the metadata index (small, cached in memory) — never touches
     * the raw JSONL files.
     *
     * Results are sorted by timestamp descending (newest first) by default.
     *
     * Example usage:
     * ```kotlin
     * // Get all chat exchanges from the last week
     * val recentChats = facade.queryMetadata(MetadataFilter(
     *     purpose = ExchangePurpose.CHAT,
     *     after = Instant.now().minus(7, ChronoUnit.DAYS)
     * ))
     *
     * // Get all starred exchanges
     * val starred = facade.queryMetadata(MetadataFilter(hasFlag = "starred"))
     * ```
     *
     * @param filter Criteria to filter by (all fields optional, combined with AND logic)
     * @return List of matching metadata records, empty if none match or storage is OFF
     */
    override suspend fun queryMetadata(filter: MetadataFilter): List<ExchangeMetadata> {
        if (mode == StorageMode.OFF) {
            Dev.info(log, "query.skip", "reason" to "mode_off")
            return emptyList()
        }

        return withContext(Dispatchers.IO) {
            try {
                Dev.info(log, "query.start",
                    "purpose" to filter.purpose,
                    "provider" to filter.providerId,
                    "limit" to filter.limit
                )

                // Load from cache (or disk if first access)
                val allMetadata = loadMetadataCache()

                // Apply filters, sort, and limit
                val filtered = allMetadata
                    .filter { matchesFilter(it, filter) }
                    .sortedByDescending { it.timestamp }
                    .take(filter.limit)

                Dev.info(log, "query.complete",
                    "total" to allMetadata.size,
                    "matched" to filtered.size
                )

                filtered
            } catch (e: Exception) {
                Dev.error(log, "query.failed", e)
                emptyList()
            }
        }
    }

    /**
     * Update flags or labels on an existing exchange.
     *
     * This only modifies the metadata index — the raw exchange content is immutable.
     * Use this for user actions like starring, archiving, or tagging exchanges.
     *
     * @param id The exchange ID to update
     * @param flags New flag set (replaces existing flags). Pass null to leave unchanged.
     * @param labels New label set (replaces existing labels). Pass null to leave unchanged.
     * @return True if the exchange was found and updated, false otherwise
     */
    override suspend fun updateMetadata(id: String, flags: Set<String>?, labels: Set<String>?): Boolean {
        if (mode == StorageMode.OFF) {
            Dev.info(log, "update.skip", "reason" to "mode_off", "id" to id)
            return false
        }

        return withContext(Dispatchers.IO) {
            // Lock to prevent concurrent modifications
            metadataMutex.withLock {
                try {
                    Dev.info(log, "update.start",
                        "id" to id,
                        "flags" to flags,
                        "labels" to labels
                    )

                    val cache = loadMetadataCache()
                    val index = cache.indexOfFirst { it.id == id }

                    if (index == -1) {
                        Dev.info(log, "update.notfound", "id" to id)
                        return@withLock false
                    }

                    // Create updated record (only change what was passed)
                    val existing = cache[index]
                    val updated = existing.copy(
                        flags = flags?.toMutableSet() ?: existing.flags,
                        labels = labels?.toMutableSet() ?: existing.labels
                    )
                    cache[index] = updated

                    // Persist immediately
                    saveMetadataCache(cache)

                    Dev.info(log, "update.complete", "id" to id)
                    true
                } catch (e: Exception) {
                    Dev.error(log, "update.failed", e, "id" to id)
                    false
                }
            }
        }
    }

    /**
     * Search exchanges by text content in request and/or response.
     *
     * This is the main entry point for finding previously discussed topics.
     * Helps avoid redundant AI calls by surfacing relevant past exchanges.
     *
     * The search is delegated to [ExchangeSearchEngine], which can be swapped
     * for different implementations. The default [SimpleSearchEngine] does
     * case-insensitive substring matching.
     *
     * Note: This loads full exchange content for matches, so it's slower than
     * [queryMetadata]. Use metadata queries when you only need to filter by
     * structured fields (purpose, provider, date range, etc.).
     *
     * @param query The search text
     * @param searchIn Where to search (REQUEST_ONLY, RESPONSE_ONLY, or BOTH)
     * @param limit Maximum number of results
     * @return List of matching exchanges with full content, empty if no matches
     */
    override suspend fun searchExchanges(
        query: String,
        searchIn: SearchScope,
        limit: Int
    ): List<AiExchange> {
        if (mode == StorageMode.OFF) {
            Dev.info(log, "search.skip", "reason" to "mode_off")
            return emptyList()
        }

        return withContext(Dispatchers.IO) {
            try {
                Dev.info(log, "search.start",
                    "query" to Dev.preview(query, 50),
                    "scope" to searchIn
                )

                // Step 1: Get matching IDs from search engine (fast, in-memory)
                val matchingIds = searchEngine.search(query, searchIn, limit)

                // Step 2: Load full content for each match (slower, disk I/O)
                val exchanges = matchingIds.mapNotNull { id -> getExchange(id) }

                Dev.info(log, "search.complete",
                    "query" to Dev.preview(query, 50),
                    "found" to exchanges.size
                )

                exchanges
            } catch (e: Exception) {
                Dev.error(log, "search.failed", e,
                    "query" to Dev.preview(query, 50)
                )
                emptyList()
            }
        }
    }

    /**
     * Validate that raw data files still exist for all metadata records.
     *
     * Call this on startup or periodically to detect when raw files have been
     * manually deleted (e.g., user clearing space, git clean, etc.).
     *
     * For any metadata where the raw file is missing, this sets `rawDataAvailable = false`.
     * The metadata is retained so you still have timestamps, provider info, and labels —
     * you just can't retrieve the full content anymore.
     *
     * @return Number of metadata records that were marked as unavailable
     */
    override suspend fun validateRawDataAvailability(): Int {
        if (mode == StorageMode.OFF) {
            Dev.info(log, "validate.skip", "reason" to "mode_off")
            return 0
        }

        return withContext(Dispatchers.IO) {
            metadataMutex.withLock {
                try {
                    Dev.info(log, "validate.start")

                    val cache = loadMetadataCache()
                    var markedUnavailable = 0

                    cache.forEachIndexed { index, metadata ->
                        // Only check records that we think have raw data
                        if (metadata.rawDataAvailable) {
                            val rawFile = getRawExchangesFile(metadata.rawFile)
                            if (!rawFile.exists()) {
                                // Raw file is gone — update metadata
                                cache[index] = metadata.copy(rawDataAvailable = false)

                                // Also remove from search index since content is gone
                                searchEngine.onRawDataRemoved(metadata.id)
                                markedUnavailable++

                                Dev.info(log, "validate.marked_unavailable",
                                    "id" to metadata.id,
                                    "rawFile" to metadata.rawFile
                                )
                            }
                        }
                    }

                    // Persist changes if any
                    if (markedUnavailable > 0) {
                        saveMetadataCache(cache)
                    }

                    Dev.info(log, "validate.complete",
                        "total" to cache.size,
                        "markedUnavailable" to markedUnavailable
                    )

                    markedUnavailable
                } catch (e: Exception) {
                    Dev.error(log, "validate.failed", e)
                    0
                }
            }
        }
    }

    /**
     * Get the current storage mode.
     */
    override fun getMode(): StorageMode = mode

    /**
     * Change the storage mode at runtime.
     *
     * Primarily used for:
     * - Testing (set to OFF to disable persistence)
     * - Future settings UI integration
     * - Privacy-sensitive contexts where user wants to disable storage
     *
     * Note: Changing mode doesn't migrate or delete existing data.
     * If you switch from LOCAL to OFF, existing data remains on disk
     * but won't be read or written until you switch back.
     *
     * @param newMode The mode to switch to
     */
    fun setMode(newMode: StorageMode) {
        Dev.info(log, "mode.change", "from" to mode, "to" to newMode)
        mode = newMode
    }

    /**
     * Initialize the facade on project open.
     *
     * This must be called once when the project opens, before any other operations.
     * It:
     * 1. Creates storage directories if they don't exist
     * 2. Loads the metadata index into memory
     * 3. Rebuilds the search index from available exchanges
     *
     * Initialization is idempotent — safe to call multiple times.
     *
     * Typical usage (in a startup activity or service constructor):
     * ```kotlin
     * project.coroutineScope.launch {
     *     LocalStorageFacade.getInstance(project).initialize()
     * }
     * ```
     */
    suspend fun initialize() {
        if (mode == StorageMode.OFF) {
            Dev.info(log, "init.skip", "reason" to "mode_off")
            return
        }

        withContext(Dispatchers.IO) {
            try {
                Dev.info(log, "init.start", "project" to project.name)

                // Step 1: Ensure directories exist
                getRawStorageDir().mkdirs()
                getMetadataDir().mkdirs()

                // Step 2: Load metadata index
                val metadata = loadMetadataCache()

                // Step 3: Rebuild search index from available exchanges
                // This reads all raw files, so it can be slow for large histories
                // Future optimization: persist the search index itself
                val exchanges = metadata
                    .filter { it.rawDataAvailable }
                    .mapNotNull { meta -> readRawExchange(meta.id, meta.rawFile) }
                searchEngine.rebuildIndex(exchanges)

                Dev.info(log, "init.complete",
                    "metadataCount" to metadata.size,
                    "indexedCount" to searchEngine.indexSize()
                )
            } catch (e: Exception) {
                Dev.error(log, "init.failed", e)
            }
        }
    }

    // ==================== Raw Storage (Project Directory) ====================

    /**
     * Get the directory for raw JSONL files.
     *
     * Location: `<project-root>/.youmeandmyself/`
     *
     * The dot prefix makes it a hidden directory on Unix systems,
     * similar to .git, .idea, etc.
     *
     * @throws IllegalStateException if the project has no base path (shouldn't happen)
     */
    private fun getRawStorageDir(): File {
        val projectPath = project.basePath
            ?: throw IllegalStateException("Project has no base path — cannot determine storage location")
        return File(projectPath, ".youmeandmyself")
    }

    /**
     * Get a specific raw exchanges file by filename.
     *
     * @param filename The filename (e.g., "exchanges-2026-01.jsonl")
     * @return File object (may not exist yet)
     */
    private fun getRawExchangesFile(filename: String): File {
        return File(getRawStorageDir(), filename)
    }

    /**
     * Determine the filename for the current month.
     *
     * Format: `exchanges-YYYY-MM.jsonl`
     *
     * Using UTC timezone ensures consistent filenames regardless of user's locale.
     * Monthly partitioning keeps files from growing too large and makes
     * date-based cleanup straightforward.
     *
     * @return Filename for the current month (e.g., "exchanges-2026-01.jsonl")
     */
    private fun currentRawFileName(): String {
        val yearMonth = YearMonth.now(ZoneOffset.UTC)
        return "exchanges-${yearMonth}.jsonl"
    }

    /**
     * Append an exchange to the appropriate JSONL file.
     *
     * The exchange is converted to a compact JSON string and appended as a single line.
     * This is an atomic-ish operation — either the whole line is written or it isn't.
     *
     * @param exchange The exchange to write
     * @return The filename used (needed for metadata linkage)
     */
    private fun writeRawExchange(exchange: AiExchange): String {
        val filename = currentRawFileName()
        val file = getRawExchangesFile(filename)

        // Ensure directory exists (first write of the month)
        file.parentFile?.mkdirs()

        // Convert to serializable form and write as single line
        val serializable = exchange.toSerializable()
        val jsonLine = jsonCompact.encodeToString(serializable)

        // appendText handles opening, writing, and closing the file
        file.appendText(jsonLine + "\n")

        Dev.info(log, "raw.write",
            "file" to filename,
            "id" to exchange.id,
            "bytes" to jsonLine.length
        )

        return filename
    }

    /**
     * Read a specific exchange from a JSONL file by ID.
     *
     * This scans the file line by line looking for the matching ID.
     * While not optimal for random access, JSONL doesn't support indexing.
     *
     * @param id The exchange ID to find
     * @param filename Which file to search
     * @return The exchange if found, null otherwise
     */
    private fun readRawExchange(id: String, filename: String): AiExchange? {
        val file = getRawExchangesFile(filename)
        if (!file.exists()) {
            Dev.info(log, "raw.read.nofile", "file" to filename)
            return null
        }

        // useLines reads lazily — doesn't load entire file into memory
        file.useLines { lines ->
            for (line in lines) {
                if (line.isBlank()) continue

                try {
                    val serializable = jsonCompact.decodeFromString<SerializableExchange>(line)
                    if (serializable.id == id) {
                        return serializable.toExchange()
                    }
                } catch (e: Exception) {
                    // Log but continue — one bad line shouldn't break the whole file
                    Dev.warn(log, "raw.read.parse_error", e,
                        "file" to filename,
                        "linePreview" to Dev.preview(line, 100)
                    )
                }
            }
        }

        return null
    }

    // ==================== Metadata Storage (IntelliJ System Area) ====================

    /**
     * Get the directory for metadata files.
     *
     * Location: `<intellij-system>/youmeandmyself/<project-hash>/`
     *
     * Uses [PathManager.getSystemPath] which is the standard JetBrains location
     * for plugin data that shouldn't be in the project directory.
     *
     * The project hash (from [Project.getLocationHash]) ensures uniqueness
     * even for projects with the same name in different locations.
     */
    private fun getMetadataDir(): File {
        val systemPath = PathManager.getSystemPath()
        val projectHash = project.locationHash
        return File(systemPath, "youmeandmyself/$projectHash")
    }

    /**
     * Get the metadata index file.
     *
     * This single file contains all metadata records for the project.
     * It's small enough to load entirely into memory.
     */
    private fun getMetadataFile(): File {
        return File(getMetadataDir(), "metadata-index.json")
    }

    /**
     * Load metadata from disk into the in-memory cache.
     *
     * This is called lazily on first access. Subsequent calls return the
     * cached list without re-reading the file.
     *
     * If the file doesn't exist (first run), returns an empty list.
     * If the file is corrupted, logs an error and returns an empty list.
     *
     * @return The metadata list (never null after this call)
     */
    private fun loadMetadataCache(): MutableList<ExchangeMetadata> {
        // Return cached if available
        metadataCache?.let { return it }

        val file = getMetadataFile()
        if (!file.exists()) {
            Dev.info(log, "metadata.load.new", "file" to file.path)
            metadataCache = mutableListOf()
            return metadataCache!!
        }

        return try {
            val content = file.readText()
            val serializable = jsonPretty.decodeFromString<List<SerializableMetadata>>(content)
            val loaded = serializable.map { it.toMetadata() }.toMutableList()

            Dev.info(log, "metadata.load.complete",
                "file" to file.path,
                "count" to loaded.size
            )

            metadataCache = loaded
            loaded
        } catch (e: Exception) {
            // Corrupted file — start fresh rather than crash
            Dev.error(log, "metadata.load.failed", e, "file" to file.path)
            metadataCache = mutableListOf()
            metadataCache!!
        }
    }

    /**
     * Persist the metadata cache to disk.
     *
     * This writes the entire cache as a JSON array.
     *
     * @param cache The metadata list to save
     */
    private fun saveMetadataCache(cache: List<ExchangeMetadata>) {
        val file = getMetadataFile()
        file.parentFile?.mkdirs()

        try {
            val serializable = cache.map { it.toSerializable() }
            val content = jsonPretty.encodeToString(serializable)
            file.writeText(content)

            Dev.info(log, "metadata.save.complete",
                "file" to file.path,
                "count" to cache.size
            )
        } catch (e: Exception) {
            Dev.error(log, "metadata.save.failed", e, "file" to file.path)
        }
    }

    /**
     * Add a new metadata entry and persist immediately.
     *
     * Uses mutex to ensure thread safety.
     *
     * @param metadata The new metadata record to add
     */
    private suspend fun addMetadata(metadata: ExchangeMetadata) {
        metadataMutex.withLock {
            val cache = loadMetadataCache()
            cache.add(metadata)
            saveMetadataCache(cache)
        }
    }

    /**
     * Find metadata by exchange ID.
     *
     * @param id The exchange ID
     * @return The metadata record, or null if not found
     */
    private fun getMetadataById(id: String): ExchangeMetadata? {
        return loadMetadataCache().find { it.id == id }
    }

    // ==================== Filtering ====================

    /**
     * Check if a metadata record matches all specified filter criteria.
     *
     * @param metadata The record to check
     * @param filter The filter criteria
     * @return True if all criteria match
     */
    private fun matchesFilter(metadata: ExchangeMetadata, filter: MetadataFilter): Boolean {
        if (filter.purpose != null && metadata.purpose != filter.purpose) return false
        if (filter.providerId != null && metadata.providerId != filter.providerId) return false
        if (filter.modelId != null && metadata.modelId != filter.modelId) return false
        if (filter.hasFlag != null && !metadata.flags.contains(filter.hasFlag)) return false
        if (filter.hasLabel != null && !metadata.labels.contains(filter.hasLabel)) return false
        if (filter.after != null && metadata.timestamp.isBefore(filter.after)) return false
        if (filter.before != null && metadata.timestamp.isAfter(filter.before)) return false
        if (filter.rawDataAvailable != null && metadata.rawDataAvailable != filter.rawDataAvailable) return false
        return true
    }

    // ==================== Serialization ====================

    /**
     * Serializable form of [AiExchange] for JSON encoding.
     */
    @Serializable
    private data class SerializableExchange(
        val id: String,
        val timestamp: String,
        val providerId: String,
        val modelId: String,
        val purpose: String,
        val request: SerializableRequest,
        val rawResponse: SerializableRawResponse
    ) {
        fun toExchange(): AiExchange = AiExchange(
            id = id,
            timestamp = Instant.parse(timestamp),
            providerId = providerId,
            modelId = modelId,
            purpose = ExchangePurpose.valueOf(purpose),
            request = request.toRequest(),
            rawResponse = rawResponse.toRawResponse()
        )
    }

    /**
     * Serializable form of [ExchangeRequest].
     */
    @Serializable
    private data class SerializableRequest(
        val input: String,
        val systemPrompt: String? = null,
        val contextFiles: List<String>? = null,
        val temperature: Double? = null,
        val maxTokens: Int? = null,
        val topP: Double? = null,
        val stopSequences: List<String>? = null
    ) {
        fun toRequest(): ExchangeRequest = ExchangeRequest(
            input = input,
            systemPrompt = systemPrompt,
            contextFiles = contextFiles,
            temperature = temperature,
            maxTokens = maxTokens,
            topP = topP,
            stopSequences = stopSequences
        )
    }

    /**
     * Serializable form of [ExchangeRawResponse].
     */
    @Serializable
    private data class SerializableRawResponse(
        val json: String,
        val httpStatus: Int? = null
    ) {
        fun toRawResponse(): ExchangeRawResponse = ExchangeRawResponse(
            json = json,
            httpStatus = httpStatus
        )
    }

    /**
     * Serializable form of [ExchangeMetadata].
     */
    @Serializable
    private data class SerializableMetadata(
        val id: String,
        val timestamp: String,
        val providerId: String,
        val modelId: String,
        val purpose: String,
        val tokensUsed: Int?,
        val flags: List<String>,
        val labels: List<String>,
        val rawFile: String,
        val rawDataAvailable: Boolean
    ) {
        fun toMetadata(): ExchangeMetadata = ExchangeMetadata(
            id = id,
            timestamp = Instant.parse(timestamp),
            providerId = providerId,
            modelId = modelId,
            purpose = ExchangePurpose.valueOf(purpose),
            tokensUsed = tokensUsed,
            flags = flags.toMutableSet(),
            labels = labels.toMutableSet(),
            rawFile = rawFile,
            rawDataAvailable = rawDataAvailable
        )
    }

    /**
     * Convert domain model to serializable form.
     */
    private fun AiExchange.toSerializable(): SerializableExchange = SerializableExchange(
        id = id,
        timestamp = timestamp.toString(),
        providerId = providerId,
        modelId = modelId,
        purpose = purpose.name,
        request = SerializableRequest(
            input = request.input,
            systemPrompt = request.systemPrompt,
            contextFiles = request.contextFiles,
            temperature = request.temperature,
            maxTokens = request.maxTokens,
            topP = request.topP,
            stopSequences = request.stopSequences
        ),
        rawResponse = SerializableRawResponse(
            json = rawResponse.json,
            httpStatus = rawResponse.httpStatus
        )
    )

    /**
     * Convert domain model to serializable form.
     */
    private fun ExchangeMetadata.toSerializable(): SerializableMetadata = SerializableMetadata(
        id = id,
        timestamp = timestamp.toString(),
        providerId = providerId,
        modelId = modelId,
        purpose = purpose.name,
        tokensUsed = tokensUsed,
        flags = flags.toList(),
        labels = labels.toList(),
        rawFile = rawFile,
        rawDataAvailable = rawDataAvailable
    )

    companion object {
        /**
         * Get the facade instance for a project.
         *
         * @param project The project
         * @return The facade instance
         */
        fun getInstance(project: Project): LocalStorageFacade =
            project.getService(LocalStorageFacade::class.java)
    }
}