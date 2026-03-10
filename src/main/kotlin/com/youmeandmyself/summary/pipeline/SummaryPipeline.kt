// File: src/main/kotlin/com/youmeandmyself/summary/pipeline/SummaryPipeline.kt
package com.youmeandmyself.summary.pipeline

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.youmeandmyself.dev.Dev
import com.youmeandmyself.summary.cache.SummaryCache
import com.youmeandmyself.summary.config.ConfigChangeListener
import com.youmeandmyself.summary.config.SummaryConfig
import com.youmeandmyself.summary.config.SummaryConfigService
import com.youmeandmyself.summary.config.SummaryMode
import com.youmeandmyself.summary.config.SummaryQueue
import com.youmeandmyself.summary.config.SummaryRequest
import com.youmeandmyself.summary.config.SummaryTrigger
import com.youmeandmyself.storage.model.ExchangePurpose
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Orchestrates summary generation decisions and delegates execution.
 *
 * ## Purpose
 *
 * This is the decision-making and orchestration layer for summarization.
 * It decides WHETHER to summarize (config checks) and WHEN (queue priority),
 * then delegates the actual execution to [SummarizationService].
 *
 * ## What This Class Does
 *
 * - Evaluates whether a file should be summarized (kill switch, mode, budget, scope, dry-run)
 * - Manages the [SummaryQueue] for ordered, deduplicated processing
 * - Processes queue items by delegating to [SummarizationService]
 * - Updates [SummaryCache] after successful generation
 * - Reacts to config changes (kill switch off → cancel all queued work)
 *
 * ## What This Class Does NOT Do
 *
 * - Cache management (that's [SummaryCache])
 * - AI provider calls (that's [SummarizationService])
 * - Prompt building (that's [SummaryExtractor] via SummarizationService)
 * - Storage persistence (that's [SummarizationService] → LocalStorageFacade)
 *
 * ## Single Execution Path
 *
 * All summarization goes through:
 * SummaryPipeline → SummarizationService → provider + JSONL + SQLite → SummaryCache update
 *
 * There is NO direct provider.summarize() call in this class.
 *
 * @param project The IntelliJ project this pipeline belongs to
 */
@Service(Service.Level.PROJECT)
class SummaryPipeline(private val project: Project) : Disposable {

    private val log = Logger.getInstance(SummaryPipeline::class.java)

    /** The summary queue — ordered, deduplicated processing. */
    val queue = SummaryQueue()

    /** Coroutine scope for background summarization jobs. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Lazy reference to config service — avoids circular init issues. */
    private val configService: SummaryConfigService by lazy {
        SummaryConfigService.getInstance(project)
    }

    /** Lazy reference to cache — avoids circular init issues. */
    private val cache: SummaryCache by lazy {
        SummaryCache.getInstance(project)
    }

    /** Lazy reference to summarization service — the single execution point. */
    private val summarizationService: SummarizationService by lazy {
        SummarizationService.getInstance(project)
    }

    init {
        // Listen for config changes — if kill switch turns off, cancel all queued work
        configService.addConfigChangeListener(ConfigChangeListener { old, new ->
            onConfigChanged(old, new)
        })
    }

    // ==================== Public API ====================

    /**
     * Get or trigger generation of an AI summary for a file.
     *
     * Returns immediately with whatever the cache has. If autoGenerate is true
     * and no summary exists, evaluates config before deciding to enqueue.
     *
     * ## Flow
     *
     * 1. Check SummaryCache for cached synopsis
     * 2. If cache hit: return it (even if stale)
     * 3. If cache miss + autoGenerate: evaluate config and potentially enqueue
     * 4. Return (null, false) if nothing available
     *
     * @param path Absolute file path
     * @param languageId Programming language (for prompt customization)
     * @param currentContentHash Current hash of file content (for staleness check)
     * @param autoGenerate If true and no summary exists, evaluate config and potentially enqueue
     * @param trigger What caused this request (default: BACKGROUND)
     * @return Pair of (summary text or null, is stale flag)
     */
    fun getOrEnqueueSynopsis(
        path: String,
        languageId: String?,
        currentContentHash: String?,
        autoGenerate: Boolean,
        trigger: SummaryTrigger = SummaryTrigger.BACKGROUND
    ): Pair<String?, Boolean> {

        Dev.info(log, "pipeline.get_or_enqueue",
            "path" to path,
            "auto" to autoGenerate,
            "trigger" to trigger.name,
            "hash" to (currentContentHash?.take(8) ?: "NONE"))

        // Check cache first
        val (synopsis, isStale) = cache.getCachedSynopsis(path, currentContentHash)

        // If we have a synopsis, return it (even if stale)
        if (synopsis != null) {
            return synopsis to isStale
        }

        // Evaluate whether we should enqueue generation
        if (autoGenerate) {
            val enqueued = evaluateAndEnqueue(path, languageId, currentContentHash, trigger)

            if (!enqueued) {
                Dev.info(log, "pipeline.skipped",
                    "path" to path,
                    "reason" to "config check failed or already queued"
                )
            }
        }

        return null to false
    }

    /**
     * Explicitly request a summary for a file (ON_DEMAND trigger).
     *
     * Bypasses the mode check (ON_DEMAND requests are always allowed if enabled).
     * Still checks kill switch, budget, and scope.
     *
     * @param path Absolute file path
     * @param languageId Programming language
     * @param currentContentHash Current hash of file content
     * @return true if enqueued, false if denied by config
     */
    fun requestSummary(
        path: String,
        languageId: String?,
        currentContentHash: String?
    ): Boolean {
        return evaluateAndEnqueue(
            path, languageId, currentContentHash,
            trigger = SummaryTrigger.USER_REQUEST
        )
    }

    /**
     * Handle file change notification from VfsSummaryWatcher.
     *
     * Updates cache staleness and optionally enqueues a refresh.
     *
     * @param path Absolute file path
     * @param newHash New content hash
     */
    fun onFileChanged(path: String, newHash: String) {
        // Update cache staleness
        cache.onHashChange(path, newHash)

        // Optionally enqueue a staleness refresh if in SMART_BACKGROUND mode
        val config = configService.getConfig()
        if (config.isActive && config.mode == SummaryMode.SMART_BACKGROUND && config.hasBudget) {
            // Only re-enqueue if we had a summary for this file before
            val (existing, _) = cache.getCachedSynopsis(path, newHash)
            if (existing != null) {
                evaluateAndEnqueue(path, null, newHash, SummaryTrigger.STALENESS_REFRESH)
            }
        }
    }

    /**
     * Handle file deletion.
     *
     * Clears cache and cancels any queued work for the file.
     */
    fun onFileDeleted(path: String) {
        queue.cancel(path)
        cache.onFileDeleted(path)
    }

    // ==================== Config-Aware Enqueue ====================

    /**
     * Evaluate config and enqueue a summary request if allowed.
     *
     * This is the central decision point. Every summarization request passes through here.
     *
     * Decision chain:
     * 1. Kill switch enabled?
     * 2. Mode allows this trigger? (USER_REQUEST bypasses mode check)
     * 3. Budget remaining?
     * 4. Scope patterns match? (include/exclude globs, min file size)
     * 5. Dry-run mode? (log what would happen, skip API call)
     *
     * @return true if enqueued (or dry-run logged), false if denied
     */
    private fun evaluateAndEnqueue(
        path: String,
        languageId: String?,
        currentContentHash: String?,
        trigger: SummaryTrigger
    ): Boolean {
        val config = configService.getConfig()

        // Kill switch
        if (!config.enabled) {
            Dev.info(log, "pipeline.denied",
                "path" to path, "reason" to "kill switch off")
            return false
        }

        // Mode check — USER_REQUEST bypasses mode (it IS on-demand)
        if (trigger != SummaryTrigger.USER_REQUEST) {
            when (config.mode) {
                SummaryMode.OFF -> {
                    Dev.info(log, "pipeline.denied",
                        "path" to path, "reason" to "mode is OFF")
                    return false
                }
                SummaryMode.ON_DEMAND -> {
                    Dev.info(log, "pipeline.denied",
                        "path" to path, "reason" to "mode is ON_DEMAND, trigger is $trigger")
                    return false
                }
                SummaryMode.SUMMARIZE_PATH -> {
                    Dev.info(log, "pipeline.denied",
                        "path" to path, "reason" to "SUMMARIZE_PATH not yet implemented")
                    return false
                }
                SummaryMode.SMART_BACKGROUND -> {
                    // Allowed — continue to scope/budget checks
                }
            }
        }

        // Count lines for scope evaluation
        val lineCount = cache.countLines(path)

        // Scope check (budget, patterns, min lines)
        val decision = configService.shouldSummarize(path, lineCount)
        if (!decision.allowed) {
            Dev.info(log, "pipeline.denied",
                "path" to path, "reason" to decision.reason)
            return false
        }

        // Dry-run check — everything above passed, but we don't make the API call
        if (config.dryRun) {
            val dryResult = configService.evaluateDryRun(path, lineCount)
            Dev.info(log, "pipeline.dryrun",
                "path" to path,
                "wouldSummarize" to dryResult.wouldSummarize,
                "estimatedTokens" to dryResult.estimatedTokens,
                "provider" to dryResult.providerInfo,
                "reason" to dryResult.reason
            )
            return true // Considered "handled" even though no API call
        }

        // Enqueue the actual request
        val priority = when (trigger) {
            SummaryTrigger.USER_REQUEST -> -10  // Highest priority
            SummaryTrigger.STALENESS_REFRESH -> 0
            SummaryTrigger.BACKGROUND -> 5
            SummaryTrigger.WARMUP -> 10  // Lowest priority
        }

        val request = SummaryRequest(
            filePath = path,
            languageId = languageId,
            contentHash = currentContentHash,
            priority = priority,
            triggeredBy = trigger
        )

        val enqueued = queue.enqueue(request)
        if (enqueued) {
            Dev.info(log, "pipeline.enqueued",
                "path" to path,
                "priority" to priority,
                "trigger" to trigger.name,
                "queueSize" to queue.size()
            )
            processNextInQueue()
        }

        return enqueued
    }

    // ==================== Queue Processing ====================

    /**
     * Process the next item in the queue.
     *
     * Launches a coroutine to handle the actual summarization.
     * Re-checks kill switch, dry-run, and budget before executing
     * (these could have changed between enqueue and processing).
     */
    private fun processNextInQueue() {
        scope.launch {
            val request = queue.poll() ?: return@launch

            // Re-check kill switch (could have changed since enqueue)
            if (!configService.isEnabled()) {
                Dev.info(log, "pipeline.process.killed",
                    "path" to request.filePath, "reason" to "kill switch toggled")
                return@launch
            }

            // Re-check dry-run
            if (configService.isDryRun()) {
                Dev.info(log, "pipeline.process.dryrun_skip",
                    "path" to request.filePath)
                return@launch
            }

            // Re-check budget
            if (configService.getRemainingBudget()?.let { it <= 0 } == true) {
                Dev.info(log, "pipeline.process.budget_exhausted",
                    "path" to request.filePath)
                return@launch
            }

            executeSummarization(request)
        }
    }

    /**
     * Execute summarization by delegating to SummarizationService.
     *
     * This is the ONLY place where summarization actually happens.
     * SummarizationService handles: prompt building, provider call,
     * JSONL persistence, SQLite persistence, token indexing.
     *
     * After success, this method updates SummaryCache so the result
     * is available for immediate reads.
     */
    private suspend fun executeSummarization(request: SummaryRequest) {
        val path = request.filePath
        val languageId = request.languageId

        try {
            // Get the summary provider
            val provider = com.youmeandmyself.ai.providers.ProviderRegistry.selectedSummaryProvider(project)
            if (provider == null) {
                Dev.warn(log, "pipeline.no_provider", null, "path" to path)
                return
            }

            // Get source text for the prompt
            val (headerSample, _) = cache.ensureHeaderSample(path, languageId, 1_500)
            val sourceText = headerSample ?: return

            if (sourceText.isBlank()) {
                Dev.warn(log, "pipeline.empty_source", null, "path" to path)
                return
            }

            // Build the prompt template
            // SummarizationService expects a template with {content} placeholder
            val promptTemplate = """
                |Summarize this ${languageId ?: "code"} code concisely in plain text.
                |No markdown formatting, no code blocks, just a clear description of what this code does.
                |Start your response with "Summary: " followed by the summary text.
                |
                |{content}
            """.trimMargin()

            Dev.info(log, "pipeline.executing",
                "path" to path,
                "provider" to provider.id,
                "sourceLength" to sourceText.length
            )

            // Delegate to SummarizationService — the single execution point
            // SummarizationService handles: provider call, JSONL, SQLite, token indexing
            val result = summarizationService.summarize(
                provider = provider,
                content = sourceText,
                promptTemplate = promptTemplate,
                purpose = ExchangePurpose.FILE_SUMMARY,
                metadata = mapOf(
                    "filePath" to path,
                    "languageId" to (languageId ?: "unknown"),
                    "trigger" to request.triggeredBy.name
                )
            )

            if (!result.isError && result.summaryText.isNotBlank()) {
                // Extract clean summary (strip marker if present)
                val cleanSummary = result.summaryText
                    .trim()
                    .let { if (it.startsWith("Summary: ", ignoreCase = true)) it.substringAfter(": ").trim() else it }

                // Update the in-memory cache
                cache.updateSynopsis(
                    path = path,
                    languageId = languageId,
                    synopsis = cleanSummary,
                    contentHash = request.contentHash
                )

                // Record token usage for budget tracking
                val tokensUsed = result.tokenUsage?.totalTokens
                    ?: ((sourceText.length + cleanSummary.length) / 4) // estimate if not available
                configService.recordTokensUsed(tokensUsed, ExchangePurpose.FILE_SUMMARY)

                Dev.info(log, "pipeline.success",
                    "path" to path,
                    "summaryLength" to cleanSummary.length,
                    "tokens" to tokensUsed
                )
            } else {
                Dev.warn(log, "pipeline.generation_failed", null,
                    "path" to path,
                    "error" to (result.errorMessage ?: "empty summary")
                )
            }

        } catch (e: Throwable) {
            Dev.warn(log, "pipeline.error", e, "path" to path)
        }
    }

    // ==================== Config Change Handler ====================

    /**
     * React to config changes.
     *
     * Key reactions:
     * - Kill switch turned off → cancel all queued work
     * - Mode changed to OFF → cancel all queued work
     */
    private fun onConfigChanged(old: SummaryConfig, new: SummaryConfig) {
        if ((!new.enabled && old.enabled) || (new.mode == SummaryMode.OFF && old.mode != SummaryMode.OFF)) {
            val cancelled = queue.cancelAll()
            if (cancelled > 0) {
                Dev.info(log, "pipeline.config_change.cancelled_queue",
                    "count" to cancelled,
                    "reason" to if (!new.enabled) "kill switch" else "mode OFF"
                )
            }
        }
    }

    // ==================== Lifecycle ====================

    override fun dispose() {
        queue.cancelAll()
        scope.cancel("Project disposed")
        Dev.info(log, "pipeline.disposed", "project" to project.name)
    }

    companion object {
        fun getInstance(project: Project): SummaryPipeline =
            project.getService(SummaryPipeline::class.java)
    }
}