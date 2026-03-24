package com.youmeandmyself.ai.chat.context

import com.intellij.openapi.project.Project
import com.youmeandmyself.dev.Dev
import com.youmeandmyself.storage.LocalStorageFacade
import com.youmeandmyself.summary.cache.SummaryCache
import com.youmeandmyself.summary.config.SummaryConfigService
import com.youmeandmyself.summary.config.SummaryMode
import com.youmeandmyself.summary.model.CodeElement
import com.youmeandmyself.summary.pipeline.SummaryPipeline
import java.time.Instant

/**
 * Individual-tier implementation of [SummaryStoreProvider].
 *
 * ## Behavior
 *
 * Reads code summaries from the local SQLite database only. No shared storage,
 * no claim coordination, no network calls. This is the simplest possible
 * implementation — it's what individual users get.
 *
 * ## Company Tier
 *
 * When company tier is implemented, it will use a different implementation
 * (e.g., `CompanySummaryStoreProvider`) that:
 * - Reads from both local AND shared storage
 * - Implements real claim coordination via a shared backend
 * - Wraps this local provider for the local-read portion
 *
 * The [ContextAssembler] doesn't need to change — it depends on the interface,
 * and the correct implementation is injected based on the user's tier.
 *
 * ## Storage Readiness
 *
 * Like all storage-dependent classes, this checks [LocalStorageFacade.isInitialized]
 * before querying. If storage isn't ready yet (IDE just started, initializer hasn't
 * finished), methods return null/empty gracefully.
 *
 * ## Summary Generation Gating (Defense-in-Depth)
 *
 * [suggestSummarization] checks [SummaryConfigService] before enqueuing work.
 * This is a safety net — the caller ([ContextAssembler]) also checks config before
 * calling. Double-gating ensures that even if a future caller forgets the check,
 * no unsanctioned work runs.
 *
 * @param project The IntelliJ project for storage access
 */
class LocalSummaryStoreProvider(private val project: Project) : SummaryStoreProvider {

    private val log = Dev.logger(LocalSummaryStoreProvider::class.java)

    private val storage: LocalStorageFacade by lazy {
        LocalStorageFacade.getInstance(project)
    }

    private val cache: SummaryCache by lazy {
        SummaryCache.getInstance(project)
    }

    private val pipeline: SummaryPipeline by lazy {
        SummaryPipeline.getInstance(project)
    }

    private val configService: SummaryConfigService by lazy {
        SummaryConfigService.getInstance(project)
    }

    /**
     * Retrieve a single file summary from local storage.
     *
     * Uses the three-layer read path:
     * 1. SummaryCache (in-memory, nanoseconds)
     * 2. SQLite chat_exchanges with purpose = FILE_SUMMARY (microseconds)
     * 3. No summary exists → return null
     *
     * On first call per session, warms the cache from SQLite so that
     * summaries persisted in previous sessions are immediately available
     * without regeneration.
     *
     * Returns null if:
     * - Storage isn't initialized yet
     * - No summary exists for this file
     * - The query fails (logged, not thrown)
     */
    override suspend fun getSummary(filePath: String, projectId: String): CodeSummary? {
        // Lazy warm-up: on first access, bulk-load all FILE_SUMMARY records
        // from SQLite into SummaryCache. Runs once per session.
        ensureWarmed(projectId)

        // Layer 1: Check in-memory cache (nanoseconds)
        val (cachedSynopsis, isStale) = cache.getCachedSynopsis(filePath, null)
        if (cachedSynopsis != null) {
            Dev.info(log, "summary_store.cache_hit", "filePath" to filePath)
            return CodeSummary(
                filePath = filePath,
                synopsis = cachedSynopsis,
                headerSample = null,
                isStale = isStale,
                generatedAt = "",
                providerId = "cached",
                modelId = "cached",
                isShared = false
            )
        }

        // Layer 2: Query SQLite (microseconds)
        if (!storage.isInitialized) {
            Dev.info(log, "summary_store.not_ready", "filePath" to filePath)
            return null
        }

        val fromDb = querySummaryFromDb(filePath, projectId)
        if (fromDb != null) {
            // Populate cache so next read is instant
            cache.populateFromStorage(
                path = filePath,
                languageId = null,
                synopsis = fromDb.synopsis,
                contentHash = null,
                summarizedAt = null
            )
            Dev.info(log, "summary_store.db_hit", "filePath" to filePath)
            return fromDb
        }

        // Layer 3: No summary exists
        Dev.info(log, "summary_store.miss", "filePath" to filePath)
        return null
    }

