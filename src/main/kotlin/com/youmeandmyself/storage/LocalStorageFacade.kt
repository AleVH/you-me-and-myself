package com.youmeandmyself.storage

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
import com.youmeandmyself.context.orchestrator.config.SummaryConfig
import com.youmeandmyself.context.orchestrator.config.SummaryMode

/**
 * LOCAL mode implementation of [StorageFacade].
 *
 * ## Architecture: Centralized Storage with SQLite
 *
 * This facade implements a two-layer storage strategy:
 *
 * 1. **JSONL files** (source of truth) — full request + raw response content
 *    - Location: `{storage-root}/chat/{project-id}/exchanges-YYYY-Www.jsonl`
 *    - Append-only, never modified after writing
 *    - Weekly partitioned using ISO week numbering
 *    - If everything else breaks, the database can be rebuilt from these
 *
 * 2. **SQLite database** (the brain) — metadata, relationships, fast queries
 *    - Location: `{storage-root}/youmeandmyself.db`
 *    - 10 tables (all created upfront, some empty until their features are built)
 *    - Replaces the old JSON metadata-index.json file
 *    - Enables proper relational queries, indexing, and cross-project operations
 *
 * ## What Changed from v1
 *
 * | Aspect | v1 (Old) | v2 (New) |
 * |--------|----------|----------|
 * | Raw data location | Project dir (.youmeandmyself/) | Centralized root (~/YouMeAndMyself/chat/{id}/) |
 * | JSONL partitioning | Monthly (YYYY-MM) | Weekly (YYYY-Www) |
 * | Metadata storage | JSON file in IntelliJ system area | SQLite database in storage root |
 * | Metadata caching | Full in-memory list | SQLite with indexed queries |
 * | Project awareness | Implicit (one facade per project) | Explicit projectId on all operations |
 * | Serialization | Custom SerializableExchange wrappers | Direct kotlinx.serialization |
 *
 * ## Thread Safety
 *
 * - Write operations use a [Mutex] to prevent concurrent SQLite writes and JSONL appends
 * - SQLite is configured in WAL mode (concurrent reads allowed alongside a single writer)
 * - JSONL files are append-only, so reads don't conflict with writes
 *
 * ## Error Handling Philosophy
 *
 * All I/O errors are:
 * - Logged with full context (file paths, operation details, stack traces)
 * - Returned as graceful fallbacks (null, empty list, false)
 * - Never thrown to callers
 *
 * The plugin remains functional even if storage fails — the user can still chat
 * with AI, they just won't have persistence.
 *
 * @param project The IntelliJ project this facade is scoped to
 */
@Service(Service.Level.PROJECT)
class LocalStorageFacade(private val project: Project) : StorageFacade {

    private val log = Logger.getInstance(LocalStorageFacade::class.java)

