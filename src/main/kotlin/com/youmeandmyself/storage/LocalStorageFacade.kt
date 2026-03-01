package com.youmeandmyself.storage

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.youmeandmyself.ai.providers.parsing.FormatDetector
import com.youmeandmyself.dev.Dev
import com.youmeandmyself.storage.model.AiExchange
import com.youmeandmyself.storage.model.ExchangeMetadata
import com.youmeandmyself.storage.model.ExchangePurpose
import com.youmeandmyself.storage.model.ExchangeRawResponse
import com.youmeandmyself.storage.model.ExchangeRequest
import com.youmeandmyself.storage.model.ExchangeTokenUsage
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
import com.youmeandmyself.storage.model.DerivedMetadata
import com.youmeandmyself.storage.model.IdeContext

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

    @Volatile
    var isInitialized: Boolean = false
        private set

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
     * Note: Token columns (prompt_tokens, completion_tokens, total_tokens) are
     * initially NULL in the SQLite row. They get populated by [updateTokenUsage]
     * after the response is parsed. This is the "save then index" pattern —
     * raw data is safe immediately, tokens are indexed as a second step.
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
                    // Token columns are NULL here — filled by updateTokenUsage() after parsing
                    database.execute(
                        """
                        INSERT INTO chat_exchanges 
                            (id, project_id, provider_id, model_id, conversation_id, purpose, timestamp,
                             prompt_tokens, completion_tokens, total_tokens, user_prompt, 
                             raw_file, raw_available)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1)
                        """.trimIndent(),
                        exchange.id,
                        projectId,
                        exchange.providerId,
                        exchange.modelId,
                        exchange.conversationId,
                        exchange.purpose.name,
                        exchange.timestamp.toString(),
                        exchange.tokenUsage?.promptTokens,
                        exchange.tokenUsage?.completionTokens,
                        exchange.tokenUsage?.effectiveTotal,
                        exchange.request.input,
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
     * Update token usage for an existing exchange.
     *
     * Called after ResponseParser extracts token data from the raw response.
     * The exchange row already exists (created by [saveExchange] with null tokens),
     * this just fills in the token columns.
     *
     * This is the "index" step in the save-then-index pattern:
     * 1. [saveExchange] → raw JSONL safe, SQLite row with null tokens
     * 2. ResponseParser.parse() → extracts token counts from raw JSON
     * 3. [updateTokenUsage] → fills in the SQLite token columns
     *
     * If this fails, no data is lost — tokens are still in the raw JSONL
     * and can be backfilled later during a database rebuild.
     *
     * @param exchangeId The exchange to update (must already exist)
     * @param tokenUsage The extracted token breakdown
     */
    suspend fun updateTokenUsage(exchangeId: String, tokenUsage: ExchangeTokenUsage) {
        if (mode == StorageMode.OFF) return

        withContext(Dispatchers.IO) {
            writeMutex.withLock {
                try {
                    val database = requireDb()
                    database.execute(
                        """
                        UPDATE chat_exchanges 
                        SET prompt_tokens = ?, completion_tokens = ?, total_tokens = ?
                        WHERE id = ?
                        """.trimIndent(),
                        tokenUsage.promptTokens,
                        tokenUsage.completionTokens,
                        tokenUsage.effectiveTotal,
                        exchangeId
                    )

                    Dev.info(log, "tokens.indexed",
                        "exchangeId" to exchangeId,
                        "prompt" to tokenUsage.promptTokens,
                        "completion" to tokenUsage.completionTokens,
                        "total" to tokenUsage.effectiveTotal
                    )
                } catch (e: Exception) {
                    // Non-fatal: tokens are still in raw JSONL, can be backfilled
                    Dev.warn(log, "tokens.index_failed", e,
                        "exchangeId" to exchangeId
                    )
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
                data class ExchangeLocation(val rawFile: String, val rawAvailable: Boolean, val purpose: ExchangePurpose)

                val row = database.queryOne(
                    "SELECT raw_file, raw_available, purpose FROM chat_exchanges WHERE id = ? AND project_id = ?",
                    id, projectId
                ) { rs ->
                    ExchangeLocation(
                        rs.getString("raw_file"),
                        rs.getInt("raw_available") == 1,
                        ExchangePurpose.valueOf(rs.getString("purpose"))
                    )
                }

                if (row == null) {
                    Dev.info(log, "get.notfound", "id" to id, "reason" to "no_metadata")
                    return@withContext null
                }

                val (rawFile, rawAvailable, purpose) = row

                if (!rawAvailable) {
                    Dev.info(log, "get.notfound", "id" to id, "reason" to "raw_unavailable")
                    return@withContext null
                }

                // Step 2: Read from the JSONL file
                val exchange = readRawExchange(id, projectId, rawFile, config, purpose)

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
                           prompt_tokens, completion_tokens, total_tokens,
                           raw_file, raw_available, flags, labels
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
                        tokenUsage = ExchangeTokenUsage(
                            promptTokens = rs.getInt("prompt_tokens").takeIf { !rs.wasNull() },
                            completionTokens = rs.getInt("completion_tokens").takeIf { !rs.wasNull() },
                            totalTokens = rs.getInt("total_tokens").takeIf { !rs.wasNull() }
                        ).takeIf { it.promptTokens != null || it.completionTokens != null || it.totalTokens != null },
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
                    data class RawFileRef(val filename: String, val purpose: ExchangePurpose)

                    val rawFileRefs = database.query(
                        """
                            SELECT raw_file, purpose FROM chat_exchanges
                            WHERE project_id = ? AND raw_available = 1
                            GROUP BY raw_file, purpose
                            """.trimIndent(),
                        projectId
                    ) { rs -> RawFileRef(rs.getString("raw_file"), ExchangePurpose.valueOf(rs.getString("purpose"))) }

                    var markedUnavailable = 0

                    for (ref in rawFileRefs) {
                        val file = config.chatFile(projectId, ref.filename, ref.purpose)
                        if (!file.exists()) {
                            val affected = database.execute(
                                """
                                    UPDATE chat_exchanges
                                    SET raw_available = 0
                                    WHERE project_id = ? AND raw_file = ? AND raw_available = 1
                                    """.trimIndent(),
                                projectId, ref.filename
                            )
                            markedUnavailable += affected

                            Dev.info(log, "validate.marked_unavailable",
                                "rawFile" to ref.filename,
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

                // Step 3: Rebuild SQLite index from JSONL files on disk.
                // This is the "JSONL is truth" promise: if the DB was wiped
                // (dev mode, corruption, migration), we reconstruct everything
                // we can from the raw files. Fast no-op if DB already has data.
                val rebuildService = JsonlRebuildService(database)
                val stats = rebuildService.rebuildFromDirectory(config.root)
                if (stats.imported > 0) {
                    Dev.info(log, "rebuild.imported",
                        "imported" to stats.imported,
                        "files" to stats.filesScanned
                    )
                }

                // Step 4: Register this project
                val projectId = resolveProjectId()
                ensureProjectRegistered(projectId, database)

                // Step 5: Rebuild search index from available exchanges
                rebuildSearchIndex(projectId, config, database)

                // Step 6: Self-heal misrouted files from pre-Phase 4B bug
                val healingService = StorageHealingService(database)
                val healed = healingService.healMisroutedFiles(projectId, config)
                if (healed > 0) {
                    Dev.info(log, "init.healed", "files" to healed)
                }

                Dev.info(log, "init.complete",
                    "project" to project.name,
                    "storageRoot" to config.root.absolutePath,
                    "indexedCount" to searchEngine.indexSize()
                )

                isInitialized = true
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
        val file = config.currentFileForPurpose(projectId, exchange.purpose)
        val filename = file.name

        // Ensure project directory exists (first write for this project)
        file.parentFile?.mkdirs()

        // Serialize to compact JSON and append as a single line.
        // Include projectId in the record for database rebuild capability.
        val serializable = exchange.toJsonl(projectId, project.name)
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
        config: StorageConfig,
        purpose: ExchangePurpose
    ): AiExchange? {
        val file = config.chatFile(projectId, filename, purpose)
        if (!file.exists()) {
            Dev.info(log, "raw.read.nofile", "file" to filename)
            return null
        }

        file.useLines { lines ->
            for (line in lines) {
                if (line.isBlank()) continue

                try {
                    val record = json.decodeFromString<JsonlExchange>(line)
                    if (record.id == id) {
                        return record.toExchange()
                    }
                } catch (e: Exception) {
                    Dev.warn(log, "raw.read.parse_error", e,
                        "file" to filename,
                        "linePreview" to Dev.preview(line, 100)
                    )
                }
            }
        }

        return null
    }

    // ==================== Token Backfill ====================

    /**
     * Attempt to extract token usage from a raw response JSON string.
     *
     * Used during database rebuild to backfill token data for exchanges
     * that were saved before Phase 4 (or where the update step failed).
     * Re-parses the raw JSON through FormatDetector, which knows how to
     * find token usage in OpenAI, Gemini, and Anthropic response formats.
     *
     * @param rawJson The raw provider response JSON
     * @return Extracted token usage, or null if not available
     */
    private fun backfillTokenUsage(rawJson: String?): ExchangeTokenUsage? {
        if (rawJson.isNullOrBlank()) return null
        return try {
            val detection = FormatDetector.detect(rawJson)
            detection.tokenUsage?.let { usage ->
                ExchangeTokenUsage(
                    promptTokens = usage.promptTokens,
                    completionTokens = usage.completionTokens,
                    totalTokens = usage.totalTokens
                )
            }
        } catch (e: Exception) {
            Dev.warn(log, "tokens.backfill_failed", e)
            null
        }
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
     *
     * ## Backward Compatibility
     *
     * Pre-Phase 4 records may have:
     * - `tokensUsed: Int` (old single-integer format)
     * - No `tokenUsage` block
     *
     * The [toExchange] method handles both cases:
     * - If `tokenUsage` is present → use the breakdown directly
     * - If only `tokensUsed` is present → map it to `totalTokens`
     * - If neither → tokenUsage will be null (can be backfilled from rawResponse.json)
     */
    @Serializable
    data class JsonlExchange(
        val id: String,
        val projectId: String? = null,
        val projectName: String? = null,
        val timestamp: String,
        val providerId: String,
        val modelId: String,
        val conversationId: String?,
        val purpose: String,
        val request: JsonlRequest,
        val rawResponse: JsonlRawResponse,
        // Phase 4: structured token breakdown
        val tokenUsage: JsonlTokenUsage? = null,
        // Legacy field — kept for backward compatibility with pre-Phase 4 JSONL records.
        // New records write to tokenUsage instead. During deserialization, if tokenUsage
        // is null but tokensUsed is present, we map tokensUsed → totalTokens.
        val tokensUsed: Int? = null
    ) {
        /**
         * Convert JSONL record back to domain model.
         *
         * Handles backward compatibility:
         * - New records: tokenUsage block present → use directly
         * - Legacy records: only tokensUsed Int → treat as totalTokens
         * - Very old records: neither present → null (backfillable from rawResponse.json)
         */
        fun toExchange(): AiExchange = AiExchange(
            id = id,
            timestamp = Instant.parse(timestamp),
            providerId = providerId,
            modelId = modelId,
            purpose = ExchangePurpose.valueOf(purpose),
            request = request.toRequest(),
            rawResponse = rawResponse.toRawResponse(),
            tokenUsage = tokenUsage?.toDomain()
                ?: tokensUsed?.let { ExchangeTokenUsage(totalTokens = it) }
        )
    }

    /**
     * Token usage breakdown for JSONL serialization.
     *
     * Mirrors [ExchangeTokenUsage] but is @Serializable for kotlinx.serialization.
     */
    @Serializable
    data class JsonlTokenUsage(
        val promptTokens: Int? = null,
        val completionTokens: Int? = null,
        val totalTokens: Int? = null
    ) {
        fun toDomain(): ExchangeTokenUsage = ExchangeTokenUsage(
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            totalTokens = totalTokens
        )

        companion object {
            fun fromDomain(usage: ExchangeTokenUsage): JsonlTokenUsage = JsonlTokenUsage(
                promptTokens = usage.promptTokens,
                completionTokens = usage.completionTokens,
                totalTokens = usage.totalTokens
            )
        }
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
    private fun AiExchange.toJsonl(projectId: String? = null, projectName: String? = null): JsonlExchange = JsonlExchange(
        id = id,
        projectId = projectId,
        projectName = projectName,
        timestamp = timestamp.toString(),
        providerId = providerId,
        modelId = modelId,
        conversationId = conversationId,
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
        tokenUsage = tokenUsage?.let { JsonlTokenUsage.fromDomain(it) },
        tokensUsed = null  // Legacy field — not written by new code
    )

    // ==================== Database Access for Services ====================

    /**
     * Provides writable database access for services (SearchService, BookmarkService).
     * Replaces reflection hacks — this is the clean way for services to run SQL.
     */
    fun <T> withDatabase(block: (DatabaseHelper) -> T): T {
        val database = db ?: throw IllegalStateException("Storage not initialized yet")
        return block(database)
    }

    /**
     * Read-only database access for query operations.
     */
    fun <T> withReadableDatabase(block: (DatabaseHelper) -> T): T {
        val database = db ?: throw IllegalStateException("Storage not initialized yet")
        return block(database)
    }

    // ==================== Library Support ====================

    /**
     * Stats for the Library tab footer and sidebar counts.
     */
    data class LibraryStats(
        val totalExchanges: Int,
        val totalBookmarks: Int,
        val totalWithCode: Int,
        val totalTokens: Long
    )

    fun getLibraryStats(): LibraryStats {
        if (mode == StorageMode.OFF) return LibraryStats(0, 0, 0, 0)

        return try {
            val database = db ?: return LibraryStats(0, 0, 0, 0)
            val projectId = resolveProjectId()

            val total = database.queryOne(
                "SELECT COUNT(*) as cnt FROM chat_exchanges WHERE project_id = ?", projectId
            ) { rs -> rs.getInt("cnt") } ?: 0

            val bookmarked = database.queryOne(
                "SELECT COUNT(*) as cnt FROM chat_exchanges WHERE project_id = ? AND flags LIKE '%BOOKMARKED%'", projectId
            ) { rs -> rs.getInt("cnt") } ?: 0

            val withCode = database.queryOne(
                "SELECT COUNT(*) as cnt FROM chat_exchanges WHERE project_id = ? AND has_code_block = 1", projectId
            ) { rs -> rs.getInt("cnt") } ?: 0

            val totalTokens = database.queryOne(
                "SELECT COALESCE(SUM(total_tokens), 0) as total FROM chat_exchanges WHERE project_id = ? AND total_tokens IS NOT NULL", projectId
            ) { rs -> rs.getLong("total") } ?: 0L

            LibraryStats(total, bookmarked, withCode, totalTokens)
        } catch (e: Exception) {
            Dev.error(log, "library_stats.failed", e)
            LibraryStats(0, 0, 0, 0)
        }
    }

    /**
     * Full exchange detail for the Library detail panel.
     */
    data class FullExchange(
        val id: String,
        val profileName: String?,
        val userPrompt: String?,
        val assistantText: String?,
        val timestamp: Instant,
        val tokensUsed: Int?,
        val hasCode: Boolean,
        val languages: List<String>,
        val topics: List<String>,
        val filePaths: List<String>,
        val ideContextFile: String?,
        val ideContextBranch: String?,
        val openFiles: List<String>
    )

    fun getFullExchange(exchangeId: String): FullExchange? {
        if (mode == StorageMode.OFF) return null

        return try {
            val database = db ?: return null
            database.queryOne(
                """
                SELECT id, model_id, assistant_text, user_prompt, timestamp, total_tokens,
                       has_code_block, code_languages, detected_topics, file_paths,
                       context_file, context_branch, open_files
                FROM chat_exchanges WHERE id = ?
                """.trimIndent(),
                exchangeId
            ) { rs ->
                FullExchange(
                    id = rs.getString("id"),
                    profileName = rs.getString("model_id"),
                    userPrompt = rs.getString("user_prompt"),
                    assistantText = rs.getString("assistant_text"),
                    timestamp = Instant.parse(rs.getString("timestamp")),
                    tokensUsed = rs.getInt("total_tokens").takeIf { !rs.wasNull() },
                    hasCode = rs.getInt("has_code_block") == 1,
                    languages = rs.getString("code_languages")?.split(",")?.filter { it.isNotBlank() } ?: emptyList(),
                    topics = rs.getString("detected_topics")?.split(",")?.filter { it.isNotBlank() } ?: emptyList(),
                    filePaths = rs.getString("file_paths")?.split(",")?.filter { it.isNotBlank() } ?: emptyList(),
                    ideContextFile = rs.getString("context_file"),
                    ideContextBranch = rs.getString("context_branch"),
                    openFiles = rs.getString("open_files")?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
                )
            }
        } catch (e: Exception) {
            Dev.error(log, "full_exchange.failed", e, "exchangeId" to exchangeId)
            null
        }
    }

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

    // ==================== Assistant Text ====================

    /**
     * Cache parsed assistant text in SQLite.
     *
     * Called after ResponseParser extracts the response content.
     * This avoids re-parsing the raw JSON every time the Library
     * needs to display a response preview or run a text search.
     *
     * @param exchangeId The exchange to update
     * @param assistantText The parsed response text
     */
    suspend fun cacheAssistantText(exchangeId: String, assistantText: String) {
        if (mode == StorageMode.OFF) return

        withContext(Dispatchers.IO) {
            writeMutex.withLock {
                try {
                    val database = requireDb()
                    database.execute(
                        "UPDATE chat_exchanges SET assistant_text = ? WHERE id = ?",
                        assistantText, exchangeId
                    )
                    Dev.info(log, "assistant_text.cached",
                        "exchangeId" to exchangeId,
                        "length" to assistantText.length
                    )
                } catch (e: Exception) {
                    Dev.warn(log, "assistant_text.cache_failed", e,
                        "exchangeId" to exchangeId
                    )
                }
            }
        }
    }

    /**
     * Get assistant text for an exchange, with lazy loading from JSONL.
     *
     * Flow:
     * 1. Check SQLite (fast path — already cached)
     * 2. If NULL → read raw JSON from JSONL → re-parse → cache in SQLite
     * 3. If JSONL also fails → return null
     *
     * After the first access, subsequent reads are instant from SQLite.
     *
     * @param exchangeId The exchange ID
     * @param projectId The project ID (needed for JSONL file lookup)
     * @return The assistant text, or null if not available
     */
    suspend fun getAssistantText(exchangeId: String, projectId: String): String? {
        if (mode == StorageMode.OFF) return null

        return withContext(Dispatchers.IO) {
            try {
                val database = requireDb()

                // Step 1: Check SQLite cache
                val cached = database.queryOne(
                    "SELECT assistant_text FROM chat_exchanges WHERE id = ?",
                    exchangeId
                ) { rs -> rs.getString("assistant_text") }

                if (cached != null) return@withContext cached

                // Step 2: Lazy load from JSONL
                Dev.info(log, "assistant_text.lazy_load", "exchangeId" to exchangeId)

                val exchange = getExchange(exchangeId, projectId) ?: return@withContext null
                val rawJson = exchange.rawResponse.json
                if (rawJson.isBlank()) return@withContext null

                // Re-parse through ResponseParser to extract content
                val parsed = com.youmeandmyself.ai.providers.parsing.ResponseParser.parse(
                    rawJson, exchange.rawResponse.httpStatus, exchangeId
                )
                val assistantText = parsed.rawText

                // Step 3: Cache for next time
                if (!assistantText.isNullOrBlank()) {
                    writeMutex.withLock {
                        database.execute(
                            "UPDATE chat_exchanges SET assistant_text = ? WHERE id = ?",
                            assistantText, exchangeId
                        )
                    }
                    Dev.info(log, "assistant_text.lazy_cached",
                        "exchangeId" to exchangeId,
                        "length" to assistantText.length
                    )
                }

                assistantText
            } catch (e: Exception) {
                Dev.warn(log, "assistant_text.get_failed", e, "exchangeId" to exchangeId)
                null
            }
        }
    }

// ==================== Derived Metadata ====================

    /**
     * Store derived metadata for an exchange.
     *
     * Called after assistant text is extracted, during the save pipeline.
     * Also called during database rebuild to backfill from JSONL.
     *
     * @param exchangeId The exchange to update
     * @param derived The extracted metadata
     */
    suspend fun updateDerivedMetadata(exchangeId: String, derived: DerivedMetadata) {
        if (mode == StorageMode.OFF) return

        withContext(Dispatchers.IO) {
            writeMutex.withLock {
                try {
                    val database = requireDb()
                    database.execute(
                        """
                    UPDATE chat_exchanges SET
                        has_code_block = ?,
                        code_languages = ?,
                        has_command = ?,
                        has_stacktrace = ?,
                        detected_topics = ?,
                        file_paths = ?,
                        duplicate_hash = ?
                    WHERE id = ?
                    """.trimIndent(),
                        if (derived.hasCodeBlock) 1 else 0,
                        derived.codeLanguages.joinToString(",").ifBlank { null },
                        if (derived.hasCommand) 1 else 0,
                        if (derived.hasStacktrace) 1 else 0,
                        derived.detectedTopics.joinToString(",").ifBlank { null },
                        derived.filePaths.joinToString(",").ifBlank { null },
                        derived.duplicateHash,
                        exchangeId
                    )
                    Dev.info(log, "derived.stored",
                        "exchangeId" to exchangeId,
                        "hasCode" to derived.hasCodeBlock,
                        "languages" to derived.codeLanguages,
                        "topics" to derived.detectedTopics,
                        "duplicateHash" to derived.duplicateHash?.take(16)
                    )
                } catch (e: Exception) {
                    Dev.warn(log, "derived.store_failed", e, "exchangeId" to exchangeId)
                }
            }
        }
    }

// ==================== IDE Context ====================

    /**
     * Store IDE context for an exchange.
     *
     * Called at chat-send time with the captured IDE state.
     *
     * @param exchangeId The exchange to update
     * @param context The captured IDE context
     */
    suspend fun updateIdeContext(exchangeId: String, context: IdeContext) {
        if (mode == StorageMode.OFF || context.isEmpty) return

        withContext(Dispatchers.IO) {
            writeMutex.withLock {
                try {
                    val database = requireDb()
                    // Store open files as comma-separated paths (same pattern as code_languages)
                    val openFilesStr = context.openFiles?.joinToString(",")
                    database.execute(
                        """
                    UPDATE chat_exchanges SET
                        context_file = ?,
                        context_language = ?,
                        context_module = ?,
                        context_branch = ?,
                        open_files = ?
                    WHERE id = ?
                    """.trimIndent(),
                        context.activeFile,
                        context.language,
                        context.module,
                        context.branch,
                        openFilesStr,
                        exchangeId
                    )
                    Dev.info(log, "context.stored",
                        "exchangeId" to exchangeId,
                        "file" to context.activeFile,
                        "openFiles" to (context.openFiles?.size ?: 0),
                        "branch" to context.branch
                    )
                } catch (e: Exception) {
                    Dev.warn(log, "context.store_failed", e, "exchangeId" to exchangeId)
                }
            }
        }
    }

// ==================== Duplicate Detection ====================

    /**
     * Find exchanges with the same prompt hash.
     *
     * Used for "you asked this before" feature.
     *
     * @param duplicateHash SHA-256 hash of the normalized prompt
     * @param excludeId Exchange ID to exclude (the current one)
     * @return List of matching exchange metadata, newest first
     */
    suspend fun findDuplicatePrompts(duplicateHash: String, excludeId: String? = null): List<ExchangeMetadata> {
        if (mode == StorageMode.OFF) return emptyList()

        return withContext(Dispatchers.IO) {
            try {
                val database = requireDb()
                val sql = if (excludeId != null) {
                    "SELECT * FROM chat_exchanges WHERE duplicate_hash = ? AND id != ? ORDER BY timestamp DESC LIMIT 10"
                } else {
                    "SELECT * FROM chat_exchanges WHERE duplicate_hash = ? ORDER BY timestamp DESC LIMIT 10"
                }
                val params = if (excludeId != null) arrayOf<Any?>(duplicateHash, excludeId) else arrayOf<Any?>(duplicateHash)

                database.query(sql, *params) { rs ->
                    ExchangeMetadata(
                        id = rs.getString("id"),
                        projectId = rs.getString("project_id"),
                        timestamp = Instant.parse(rs.getString("timestamp")),
                        providerId = rs.getString("provider_id"),
                        modelId = rs.getString("model_id"),
                        purpose = ExchangePurpose.valueOf(rs.getString("purpose")),
                        tokenUsage = ExchangeTokenUsage(
                            promptTokens = rs.getInt("prompt_tokens").takeIf { !rs.wasNull() },
                            completionTokens = rs.getInt("completion_tokens").takeIf { !rs.wasNull() },
                            totalTokens = rs.getInt("total_tokens").takeIf { !rs.wasNull() }
                        ).takeIf { it.promptTokens != null || it.completionTokens != null || it.totalTokens != null },
                        flags = ExchangeMetadata.decodeSet(rs.getString("flags")),
                        labels = ExchangeMetadata.decodeSet(rs.getString("labels")),
                        rawFile = rs.getString("raw_file"),
                        rawDataAvailable = rs.getInt("raw_available") == 1
                    )
                }
            } catch (e: Exception) {
                Dev.error(log, "duplicates.find_failed", e, "hash" to duplicateHash)
                emptyList()
            }
        }
    }

// ==================== Updated Rebuild ====================

    /**
     * REPLACE the existing rebuildSearchIndex() with this version.
     *
     * Changes from previous:
     * - Populates assistant_text cache during rebuild
     * - Extracts and stores derived metadata during rebuild
     * - Backfills token usage (unchanged from Phase 4)
     */
    private suspend fun rebuildSearchIndex(projectId: String, config: StorageConfig, database: DatabaseHelper) {
        data class RebuildFileRef(val filename: String, val purpose: ExchangePurpose)
        val rawFileRefs = database.query(
            """
                    SELECT raw_file, purpose FROM chat_exchanges
                    WHERE project_id = ? AND raw_available = 1
                    GROUP BY raw_file, purpose
                    """.trimIndent(),
            projectId
        ) { rs -> RebuildFileRef(rs.getString("raw_file"), ExchangePurpose.valueOf(rs.getString("purpose"))) }

        val exchanges = mutableListOf<AiExchange>()
        for (ref in rawFileRefs) {
            val file = config.chatFile(projectId, ref.filename, ref.purpose)
            if (!file.exists()) continue

            file.useLines { lines ->
                for (line in lines) {
                    if (line.isBlank()) continue
                    try {
                        val record = json.decodeFromString<JsonlExchange>(line)
                        val exchange = record.toExchange()

                        // Backfill token usage if missing
                        val finalExchange = if (exchange.tokenUsage == null) {
                            val backfilled = backfillTokenUsage(exchange.rawResponse.json)
                            if (backfilled != null) {
                                exchange.copy(tokenUsage = backfilled)
                            } else {
                                exchange
                            }
                        } else {
                            exchange
                        }

                        // Cache assistant text if not yet in SQLite
                        val existingText = database.queryOne(
                            "SELECT assistant_text FROM chat_exchanges WHERE id = ?",
                            exchange.id
                        ) { rs -> rs.getString("assistant_text") }

                        if (existingText == null) {
                            val parsed = com.youmeandmyself.ai.providers.parsing.ResponseParser.parse(
                                exchange.rawResponse.json, exchange.rawResponse.httpStatus, exchange.id
                            )
                            val assistantText = parsed.rawText
                            if (!assistantText.isNullOrBlank()) {
                                database.execute(
                                    "UPDATE chat_exchanges SET assistant_text = ? WHERE id = ?",
                                    assistantText, exchange.id
                                )

                                // Also extract and store derived metadata
                                val derived = DerivedMetadata.extract(assistantText, exchange.request.input)
                                database.execute(
                                    """
                                UPDATE chat_exchanges SET
                                    has_code_block = ?, code_languages = ?, has_command = ?,
                                    has_stacktrace = ?, detected_topics = ?, file_paths = ?,
                                    duplicate_hash = ?
                                WHERE id = ?
                                """.trimIndent(),
                                    if (derived.hasCodeBlock) 1 else 0,
                                    derived.codeLanguages.joinToString(",").ifBlank { null },
                                    if (derived.hasCommand) 1 else 0,
                                    if (derived.hasStacktrace) 1 else 0,
                                    derived.detectedTopics.joinToString(",").ifBlank { null },
                                    derived.filePaths.joinToString(",").ifBlank { null },
                                    derived.duplicateHash,
                                    exchange.id
                                )
                            }
                        }

                        exchanges.add(finalExchange)
                    } catch (e: Exception) {
                        Dev.warn(log, "index.rebuild.parse_error", e,
                            "file" to ref.filename,
                            "linePreview" to Dev.preview(line, 100)
                        )
                    }
                }
            }
        }

        searchEngine.rebuildIndex(exchanges)
    }

    /** Expose storage config for dev/test commands that need direct path access. */
    fun getStorageConfig(): StorageConfig = requireConfig()
}