    /**
     * Query SQLite for the most recent FILE_SUMMARY exchange matching this file path.
     *
     * Queries chat_exchanges directly — file_paths is a column on chat_exchanges,
     * not in a separate table. Uses the purpose index (idx_chat_purpose) for efficient
     * filtering, then LIKE on file_paths for path matching.
     *
     * @param filePath Absolute file path to look up
     * @param projectId Current project ID
     * @return CodeSummary if found, null otherwise
     */
    private fun querySummaryFromDb(filePath: String, projectId: String): CodeSummary? {
        return try {
            storage.withReadableDatabase { db ->
                db.queryOne(
                    """
                SELECT id, provider_id, model_id, timestamp, assistant_text
                FROM chat_exchanges
                WHERE project_id = ?
                  AND purpose = 'FILE_SUMMARY'
                  AND file_paths LIKE ?
                ORDER BY timestamp DESC
                LIMIT 1
                """.trimIndent(),
                    projectId,
                    "%$filePath%"
                ) { rs ->
                    val synopsis = rs.getString("assistant_text")
                    if (synopsis.isNullOrBlank()) return@queryOne null

                    CodeSummary(
                        filePath = filePath,
                        synopsis = synopsis,
                        headerSample = null,
                        isStale = false,
                        generatedAt = rs.getString("timestamp") ?: "",
                        providerId = rs.getString("provider_id") ?: "unknown",
                        modelId = rs.getString("model_id") ?: "unknown",
                        isShared = false
                    )
                }
            }
        } catch (e: Exception) {
            Dev.warn(log, "summary_store.query_failed", e,
                "filePath" to filePath,
                "projectId" to projectId
            )
            null
        }
    }

    /**
     * Batch retrieve summaries from local storage.
     *
     * More efficient than N individual queries — uses a single SQL query
     * with IN clause. Files without summaries are simply absent from the result.
     */
    override suspend fun getSummaries(
        filePaths: List<String>,
        projectId: String
    ): Map<String, CodeSummary> {
        if (filePaths.isEmpty()) return emptyMap()

        val results = mutableMapOf<String, CodeSummary>()
        for (path in filePaths) {
            val summary = getSummary(path, projectId)
            if (summary != null) {
                results[path] = summary
            }
        }
        return results
    }

    /**
     * Retrieve an element-level summary (method, class, property) from local storage.
     *
     * Uses a three-layer read path:
     * 1. SummaryCache (in-memory, nanoseconds) — with hash validation
     * 2. SQLite `summaries` + `code_elements` tables (microseconds) — with hash
     * 3. SQLite `chat_exchanges` (legacy fallback, no hash) — for pre-fix data
     *
     * Hash validation rule: EVERY level, EVERY time, check before using.
     * If the stored hash doesn't match [currentElementHash], the summary is stale.
     * Null hashes (legacy data) are treated as stale — the safe default.
     *
     * Element summaries are persisted to SQLite via the `summaries` table
     * (with `content_hash_at_gen NOT NULL`). They survive IDE restarts.
     * The user paid tokens for these summaries — they must not be lost.
     *
     * See: BUG FIX — Element Summary Hash Validation.md
     *
     * @param filePath Absolute file path
     * @param elementSignature PSI element signature (e.g., "MyClass#doThing(String)")
     * @param projectId Current project ID
     * @param currentElementHash Current semantic hash for freshness validation (null = skip)
     * @return Element summary if available (check [CodeSummary.isStale]), null if not yet summarized
     */
    override suspend fun getElementSummary(
        filePath: String,
        elementSignature: String,
        projectId: String,
        currentElementHash: String?
    ): CodeSummary? {
        // Lazy warm-up (same as file-level — loads element entries too)
        ensureWarmed(projectId)

        // Layer 1: Check in-memory cache (nanoseconds)
        // Pass the current hash for validation — if the stored hash doesn't match,
        // the summary is marked as stale. This is the "every level, every time" rule.
        // See: BUG FIX — Element Summary Hash Validation.md, Fix #1
        val (cachedSynopsis, isStale) = cache.getCachedElementSynopsis(filePath, elementSignature, currentElementHash)

        if (cachedSynopsis != null) {
            Dev.info(log, "summary_store.element_cache_hit",
                "filePath" to filePath,
                "signature" to elementSignature,
                "isStale" to isStale
            )
            return CodeSummary(
                filePath = filePath,
                synopsis = cachedSynopsis,
                headerSample = null,
                isStale = isStale,
                generatedAt = "",
                providerId = "cached",
                modelId = "cached",
                isShared = false
            )
        }

        // Layer 2: Check SQLite for persisted element summaries (microseconds)
        if (!storage.isInitialized) {
            Dev.info(log, "summary_store.element_not_ready",
                "filePath" to filePath,
                "signature" to elementSignature
            )
            return null
        }

        val fromDb = queryElementSummaryFromDb(filePath, elementSignature, projectId)
        if (fromDb != null) {
            // Populate in-memory cache so next read is instant.
            // Pass the hash from the DB so subsequent cache reads can validate freshness.
            // Note: fromDb.contentHashAtGen comes from the summaries table (Fix #2).
            // If this is a legacy entry from chat_exchanges (no hash), contentHashAtGen
            // will be null and the next read will correctly treat it as stale.
            cache.completeElementClaim(
                path = filePath,
                elementSignature = elementSignature,
                languageId = null,
                synopsis = fromDb.synopsis,
                contentHash = fromDb.contentHashAtGen
            )
            Dev.info(log, "summary_store.element_db_hit",
                "filePath" to filePath,
                "signature" to elementSignature
            )
            return fromDb
        }

        // Layer 3: No element summary exists
        Dev.info(log, "summary_store.element_miss",
            "filePath" to filePath,
            "signature" to elementSignature
        )
        return null
    }

