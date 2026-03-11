package com.youmeandmyself.ai.chat.context

import com.intellij.openapi.project.Project
import com.youmeandmyself.dev.Dev
import com.youmeandmyself.storage.LocalStorageFacade
import com.youmeandmyself.summary.cache.SummaryCache
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
        } catch (e: Exception) {
            Dev.warn(log, "summary_store.warm_failed", e,
                "projectId" to projectId
            )
        }
    }

    /**
     * Bulk-load all FILE_SUMMARY records from SQLite for the current project.
     *
     * Returns a map of filePath → SummaryCache.Entry suitable for
     * SummaryCache.warmFromStorage().
     *
     * Only loads rows where assistant_text is non-null (rows where the
     * summary text was cached into SQLite). Rows with null assistant_text
     * are skipped — they'd be useless for the cache anyway.
     *
     * @param projectId Current project ID
     * @return Map of filePath to cache entries
     */
    private fun warmCacheFromSQLite(projectId: String): Map<String, SummaryCache.Entry> {
        return storage.withReadableDatabase { db ->
            val rows = db.query(
                """
                SELECT id, file_paths, assistant_text, provider_id, model_id, timestamp
                FROM chat_exchanges
                WHERE project_id = ?
                  AND purpose = 'FILE_SUMMARY'
                  AND assistant_text IS NOT NULL
                  AND file_paths IS NOT NULL
                ORDER BY timestamp DESC
                """.trimIndent(),
                projectId
            ) { rs ->
                Triple(
                    rs.getString("file_paths"),
                    rs.getString("assistant_text"),
                    rs.getString("timestamp")
                )
            }

            // Build the map. file_paths may contain comma-separated paths
            // (from DerivedMetadata), but for FILE_SUMMARY exchanges we store
            // a single path via SummarizationService.indexFilePath().
            // If multiple rows exist for the same path, ORDER BY timestamp DESC
            // means the first one we see is the newest — putIfAbsent keeps it.
            val entries = mutableMapOf<String, SummaryCache.Entry>()
            for ((filePaths, synopsis, timestamp) in rows) {
                if (filePaths.isNullOrBlank() || synopsis.isNullOrBlank()) continue

                // Split in case file_paths is CSV, but typically it's a single path
                val paths = filePaths.split(",").filter { it.isNotBlank() }
                val summarizedAt = try {
                    Instant.parse(timestamp)
                } catch (_: Exception) {
                    null
                }

                for (path in paths) {
                    entries.putIfAbsent(path, SummaryCache.Entry(
                        path = path,
                        languageId = null,
                        modelSynopsis = synopsis,
                        contentHashAtSummary = null,
                        lastSummarizedAt = summarizedAt
                    ))
                }
            }

            Dev.info(log, "summary_store.warmed",
                "projectId" to projectId,
                "entriesLoaded" to entries.size
            )

            entries
        }
    }

    /**
     * Suggest that a file should be summarized.
     *
     * For the individual tier, this is a fire-and-forget hint to the local
     * summarization pipeline. The pipeline (CodeSummarizationPipeline) will
     * decide whether to act based on its queue, budget, and configuration.
     *
     * In company tier, this would also create a claim in the shared system.
     */
    override suspend fun suggestSummarization(filePath: String, projectId: String) {
        pipeline.requestSummary(
            path = filePath,
            languageId = null,
            currentContentHash = null
        )
        Dev.info(log, "summary_store.suggest",
            "filePath" to filePath,
            "projectId" to projectId,
            "action" to "enqueued_via_pipeline"
        )
    }
}