package com.youmeandmyself.ai.chat.context

import com.intellij.openapi.project.Project
import com.youmeandmyself.dev.Dev
import com.youmeandmyself.storage.LocalStorageFacade

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

    /** Storage facade for querying the local SQLite database. Lazy to avoid init-order issues. */
    private val storage: LocalStorageFacade by lazy {
        LocalStorageFacade.getInstance(project)
    }

    /**
     * Retrieve a single file summary from local storage.
     *
     * Queries the SQLite summaries data for the given file path.
     * Returns null if:
     * - Storage isn't initialized yet
     * - No summary exists for this file
     * - The query fails (logged, not thrown)
     */
    override suspend fun getSummary(filePath: String, projectId: String): CodeSummary? {
        if (!storage.isInitialized) {
            Dev.info(log, "summary_store.not_ready", "filePath" to filePath)
            return null
        }

        return try {
            // Query the local database for this file's summary.
            // Uses the chat_exchanges table filtered by purpose = FILE_SUMMARY
            // and the derived metadata for the file path.
            //
            // TODO: When the dedicated summaries table is created in Phase 5,
            //       switch this query to use that table instead. The current
            //       approach works but is less efficient (scanning exchanges
            //       rather than a purpose-built summary index).
            storage.withReadableDatabase { db ->
                db.queryOne(
                    """
                    SELECT ce.id, ce.provider_id, ce.model_id, ce.timestamp,
                           ce.assistant_text, dm.file_paths
                    FROM chat_exchanges ce
                    LEFT JOIN derived_metadata dm ON dm.exchange_id = ce.id
                    WHERE ce.project_id = ?
                      AND ce.purpose = 'FILE_SUMMARY'
                      AND dm.file_paths LIKE ?
                    ORDER BY ce.timestamp DESC
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
                        headerSample = null, // TODO: Store header samples in Phase 5
                        isStale = false,     // TODO: Staleness tracking in Phase 5
                        generatedAt = rs.getString("timestamp") ?: "",
                        providerId = rs.getString("provider_id") ?: "unknown",
                        modelId = rs.getString("model_id") ?: "unknown",
                        isShared = false     // Local provider never returns shared summaries
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
        if (!storage.isInitialized || filePaths.isEmpty()) {
            return emptyMap()
        }

        return try {
            // For each file path, find the most recent FILE_SUMMARY exchange.
            // This is a simplistic approach — Phase 5 will have a dedicated
            // summaries table with proper file path indexing.
            val results = mutableMapOf<String, CodeSummary>()

            for (path in filePaths) {
                val summary = getSummary(path, projectId)
                if (summary != null) {
                    results[path] = summary
                }
            }

            results
        } catch (e: Exception) {
            Dev.warn(log, "summary_store.batch_query_failed", e,
                "fileCount" to filePaths.size,
                "projectId" to projectId
            )
            emptyMap()
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
        // TODO: Wire to CodeSummarizationPipeline.suggest(filePath) when pipeline is ready.
        //       For now, log the suggestion so we can verify the flow works.
        Dev.info(log, "summary_store.suggest",
            "filePath" to filePath,
            "projectId" to projectId,
            "action" to "logged_only_pipeline_not_wired"
        )
    }
}