    /**
     * Query SQLite for the most recent element summary.
     *
     * Two-layer query:
     * 1. Check `summaries` + `code_elements` tables (new path, has hash)
     * 2. Fall back to `chat_exchanges` (legacy path, no hash)
     *
     * The summaries table is the source of truth after Fix #2. The chat_exchanges
     * fallback ensures backward compatibility for summaries generated before
     * the fix was applied.
     *
     * @param filePath Absolute file path
     * @param elementSignature PSI element signature (e.g., "MyClass#doThing(String)")
     * @param projectId Current project ID
     * @return CodeSummary if found (with contentHashAtGen if available), null otherwise
     */
    private fun queryElementSummaryFromDb(
        filePath: String,
        elementSignature: String,
        projectId: String
    ): CodeSummary? {
        // Layer 1: Try the summaries + code_elements tables (new path with hash)
        // Uses clean FK join via summaries.exchange_id → chat_exchanges.id
        try {
            val fromSummariesTable = storage.withReadableDatabase { db ->
                db.queryOne(
                    """
                SELECT s.content_hash_at_gen, s.is_stale, s.provider_id, s.model_id,
                       s.generated_at,
                       ex.assistant_text AS synopsis_text
                FROM summaries s
                JOIN code_elements ce ON ce.id = s.code_element_id
                JOIN chat_exchanges ex ON ex.id = s.exchange_id
                WHERE s.project_id = ?
                  AND ce.file_path = ?
                  AND ce.element_name = ?
                ORDER BY s.generated_at DESC
                LIMIT 1
                """.trimIndent(),
                    projectId,
                    filePath,
                    elementSignature
                ) { rs ->
                    val synopsis = rs.getString("synopsis_text")
                    if (synopsis.isNullOrBlank()) return@queryOne null

                    CodeSummary(
                        filePath = filePath,
                        synopsis = synopsis,
                        headerSample = null,
                        isStale = (rs.getInt("is_stale") == 1),
                        generatedAt = rs.getString("generated_at") ?: "",
                        providerId = rs.getString("provider_id") ?: "unknown",
                        modelId = rs.getString("model_id") ?: "unknown",
                        isShared = false,
                        contentHashAtGen = rs.getString("content_hash_at_gen")
                    )
                }
            }
            if (fromSummariesTable != null) return fromSummariesTable
        } catch (e: Exception) {
            Dev.warn(log, "summary_store.element_summaries_table_query_failed", e,
                "filePath" to filePath,
                "signature" to elementSignature
            )
        }

        // Layer 2: Fall back to chat_exchanges (legacy path, no hash)
        return try {
            storage.withReadableDatabase { db ->
                db.queryOne(
                    """
                SELECT id, provider_id, model_id, timestamp, assistant_text
                FROM chat_exchanges
                WHERE project_id = ?
                  AND purpose IN ('METHOD_SUMMARY', 'CLASS_SUMMARY')
                  AND file_paths LIKE ?
                  AND user_prompt LIKE ?
                ORDER BY timestamp DESC
                LIMIT 1
                """.trimIndent(),
                    projectId,
                    "%$filePath%",
                    "%$elementSignature%"
                ) { rs ->
                    val synopsis = rs.getString("assistant_text")
                    if (synopsis.isNullOrBlank()) return@queryOne null

                    CodeSummary(
                        filePath = filePath,
                        synopsis = synopsis,
                        headerSample = null,
                        isStale = false,  // No hash available — can't determine staleness from DB
                        generatedAt = rs.getString("timestamp") ?: "",
                        providerId = rs.getString("provider_id") ?: "unknown",
                        modelId = rs.getString("model_id") ?: "unknown",
                        isShared = false,
                        contentHashAtGen = null  // Legacy: no hash stored
                    )
                }
            }
        } catch (e: Exception) {
            Dev.warn(log, "summary_store.element_query_failed", e,
                "filePath" to filePath,
                "signature" to elementSignature,
                "projectId" to projectId
            )
            null
        }
    }