    /**
     * JSON parser for JSONL raw exchange files.
     *
     * Compact (no pretty printing) to minimize file size since these can grow large.
     * ignoreUnknownKeys provides forward compatibility with newer versions.
     * encodeDefaults ensures all fields are present for clarity.
     */
    private val json = Json {
        prettyPrint = false
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * Storage path configuration.
     *
     * Resolves all file paths for JSONL and the database.
     * Initialized in [initialize] — null means "not yet initialized".
     */
    private var storageConfig: StorageConfig? = null

    /**
     * SQLite database helper.
     *
     * Manages the connection, schema, and provides typed query methods.
     * Initialized in [initialize] — null means "not yet initialized".
     */
    private var db: DatabaseHelper? = null

    /**
     * Mutex protecting write operations.
     *
     * Ensures only one coroutine at a time can:
     * - Append to JSONL files
     * - Write to SQLite
     *
     * This prevents concurrent file corruption and SQLite write conflicts.
     * Read operations don't need locking thanks to WAL mode and append-only JSONL.
     */
    private val writeMutex = Mutex()

    /**
     * Search engine instance for text content queries.
     *
     * Currently uses [SimpleSearchEngine] (in-memory substring matching).
     * Can be swapped for SQLite FTS or another implementation without
     * changing the facade interface.
     */
    private val searchEngine: SimpleSearchEngine = SimpleSearchEngine()

    /**
     * Current storage mode.
     *
     * OFF = no persistence (useful for testing or privacy-sensitive contexts)
     * LOCAL = write to disk (this implementation)
     * CLOUD = future remote sync capability
     */
    private var mode: StorageMode = StorageMode.LOCAL

    // ==================== Public API ====================

    /**
     * Persist a complete AI exchange.
     *
     * This is the primary entry point for saving AI interactions. It:
     * 1. Ensures the project exists in the projects table
     * 2. Appends the full exchange to a weekly JSONL file
     * 3. Inserts metadata into the chat_exchanges SQLite table
     * 4. Updates the search index for text queries
     *
     * The exchange is immediately persisted — no batching or delayed write.
     * This ensures data is safe even if the IDE crashes right after.
     *
     * @param exchange The complete exchange to store (must have a unique ID)
     * @param projectId The project this exchange belongs to
     * @return The exchange ID if saved successfully, null if storage is OFF or an error occurred
     */
    override suspend fun saveExchange(exchange: AiExchange, projectId: String): String? {
        if (mode == StorageMode.OFF) {
            Dev.info(log, "save.skip", "reason" to "mode_off", "id" to exchange.id)
            return null
        }

        return withContext(Dispatchers.IO) {
            writeMutex.withLock {
                try {
                    val config = requireConfig()
                    val database = requireDb()

                    Dev.info(log, "save.start",
                        "id" to exchange.id,
                        "projectId" to projectId,
                        "provider" to exchange.providerId,
                        "purpose" to exchange.purpose
                    )

                    // Step 1: Ensure project exists in the projects table
                    ensureProjectRegistered(projectId, database)

                    // Step 2: Ensure project directories exist for JSONL files
                    config.ensureProjectDirectoriesExist(projectId)

                    // Step 3: Append raw exchange to weekly JSONL file
                    val rawFile = writeRawExchange(exchange, projectId, config)

                    // Step 4: Insert metadata into SQLite
                    database.execute(
                        """
                        INSERT INTO chat_exchanges 
                            (id, project_id, provider_id, model_id, purpose, timestamp, tokens_used, raw_file, raw_available)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1)
                        """.trimIndent(),
                        exchange.id,
                        projectId,
                        exchange.providerId,
                        exchange.modelId,
                        exchange.purpose.name,
                        exchange.timestamp.toString(),
                        exchange.tokensUsed,
                        rawFile
                    )

                    // Step 5: Update search index
                    searchEngine.onExchangeSaved(exchange)

                    Dev.info(log, "save.complete",
                        "id" to exchange.id,
                        "rawFile" to rawFile
                    )

                    exchange.id
                } catch (e: Exception) {
                    Dev.error(log, "save.failed", e,
                        "id" to exchange.id,
                        "projectId" to projectId
                    )
                    null
                }
            }
        }
    }

    /**
     * Retrieve a full exchange by its ID.
     *
     * This loads the complete request and raw response from the JSONL file.
     * Use this when you need the actual content (e.g., for re-display, extraction).
     * For listing/filtering, use [queryMetadata] instead — it's much faster.
     *
     * @param id The unique exchange ID
     * @param projectId The project this exchange belongs to
     * @return The complete exchange, or null if not found, raw data unavailable, or storage is OFF
     */
    override suspend fun getExchange(id: String, projectId: String): AiExchange? {
        if (mode == StorageMode.OFF) {
            Dev.info(log, "get.skip", "reason" to "mode_off", "id" to id)
            return null
        }

        return withContext(Dispatchers.IO) {
            try {
                val config = requireConfig()
                val database = requireDb()

                Dev.info(log, "get.start", "id" to id)

                // Step 1: Look up metadata in SQLite to find which file has this exchange
                val row = database.queryOne(
                    "SELECT raw_file, raw_available FROM chat_exchanges WHERE id = ? AND project_id = ?",
                    id, projectId
                ) { rs ->
                    Pair(rs.getString("raw_file"), rs.getInt("raw_available") == 1)
                }

                if (row == null) {
                    Dev.info(log, "get.notfound", "id" to id, "reason" to "no_metadata")
                    return@withContext null
                }

                val (rawFile, rawAvailable) = row

                if (!rawAvailable) {
                    Dev.info(log, "get.notfound", "id" to id, "reason" to "raw_unavailable")
                    return@withContext null
                }

                // Step 2: Read from the JSONL file
                val exchange = readRawExchange(id, projectId, rawFile, config)

                if (exchange != null) {
                    Dev.info(log, "get.complete", "id" to id)
                } else {
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
     * It queries SQLite only — never touches JSONL files.
     *
     * Results are sorted by timestamp descending (newest first).
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
                val database = requireDb()

                Dev.info(log, "query.start",
                    "projectId" to filter.projectId,
                    "purpose" to filter.purpose,
                    "limit" to filter.limit
                )

                // Build dynamic WHERE clause from filter
                val conditions = mutableListOf<String>()
                val params = mutableListOf<Any?>()

                filter.projectId?.let {
                    conditions.add("project_id = ?")
                    params.add(it)
                }
                filter.purpose?.let {
                    conditions.add("purpose = ?")
                    params.add(it.name)
                }
                filter.providerId?.let {
                    conditions.add("provider_id = ?")
                    params.add(it)
                }
                filter.modelId?.let {
                    conditions.add("model_id = ?")
                    params.add(it)
                }
                filter.after?.let {
                    conditions.add("timestamp > ?")
                    params.add(it.toString())
                }
                filter.before?.let {
                    conditions.add("timestamp < ?")
                    params.add(it.toString())
                }
                filter.rawDataAvailable?.let {
                    conditions.add("raw_available = ?")
                    params.add(if (it) 1 else 0)
                }
                filter.hasFlag?.let {
                    conditions.add("flags LIKE ?")
                    params.add("%$it%")
                }
                filter.hasLabel?.let {
                    conditions.add("labels LIKE ?")
                    params.add("%$it%")
                }

                val whereClause = if (conditions.isEmpty()) "" else "WHERE ${conditions.joinToString(" AND ")}"
                val sql = """
                    SELECT id, project_id, provider_id, model_id, purpose, timestamp,
                           tokens_used, raw_file, raw_available, flags, labels
                    FROM chat_exchanges
                    $whereClause
                    ORDER BY timestamp DESC
                    LIMIT ?
                """.trimIndent()

                params.add(filter.limit)

                val results = database.query(sql, *params.toTypedArray()) { rs ->
                    ExchangeMetadata(
                        id = rs.getString("id"),
                        projectId = rs.getString("project_id"),
                        timestamp = Instant.parse(rs.getString("timestamp")),
                        providerId = rs.getString("provider_id"),
                        modelId = rs.getString("model_id"),
                        purpose = ExchangePurpose.valueOf(rs.getString("purpose")),
                        tokensUsed = rs.getInt("tokens_used").takeIf { !rs.wasNull() },
                        flags = ExchangeMetadata.decodeSet(rs.getString("flags")),
                        labels = ExchangeMetadata.decodeSet(rs.getString("labels")),
                        rawFile = rs.getString("raw_file"),
                        rawDataAvailable = rs.getInt("raw_available") == 1
                    )
                }

                Dev.info(log, "query.complete", "matched" to results.size)

                results
            } catch (e: Exception) {
                Dev.error(log, "query.failed", e)
                emptyList()
            }
        }
    }

    /**
     * Update flags or labels on an existing exchange.
     *
     * Only modifies SQLite metadata — the raw JSONL content is immutable.
     * Use this for user actions like starring, archiving, or tagging.
     *
     * @param id The exchange ID to update
     * @param flags New flag set (replaces existing). Pass null to leave unchanged.
     * @param labels New label set (replaces existing). Pass null to leave unchanged.
     * @return True if the exchange was found and updated, false otherwise
     */
    override suspend fun updateMetadata(id: String, flags: Set<String>?, labels: Set<String>?): Boolean {
        if (mode == StorageMode.OFF) {
            Dev.info(log, "update.skip", "reason" to "mode_off", "id" to id)
            return false
        }

        return withContext(Dispatchers.IO) {
            writeMutex.withLock {
                try {
                    val database = requireDb()

                    Dev.info(log, "update.start",
                        "id" to id,
                        "flags" to flags,
                        "labels" to labels
                    )

                    // Build dynamic SET clause — only update what was passed
                    val setClauses = mutableListOf<String>()
                    val params = mutableListOf<Any?>()

                    flags?.let {
                        setClauses.add("flags = ?")
                        params.add(ExchangeMetadata.encodeSet(it))
                    }
                    labels?.let {
                        setClauses.add("labels = ?")
                        params.add(ExchangeMetadata.encodeSet(it))
                    }

                    if (setClauses.isEmpty()) {
                        Dev.info(log, "update.noop", "id" to id, "reason" to "nothing_to_update")
                        return@withLock true
                    }

                    params.add(id)
                    val sql = "UPDATE chat_exchanges SET ${setClauses.joinToString(", ")} WHERE id = ?"
                    val rowsAffected = database.execute(sql, *params.toTypedArray())

                    val found = rowsAffected > 0
                    if (found) {
                        Dev.info(log, "update.complete", "id" to id)
                    } else {
                        Dev.info(log, "update.notfound", "id" to id)
                    }

                    found
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
     * Delegates to [SimpleSearchEngine] for finding matching IDs, then loads
     * full content from JSONL for each match.
     *
     * @param query The search text
     * @param projectId The project to search within
     * @param searchIn Where to search (REQUEST_ONLY, RESPONSE_ONLY, or BOTH)
     * @param limit Maximum number of results
     * @return List of matching exchanges with full content, empty if no matches
     */
    override suspend fun searchExchanges(
        query: String,
        projectId: String,
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
                    "projectId" to projectId,
                    "scope" to searchIn
                )

                // Step 1: Get matching IDs from search engine (fast, in-memory)
                val matchingIds = searchEngine.search(query, searchIn, limit)

                // Step 2: Load full content for each match
                val exchanges = matchingIds.mapNotNull { id -> getExchange(id, projectId) }

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
     * Scans all chat_exchanges for the given project where raw_available = 1,
     * checks the JSONL file on disk, and sets raw_available = 0 for any
     * missing files.
     *
     * @param projectId The project to validate
     * @return Number of metadata records marked as unavailable
     */
    override suspend fun validateRawDataAvailability(projectId: String): Int {
        if (mode == StorageMode.OFF) {
            Dev.info(log, "validate.skip", "reason" to "mode_off")
            return 0
        }

        return withContext(Dispatchers.IO) {
            writeMutex.withLock {
                try {
                    val config = requireConfig()
                    val database = requireDb()

                    Dev.info(log, "validate.start", "projectId" to projectId)

                    // Get all distinct raw files that we think are still available
                    val rawFiles = database.query(
                        """
                        SELECT DISTINCT raw_file FROM chat_exchanges
                        WHERE project_id = ? AND raw_available = 1
                        """.trimIndent(),
                        projectId
                    ) { rs -> rs.getString("raw_file") }

                    var markedUnavailable = 0

                    for (rawFile in rawFiles) {
                        val file = config.chatFile(projectId, rawFile)
                        if (!file.exists()) {
                            // Raw file is gone — mark all exchanges pointing to it
                            val affected = database.execute(
                                """
                                UPDATE chat_exchanges
                                SET raw_available = 0
                                WHERE project_id = ? AND raw_file = ? AND raw_available = 1
                                """.trimIndent(),
                                projectId, rawFile
                            )
                            markedUnavailable += affected

                            Dev.info(log, "validate.marked_unavailable",
                                "rawFile" to rawFile,
                                "affected" to affected
                            )
                        }
                    }

                    // Also remove from search index for affected exchanges
                    if (markedUnavailable > 0) {
                        val unavailableIds = database.query(
                            """
                            SELECT id FROM chat_exchanges
                            WHERE project_id = ? AND raw_available = 0
                            """.trimIndent(),
                            projectId
                        ) { rs -> rs.getString("id") }

                        unavailableIds.forEach { searchEngine.onRawDataRemoved(it) }
                    }

                    Dev.info(log, "validate.complete",
                        "projectId" to projectId,
                        "markedUnavailable" to markedUnavailable
                    )

                    markedUnavailable
                } catch (e: Exception) {
                    Dev.error(log, "validate.failed", e, "projectId" to projectId)
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

    // ==================== Initialization ====================

    /**
     * Initialize the facade when the project opens.
     *
     * Must be called once before any other operations. It:
     * 1. Creates the StorageConfig with the storage root path
     * 2. Ensures all directories exist
     * 3. Opens the SQLite database and creates the schema
     * 4. Registers the current project in the projects table
     * 5. Rebuilds the search index from available exchanges
     *
     * Initialization is idempotent — safe to call multiple times.
     *
     * @param customRootPath Optional custom storage root (default: ~/YouMeAndMyself/)
     */
    suspend fun initialize(customRootPath: File? = null) {
        if (mode == StorageMode.OFF) {
            Dev.info(log, "init.skip", "reason" to "mode_off")
            return
        }

        withContext(Dispatchers.IO) {
            try {
                Dev.info(log, "init.start", "project" to project.name)

                // Step 1: Set up storage paths
                val config = if (customRootPath != null) {
                    StorageConfig(customRootPath)
                } else {
                    StorageConfig.withDefaultRoot()
                }
                config.ensureDirectoriesExist()
                storageConfig = config

                // Step 2: Open SQLite database
                val database = DatabaseHelper(config.databaseFile)
                database.open()
                db = database

                // Step 3: Register this project
                val projectId = resolveProjectId()
                ensureProjectRegistered(projectId, database)

                // Step 4: Rebuild search index from available exchanges
                rebuildSearchIndex(projectId, config, database)

                Dev.info(log, "init.complete",
                    "project" to project.name,
                    "storageRoot" to config.root.absolutePath,
                    "indexedCount" to searchEngine.indexSize()
                )
            } catch (e: Exception) {
                Dev.error(log, "init.failed", e)
            }
        }
    }

    /**
     * Shut down the facade when the project is closing.
     *
     * Closes the database connection. After this, no operations are possible
     * without calling [initialize] again.
     */
    fun dispose() {
        try {
            db?.close()
            db = null
            storageConfig = null
            Dev.info(log, "dispose.complete")
        } catch (e: Exception) {
            Dev.error(log, "dispose.failed", e)
        }
    }

    // ==================== Raw JSONL Storage ====================

    /**
     * Append an exchange to the appropriate weekly JSONL file.
     *
     * The exchange is serialized to compact JSON and appended as a single line.
     * The file is created if it doesn't exist (first exchange of the week).
     *
     * @param exchange The exchange to write
     * @param projectId The project ID (determines the subdirectory)
     * @param config Storage path configuration
     * @return The filename used (e.g., "exchanges-2026-W05.jsonl") for metadata linkage
     */
    private fun writeRawExchange(exchange: AiExchange, projectId: String, config: StorageConfig): String {
        val file = config.currentChatFile(projectId)
        val filename = file.name

        // Ensure project directory exists (first write for this project)
        file.parentFile?.mkdirs()

        // Serialize to compact JSON and append as a single line.
        // Include projectId in the record for database rebuild capability.
        val serializable = exchange.toJsonl(projectId)
        val jsonLine = json.encodeToString(serializable)
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
     * Scans the file line by line looking for the matching ID.
     * While not optimal for random access, JSONL doesn't support indexing.
     * Lines that fail to parse are logged and skipped (one bad line doesn't
     * break the whole file).
     *
     * @param id The exchange ID to find
     * @param projectId The project ID (determines the subdirectory)
     * @param filename Which JSONL file to search
     * @param config Storage path configuration
     * @return The exchange if found, null otherwise
     */
    private fun readRawExchange(
        id: String,
        projectId: String,
        filename: String,
        config: StorageConfig
    ): AiExchange? {
        val file = config.chatFile(projectId, filename)
        if (!file.exists()) {
            Dev.info(log, "raw.read.nofile", "file" to filename)
            return null
        }

        // useLines reads lazily — doesn't load entire file into memory
        file.useLines { lines ->
            for (line in lines) {
                if (line.isBlank()) continue

                try {
                    val record = json.decodeFromString<JsonlExchange>(line)
                    if (record.id == id) {
                        return record.toExchange()
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

    // ==================== Project Registration ====================

    /**
     * Ensure the project exists in the projects table.
     *
     * Uses INSERT OR IGNORE so this is safe to call on every save — if the project
     * already exists, it just updates last_opened_at.
     *
     * @param projectId The project's unique identifier
     * @param database The database helper
     */
    private fun ensureProjectRegistered(projectId: String, database: DatabaseHelper) {
        val projectPath = project.basePath ?: "unknown"
        val now = Instant.now().toString()

        // Insert if new, or update last_opened_at if existing
        database.execute(
            """
            INSERT INTO projects (id, name, path, created_at, last_opened_at, is_active)
            VALUES (?, ?, ?, ?, ?, 1)
            ON CONFLICT(id) DO UPDATE SET
                last_opened_at = excluded.last_opened_at,
                is_active = 1
            """.trimIndent(),
            projectId,
            project.name,
            projectPath,
            now,
            now
        )
    }

    /**
     * Derive a stable project identifier from the IntelliJ project.
     *
     * Uses the project's location hash, which is a stable identifier that
     * doesn't change when the project is renamed (it's based on the path).
     *
     * @return A string identifier suitable for use as a directory name and database key
     */
    fun resolveProjectId(): String = project.locationHash

    // ==================== Search Index ====================

    /**
     * Rebuild the in-memory search index from all available JSONL files.
     *
     * This reads every exchange from every JSONL file for the project.
     * It can be slow for large histories — future optimization: persist
     * the search index itself (e.g., in SQLite FTS).
     *
     * Called during [initialize].
     */
    private suspend fun rebuildSearchIndex(projectId: String, config: StorageConfig, database: DatabaseHelper) {
        val rawFiles = database.query(
            """
            SELECT DISTINCT raw_file FROM chat_exchanges
            WHERE project_id = ? AND raw_available = 1
            """.trimIndent(),
            projectId
        ) { rs -> rs.getString("raw_file") }

        val exchanges = mutableListOf<AiExchange>()
        for (rawFile in rawFiles) {
            val file = config.chatFile(projectId, rawFile)
            if (!file.exists()) continue

            file.useLines { lines ->
                for (line in lines) {
                    if (line.isBlank()) continue
                    try {
                        val record = json.decodeFromString<JsonlExchange>(line)
                        exchanges.add(record.toExchange())
                    } catch (e: Exception) {
                        Dev.warn(log, "index.rebuild.parse_error", e,
                            "file" to rawFile,
                            "linePreview" to Dev.preview(line, 100)
                        )
                    }
                }
            }
        }

        searchEngine.rebuildIndex(exchanges)
    }

    // ==================== Internal Helpers ====================

    /**
     * Get the storage config, throwing if not initialized.
     */
    private fun requireConfig(): StorageConfig {
        return storageConfig
            ?: throw IllegalStateException("StorageFacade not initialized. Call initialize() first.")
    }

    /**
     * Get the database helper, throwing if not initialized.
     */
    private fun requireDb(): DatabaseHelper {
        return db
            ?: throw IllegalStateException("StorageFacade not initialized. Call initialize() first.")
    }

    // ==================== JSONL Serialization ====================

    /**
     * Serializable record for JSONL files.
     *
     * This is what gets written as one line in the JSONL file. It must contain
     * enough information to fully rebuild the SQLite database if needed.
     *
     * Note: [projectId] is included in the JSONL record even though the file
     * is already inside a project-specific directory. This redundancy is intentional —
     * it makes each record self-describing and simplifies database rebuilds.
     */
    @Serializable
    data class JsonlExchange(
        val id: String,
        val projectId: String? = null,
        val timestamp: String,
        val providerId: String,
        val modelId: String,
        val purpose: String,
        val request: JsonlRequest,
        val rawResponse: JsonlRawResponse,
        val tokensUsed: Int? = null
    ) {
        fun toExchange(): AiExchange = AiExchange(
            id = id,
            timestamp = Instant.parse(timestamp),
            providerId = providerId,
            modelId = modelId,
            purpose = ExchangePurpose.valueOf(purpose),
            request = request.toRequest(),
            rawResponse = rawResponse.toRawResponse(),
            tokensUsed = tokensUsed
        )
    }

    @Serializable
    data class JsonlRequest(
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

    @Serializable
    data class JsonlRawResponse(
        val json: String,
        val httpStatus: Int? = null
    ) {
        fun toRawResponse(): ExchangeRawResponse = ExchangeRawResponse(
            json = json,
            httpStatus = httpStatus
        )
    }

    /**
     * Convert an AiExchange domain model to JSONL serializable form.
     *
     * @param projectId Optional project ID to embed in the record (for rebuild).
     *                  Included for self-describing records — even though the file
     *                  is already in a project-specific directory.
     */
    private fun AiExchange.toJsonl(projectId: String? = null): JsonlExchange = JsonlExchange(
        id = id,
        projectId = projectId,
        timestamp = timestamp.toString(),
        providerId = providerId,
        modelId = modelId,
        purpose = purpose.name,
        request = JsonlRequest(
            input = request.input,
            systemPrompt = request.systemPrompt,
            contextFiles = request.contextFiles,
            temperature = request.temperature,
            maxTokens = request.maxTokens,
            topP = request.topP,
            stopSequences = request.stopSequences
        ),
        rawResponse = JsonlRawResponse(
            json = rawResponse.json,
            httpStatus = rawResponse.httpStatus
        ),
        tokensUsed = tokensUsed
    )

    companion object {
        fun getInstance(project: Project): LocalStorageFacade =
            project.getService(LocalStorageFacade::class.java)
    }

    // ── Summary Config (Phase 3) ──

    /**
     * Load the summary configuration for a project.
     *
     * Reads are safe without the Mutex (WAL mode allows concurrent reads).
     *
     * @param projectId The project's unique identifier
     * @return The stored config, or null if no config row exists
     */
    override fun loadSummaryConfig(projectId: String): SummaryConfig? {
        val database = db ?: return null

        return try {
            database.queryOne(
                """SELECT mode, enabled, max_tokens_per_session, complexity_threshold,
                   include_patterns, exclude_patterns, min_file_lines
                   FROM summary_config WHERE project_id = ?""",
                projectId
            ) { rs ->
                SummaryConfig(
                    mode = SummaryMode.fromString(rs.getString("mode")),
                    enabled = rs.getInt("enabled") == 1,
                    maxTokensPerSession = rs.getObject("max_tokens_per_session") as? Int,
                    tokensUsedSession = 0, // Always reset on load
                    complexityThreshold = rs.getObject("complexity_threshold") as? Int,
                    includePatterns = deserializePatternsHelper(rs.getString("include_patterns")),
                    excludePatterns = deserializePatternsHelper(rs.getString("exclude_patterns")),
                    minFileLines = rs.getObject("min_file_lines") as? Int,
                    dryRun = true // Default safe; column to be added later if needed
                )
            }
        } catch (e: Throwable) {
            Dev.warn(log, "storage.load_summary_config.failed", e, "projectId" to projectId)
            null
        }
    }

    /**
     * Save (upsert) the summary configuration for a project.
     *
     * Uses the writeMutex to maintain the single-writer model.
     * This is a blocking call — summary config saves are infrequent
     * (only on settings apply or kill switch toggle).
     *
     * @param projectId The project's unique identifier
     * @param config The configuration to persist
     */
    override fun saveSummaryConfig(projectId: String, config: SummaryConfig) {
        val database = db ?: run {
            Dev.warn(log, "storage.save_summary_config.no_db", null, "projectId" to projectId)
            return
        }

        try {
            // Using runBlocking here is acceptable because:
            // 1. Summary config saves are rare (settings apply, kill switch)
            // 2. The write is tiny and fast
            // 3. We need the Mutex to protect the single-writer model
            kotlinx.coroutines.runBlocking {
                writeMutex.withLock {
                    database.execute(
                        """INSERT OR REPLACE INTO summary_config
                           (project_id, mode, enabled, max_tokens_per_session, tokens_used_session,
                            complexity_threshold, include_patterns, exclude_patterns, min_file_lines)
                           VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                        projectId,
                        config.mode.name,
                        if (config.enabled) 1 else 0,
                        config.maxTokensPerSession,
                        config.tokensUsedSession,
                        config.complexityThreshold,
                        serializePatternsHelper(config.includePatterns),
                        serializePatternsHelper(config.excludePatterns),
                        config.minFileLines
                    )
                }
            }

            Dev.info(log, "storage.save_summary_config.ok",
                "projectId" to projectId,
                "mode" to config.mode.name,
                "enabled" to config.enabled
            )
        } catch (e: Throwable) {
            Dev.warn(log, "storage.save_summary_config.failed", e, "projectId" to projectId)
        }
    }

    // ── Helpers for pattern serialization ──

    /**
     * Serialize a list of patterns to JSON for SQLite storage.
     * Returns null for empty lists.
     */
    private fun serializePatternsHelper(patterns: List<String>): String? {
        if (patterns.isEmpty()) return null
        return try {
            json.encodeToString(patterns)
        } catch (_: Throwable) {
            patterns.joinToString(",")
        }
    }

    /**
     * Deserialize patterns from SQLite back to a list.
     * Handles both JSON arrays and comma-separated fallback.
     */
    private fun deserializePatternsHelper(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            json.decodeFromString<List<String>>(raw)
        } catch (_: Throwable) {
            raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        }
    }
}