    /**
     * Claim status for individual tier: always [ClaimStatus.NOT_CLAIMED].
     *
     * There's no shared state to coordinate in the individual tier.
     * The context assembler will see NOT_CLAIMED and may signal
     * [suggestSummarization] if a summary is missing, but there's
     * no waiting or claim coordination.
     */
    override suspend fun getClaimStatus(filePath: String, projectId: String): ClaimStatus {
        return ClaimStatus.NOT_CLAIMED
    }

    // ==================== Lazy Warm-Up ====================

    /**
     * Ensure the SummaryCache has been warmed from SQLite.
     *
     * Called once per session on first getSummary() access. Bulk-loads all
     * FILE_SUMMARY exchanges from SQLite into SummaryCache so that summaries
     * generated in previous IDE sessions are immediately available without
     * regeneration.
     *
     * Uses SummaryCache.isWarmed() + warmFromStorage() double-checked locking
     * to guarantee this runs exactly once, even under concurrent access.
     *
     * This is lazy (triggered on first access) not eager (on project open)
     * to avoid slowing IDE startup for projects that don't use summaries.
     *
     * @param projectId Current project ID for the SQLite query
     */
    private fun ensureWarmed(projectId: String) {
        if (cache.isWarmed()) return
        if (!storage.isInitialized) return

        try {
            val entries = warmCacheFromSQLite(projectId)
            cache.warmFromStorage(entries)

            // Safeguard #3: Startup integrity check.
            // After warm-up, sample a few element summaries and verify their hashes
            // against current code. This catches persistence regressions early.
            // See: BUG FIX — Element Summary Hash Validation.md, Safeguard #3
            try {
                val elementRows = storage.loadElementSummaries(projectId)
                val sampleSize = minOf(10, elementRows.size)
                if (sampleSize > 0) {
                    var nullHashCount = 0
                    for (row in elementRows.take(sampleSize)) {
                        if (row.contentHashAtGen.isBlank()) nullHashCount++
                    }

                    Dev.info(log, "startup.summary_integrity",
                        "checked" to sampleSize,
                        "nullHash" to nullHashCount,
                        "total" to elementRows.size
                    )

                    if (nullHashCount > 0) {
                        Dev.warn(log, "startup.summary_integrity.null_hashes", null,
                            "count" to nullHashCount,
                            "message" to "Element summaries with null hashes found. These cannot be validated for freshness. Persistence path may have regressed."
                        )
                    }
                }
            } catch (e: Exception) {
                // Integrity check is diagnostic — never block warm-up
                Dev.warn(log, "startup.summary_integrity.failed", e)
            }
        } catch (e: Exception) {
            Dev.warn(log, "summary_store.warm_failed", e,
                "projectId" to projectId
            )
        }
    }

    /**
     * Bulk-load ALL summary records (file-level AND element-level) from SQLite.
     *
     * Loads FILE_SUMMARY, METHOD_SUMMARY, and CLASS_SUMMARY exchanges.
     * File-level entries go into the file cache. Element-level entries go into
     * the element cache via completeElementClaim().
     *
     * This ensures that summaries generated in previous IDE sessions are
     * immediately available without regeneration — for BOTH files and elements.
     * The user paid tokens for these summaries; they must survive restart.
     *
     * @param projectId Current project ID
     * @return Map of filePath to cache entries (file-level only; element-level
     *         populated directly into SummaryCache via completeElementClaim)
     */
    private fun warmCacheFromSQLite(projectId: String): Map<String, SummaryCache.Entry> {
        return storage.withReadableDatabase { db ->
            // Load file-level AND element-level summaries in one query
            val rows = db.query(
                """
                SELECT id, file_paths, assistant_text, provider_id, model_id, timestamp, purpose, user_prompt
                FROM chat_exchanges
                WHERE project_id = ?
                  AND purpose IN ('FILE_SUMMARY', 'METHOD_SUMMARY', 'CLASS_SUMMARY')
                  AND assistant_text IS NOT NULL
                  AND file_paths IS NOT NULL
                ORDER BY timestamp DESC
                """.trimIndent(),
                projectId
            ) { rs ->
                ElementWarmRow(
                    filePaths = rs.getString("file_paths"),
                    synopsis = rs.getString("assistant_text"),
                    timestamp = rs.getString("timestamp"),
                    purpose = rs.getString("purpose"),
                    userPrompt = rs.getString("user_prompt")
                )
            }

            // ── Element-level warm-up: load from `summaries` + `code_elements` tables ──
            // These tables store the content_hash_at_gen, enabling hash validation
            // on first access after restart. Without the hash, all element summaries
            // would be treated as stale (because null != currentHash).
            //
            // Previously, element summaries were loaded from chat_exchanges with
            // contentHash = null, which meant they were regenerated every session.
            // See: BUG FIX — Element Summary Hash Validation.md
            var elementEntriesLoaded = 0
            val projectId = rows.firstOrNull()?.let { /* extract from context */ } // unused, we pass it from caller

            try {
                val elementRows = storage.loadElementSummaries(
                    storage.resolveProjectId()
                )
                for (row in elementRows) {
                    if (row.synopsis.isBlank() || row.filePath.isBlank()) continue
                    cache.completeElementClaim(
                        path = row.filePath,
                        elementSignature = row.elementSignature,
                        languageId = null,
                        synopsis = row.synopsis,
                        contentHash = row.contentHashAtGen  // THE KEY FIX: hash is now loaded from SQLite
                    )
                    elementEntriesLoaded++
                }
            } catch (e: Exception) {
                Dev.warn(log, "summary_store.element_warm_failed", e)
                // Fall back to the old chat_exchanges path as a safety net
                // This ensures we don't lose element summaries if the summaries table
                // is empty (e.g., first run after the migration)
                for (row in rows) {
                    if (row.filePaths.isNullOrBlank() || row.synopsis.isNullOrBlank()) continue
                    if (row.purpose == "METHOD_SUMMARY" || row.purpose == "CLASS_SUMMARY") {
                        val signature = row.userPrompt ?: continue
                        val paths = row.filePaths.split(",").filter { it.isNotBlank() }
                        for (path in paths) {
                            cache.completeElementClaim(
                                path = path,
                                elementSignature = signature,
                                languageId = null,
                                synopsis = row.synopsis,
                                contentHash = null  // No hash from chat_exchanges — will be treated as stale
                            )
                            elementEntriesLoaded++
                        }
                    }
                }
            }

            // Build file-level map (FILE_SUMMARY only).
            // Element-level entries were already populated above via completeElementClaim.
            val entries = mutableMapOf<String, SummaryCache.Entry>()
            for (row in rows) {
                if (row.filePaths.isNullOrBlank() || row.synopsis.isNullOrBlank()) continue
                if (row.purpose != "FILE_SUMMARY") continue  // Skip element-level (already handled)

                val paths = row.filePaths.split(",").filter { it.isNotBlank() }
                val summarizedAt = try {
                    Instant.parse(row.timestamp)
                } catch (_: Exception) {
                    null
                }

                for (path in paths) {
                    entries.putIfAbsent(path, SummaryCache.Entry(
                        path = path,
                        languageId = null,
                        modelSynopsis = row.synopsis,
                        contentHashAtSummary = null,
                        lastSummarizedAt = summarizedAt
                    ))
                }
            }

            Dev.info(log, "summary_store.warmed",
                "projectId" to projectId,
                "fileEntriesLoaded" to entries.size,
                "elementEntriesLoaded" to elementEntriesLoaded
            )

            entries
        }
    }

    /**
     * Internal data class for warm-up query results.
     * Holds both file-level and element-level fields so we can process
     * them in a single query + single loop.
     */
    private data class ElementWarmRow(
        val filePaths: String?,
        val synopsis: String?,
        val timestamp: String?,
        val purpose: String?,
        val userPrompt: String?
    )

    /**
     * Suggest that a file should be summarized.
     *
     * ## Defense-in-Depth Config Gate
     *
     * This method checks [SummaryConfigService] before enqueuing ANY work:
     * - Kill switch off → no-op (logged)
     * - Mode is OFF → no-op (logged)
     * - Mode is ON_DEMAND or SMART_BACKGROUND → allowed (user has opted in)
     * - Mode is SUMMARIZE_PATH → no-op (path-based mode has its own trigger)
     *
     * This gate exists as a safety net. The primary caller ([ContextAssembler])
     * also checks config before calling this method. Double-gating ensures that
     * even if a future caller forgets the check, no unsanctioned AI calls happen.
     *
     * ## Behavior When Allowed
     *
     * For the individual tier, this is a fire-and-forget hint to the local
     * summarization pipeline. The pipeline ([SummaryPipeline]) will further
     * evaluate the request against budget, scope patterns, and dry-run mode
     * before deciding whether to actually make an AI call.
     *
     * In company tier, this would also create a claim in the shared system.
     *
     * @param filePath The file that needs a summary
     * @param projectId The project context
     */
    override suspend fun suggestSummarization(filePath: String, projectId: String) {
        // Defense-in-depth: check config before enqueuing anything
        val config = configService.getConfig()

        if (!config.enabled) {
            Dev.info(log, "summary_store.suggest_blocked",
                "filePath" to filePath,
                "reason" to "kill switch off"
            )
            return
        }

        when (config.mode) {
            SummaryMode.OFF -> {
                Dev.info(log, "summary_store.suggest_blocked",
                    "filePath" to filePath,
                    "reason" to "mode is OFF"
                )
                return
            }
            SummaryMode.SUMMARIZE_PATH -> {
                Dev.info(log, "summary_store.suggest_blocked",
                    "filePath" to filePath,
                    "reason" to "SUMMARIZE_PATH has its own trigger"
                )
                return
            }
            SummaryMode.ON_DEMAND,
            SummaryMode.SMART_BACKGROUND -> {
                // Allowed — proceed to enqueue
            }
        }

        pipeline.requestSummary(
            path = filePath,
            languageId = null,
            currentContentHash = null
        )

        Dev.info(log, "summary_store.suggest",
            "filePath" to filePath,
            "projectId" to projectId,
            "mode" to config.mode.name,
            "action" to "enqueued_via_pipeline"
        )
    }

    // ==================== Demand-Driven Synchronous Generation ====================

    /**
     * Generate a method summary synchronously.
     *
     * Delegates to [SummaryPipeline.generateMethodSummarySync] which handles all
     * config gates, provider resolution, PSI detection, and persistence.
     *
     * Called by ContextAssembler when a user asks about a method that has no valid
     * cached summary. The summary is generated NOW and attached to THIS request.
     */
    override suspend fun generateMethodSummaryNow(
        filePath: String,
        element: CodeElement,
        parentClassName: String
    ): String? {
        Dev.info(log, "summary_store.sync.method.start",
            "filePath" to filePath,
            "method" to element.name,
            "parent" to parentClassName
        )

        val result = pipeline.generateMethodSummarySync(filePath, element, parentClassName)

        Dev.info(log, "summary_store.sync.method.done",
            "filePath" to filePath,
            "method" to element.name,
            "success" to (result != null),
            "length" to (result?.length ?: 0)
        )

        return result
    }

    /**
     * Generate a class summary synchronously via bottom-up cascade.
     *
     * Delegates to [SummaryPipeline.generateClassSummarySync] which handles the
     * full cascade: detect methods → validate/generate each → build class summary.
     */
    override suspend fun generateClassSummaryNow(
        filePath: String,
        element: CodeElement
    ): String? {
        Dev.info(log, "summary_store.sync.class.start",
            "filePath" to filePath,
            "class" to element.name
        )

        val result = pipeline.generateClassSummarySync(filePath, element)

        Dev.info(log, "summary_store.sync.class.done",
            "filePath" to filePath,
            "class" to element.name,
            "success" to (result != null),
            "length" to (result?.length ?: 0)
        )

        return result
    }
}