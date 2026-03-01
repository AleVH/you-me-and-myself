// File: src/main/kotlin/com/youmeandmyself/context/orchestrator/SummaryStore.kt
package com.youmeandmyself.context.orchestrator

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.youmeandmyself.ai.providers.ProviderRegistry
import com.youmeandmyself.context.orchestrator.config.ConfigChangeListener
import com.youmeandmyself.context.orchestrator.config.SummaryConfig
import com.youmeandmyself.context.orchestrator.config.SummaryConfigService
import com.youmeandmyself.context.orchestrator.config.SummaryMode
import com.youmeandmyself.context.orchestrator.config.SummaryQueue
import com.youmeandmyself.context.orchestrator.config.SummaryRequest
import com.youmeandmyself.context.orchestrator.config.SummaryTrigger
import com.youmeandmyself.dev.Dev
import com.youmeandmyself.storage.model.ExchangePurpose
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

/**
 * Project-level cache and orchestrator for code summaries.
 *
 * ## Purpose
 *
 * This store manages summaries of source files for the context system. It:
 * - Caches summaries in memory for fast access
 * - Tracks staleness using content hashes
 * - Orchestrates background summarization jobs
 * - Provides header samples as a lightweight fallback
 *
 * ## Phase 3 Changes
 *
 * Now wired to [SummaryConfigService] for all decision-making:
 * - Checks mode, kill switch, budget, and scope before enqueuing work
 * - Supports dry-run mode (evaluates everything, skips API call)
 * - Uses [SummaryQueue] instead of raw pendingSynopsis Set
 * - Records token usage after successful summaries
 * - Listens to config changes (e.g., kill switch → cancel queue)
 *
 * ## How It Works
 *
 * When code needs a summary for a file (e.g., for context injection):
 * 1. Check if we have a cached summary
 * 2. If cached and fresh (hash matches), return it
 * 3. If stale or missing, check config (mode, budget, scope, dry-run)
 * 4. If allowed and not dry-run, enqueue via SummaryQueue
 * 5. Return whatever we have (stale summary > nothing)
 *
 * ## Header Samples vs Model Synopses
 *
 * - **Header Sample**: First N characters of a file. Cheap, instant, no AI call.
 * - **Model Synopsis**: AI-generated summary. Expensive, async, much better quality.
 *
 * ## Staleness
 *
 * A summary is "stale" when the file's content hash has changed since summarization.
 * Stale summaries are still returned (better than nothing) but marked for refresh.
 *
 * @param project The IntelliJ project this store belongs to
 */
@Service(Service.Level.PROJECT)
class SummaryStore(private val project: Project) : Disposable {

    private val log = Logger.getInstance(SummaryStore::class.java)

    /**
     * A cached entry for a file's summary data.
     */
    data class Entry(
        val path: String,
        val languageId: String?,
        val summaryVersion: Int = 1,
        val contentHashAtSummary: String? = null,
        val lastSummarizedAt: Instant? = null,
        val headerSample: String? = null,
        val modelSynopsis: String? = null
    )

    /** In-memory cache of summaries, keyed by file path. */
    private val entries = ConcurrentHashMap<String, Entry>()

    /** The summary queue — replaces the old pendingSynopsis Set. */
    val queue = SummaryQueue()

    /** Coroutine scope for background summarization jobs. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Lazy reference to config service — avoids circular init issues. */
    private val configService: SummaryConfigService by lazy {
        SummaryConfigService.getInstance(project)
    }

    init {
        // Listen for config changes — if kill switch turns off, cancel all queued work
        configService.addConfigChangeListener(ConfigChangeListener { old, new ->
            onConfigChanged(old, new)
        })
    }

    // ==================== Public API ====================

    /**
     * Ensure a header sample exists for a file.
     *
     * Header samples are the first N characters of a file — a cheap, instant
     * fallback when we don't have an AI summary. They're computed once and cached.
     * No config check needed — header samples are free (no API call).
     *
     * @param path Absolute file path
     * @param languageId Programming language identifier
     * @param maxChars Maximum characters to sample
     * @return Pair of (header sample or null, character count)
     */
    fun ensureHeaderSample(
        path: String,
        languageId: String?,
        maxChars: Int
    ): Pair<String?, Int> {
        val current = entries[path]
        if (current?.headerSample != null) {
            return current.headerSample to current.headerSample.length
        }

        val sample = computeHeaderSample(path, maxChars)
        val updated = (current ?: Entry(path, languageId)).copy(headerSample = sample)
        entries[path] = updated

        return sample to (sample?.length ?: 0)
    }

    /**
     * Get or trigger generation of an AI summary for a file.
     *
     * Returns immediately with whatever we have cached. If autoGenerate is true
     * and we don't have a summary, evaluates config before deciding to enqueue.
     *
     * ## Config Integration (Phase 3)
     *
     * Before enqueuing, checks:
     * 1. Kill switch (enabled?)
     * 2. Mode (OFF/ON_DEMAND/SMART_BACKGROUND)
     * 3. Budget (tokens remaining?)
     * 4. Scope (include/exclude patterns, min lines)
     * 5. Dry-run (plan only, skip API call)
     *
     * @param path Absolute file path
     * @param languageId Programming language (for prompt customization)
     * @param currentContentHash Current hash of file content (for staleness check)
     * @param maxTokens Max tokens for the summary response
     * @param autoGenerate If true and no summary exists, evaluate config and potentially enqueue
     * @param trigger What caused this request (default: BACKGROUND)
     * @return Pair of (summary text or null, is stale flag)
     */
    fun getOrEnqueueSynopsis(
        path: String,
        languageId: String?,
        currentContentHash: String?,
        maxTokens: Int,
        autoGenerate: Boolean,
        trigger: SummaryTrigger = SummaryTrigger.BACKGROUND
    ): Pair<String?, Boolean> {

        Dev.info(log, "syn.store.call",
            "path" to path,
            "auto" to autoGenerate,
            "trigger" to trigger.name,
            "hash" to (currentContentHash?.take(8) ?: "NONE"))

        val current = entries[path]

        // Check staleness: is the cached summary out of date?
        val isStale = currentContentHash != null &&
                current?.contentHashAtSummary != null &&
                current.contentHashAtSummary != currentContentHash

        // If we have a summary, return it (even if stale)
        if (current?.modelSynopsis != null) {
            return current.modelSynopsis to isStale
        }

        // Evaluate whether we should enqueue
        if (autoGenerate) {
            val enqueued = evaluateAndEnqueue(path, languageId, currentContentHash, maxTokens, trigger)

            if (!enqueued) {
                Dev.info(log, "syn.store.skipped",
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
     * @param maxTokens Max tokens for the summary response
     * @return true if enqueued, false if denied by config
     */
    fun requestSummary(
        path: String,
        languageId: String?,
        currentContentHash: String?,
        maxTokens: Int
    ): Boolean {
        return evaluateAndEnqueue(
            path, languageId, currentContentHash, maxTokens,
            trigger = SummaryTrigger.USER_REQUEST
        )
    }

    /**
     * Update staleness tracking when a file's content changes.
     *
     * Called by VfsSummaryWatcher when it detects file modifications.
     * The summary isn't cleared — stale summaries are still useful.
     */
    fun onHashChange(path: String, newHash: String) {
        // Staleness is detected by comparing hashes in getOrEnqueueSynopsis
        // Nothing to do here beyond what the old implementation did
    }

    /**
     * Pre-compute header samples for multiple files.
     */
    fun warmUp(paths: List<String>, languageId: String?, maxChars: Int) {
        paths.forEach { ensureHeaderSample(it, languageId, maxChars) }
    }

    /**
     * Remove all cached data for a deleted file.
     */
    fun onFileDeleted(path: String) {
        queue.cancel(path)
        entries.remove(path)
    }

    // ==================== Config-Aware Enqueue ====================

    /**
     * Evaluate config and enqueue a summary request if allowed.
     *
     * This is the central decision point for Phase 3. Every summarization
     * request passes through here.
     *
     * @return true if enqueued (or dry-run logged), false if denied
     */
    private fun evaluateAndEnqueue(
        path: String,
        languageId: String?,
        currentContentHash: String?,
        maxTokens: Int,
        trigger: SummaryTrigger
    ): Boolean {
        val config = configService.getConfig()

        // Kill switch
        if (!config.enabled) {
            Dev.info(log, "syn.config.denied",
                "path" to path, "reason" to "kill switch off")
            return false
        }

        // Mode check — USER_REQUEST bypasses mode (it IS on-demand)
        if (trigger != SummaryTrigger.USER_REQUEST) {
            when (config.mode) {
                SummaryMode.OFF -> {
                    Dev.info(log, "syn.config.denied",
                        "path" to path, "reason" to "mode is OFF")
                    return false
                }
                SummaryMode.ON_DEMAND -> {
                    // Background triggers are not allowed in ON_DEMAND mode
                    Dev.info(log, "syn.config.denied",
                        "path" to path, "reason" to "mode is ON_DEMAND, trigger is $trigger")
                    return false
                }
                SummaryMode.SUMMARIZE_PATH -> {
                    Dev.info(log, "syn.config.denied",
                        "path" to path, "reason" to "SUMMARIZE_PATH not yet implemented")
                    return false
                }
                SummaryMode.SMART_BACKGROUND -> {
                    // Allowed — continue to scope/budget checks
                }
            }
        }

        // Count lines for scope evaluation
        val lineCount = countLines(path)

        // Scope check (budget, patterns, min lines)
        val decision = configService.shouldSummarize(path, lineCount)
        if (!decision.allowed) {
            Dev.info(log, "syn.config.denied",
                "path" to path, "reason" to decision.reason)
            return false
        }

        // Dry-run check — everything above passed, but we don't make the API call
        if (config.dryRun) {
            val dryResult = configService.evaluateDryRun(path, lineCount)
            Dev.info(log, "syn.dryrun",
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
            Dev.info(log, "syn.enqueued",
                "path" to path,
                "priority" to priority,
                "trigger" to trigger.name,
                "queueSize" to queue.size()
            )
            processNextInQueue(maxTokens)
        }

        return enqueued
    }

    // ==================== Queue Processing ====================

    /**
     * Process the next item in the queue.
     *
     * Launches a coroutine to handle the actual summarization.
     * Checks kill switch again before making the API call (it could have
     * been toggled between enqueue and processing).
     */
    private fun processNextInQueue(maxTokens: Int) {
        scope.launch {
            val request = queue.poll() ?: return@launch

            // Re-check kill switch (could have changed since enqueue)
            if (!configService.isEnabled()) {
                Dev.info(log, "syn.process.killed",
                    "path" to request.filePath, "reason" to "kill switch toggled")
                return@launch
            }

            // Re-check dry-run
            if (configService.isDryRun()) {
                Dev.info(log, "syn.process.dryrun_skip",
                    "path" to request.filePath)
                return@launch
            }

            // Re-check budget
            if (configService.getRemainingBudget()?.let { it <= 0 } == true) {
                Dev.info(log, "syn.process.budget_exhausted",
                    "path" to request.filePath)
                return@launch
            }

            executeSummarization(request, maxTokens)
        }
    }

    /**
     * Execute the actual summarization for a request.
     *
     * This is the only place that makes an API call.
     * Extracted from the old enqueueSynopsisGeneration() method.
     */
    private suspend fun executeSummarization(request: SummaryRequest, maxTokens: Int) {
        val path = request.filePath
        val languageId = request.languageId
        val currentContentHash = request.contentHash

        try {
            // Get the summary provider
            val provider = ProviderRegistry.selectedSummaryProvider(project)
            if (provider == null) {
                Dev.warn(log, "syn.run.no_provider", null, "path" to path)
                return
            }

            Dev.info(log, "syn.provider",
                "path" to path,
                "provider" to provider.id,
                "displayName" to provider.displayName
            )

            // Get source text
            val (headerSample, _) = ensureHeaderSample(path, languageId, 1_500)
            val sourceText = headerSample ?: computeHeaderSample(path, 1_500) ?: ""

            if (sourceText.isBlank()) {
                Dev.warn(log, "syn.run.empty_source", null, "path" to path)
                return
            }

            // Build the summary prompt
            val prompt = SummaryExtractor.buildPrompt(
                languageId = languageId,
                sourceText = sourceText,
                settings = null
            )

            Dev.info(log, "syn.run.start",
                "path" to path,
                "provider" to provider.id,
                "srcLen" to sourceText.length,
                "promptLen" to prompt.length
            )

            // Call the provider
            val response = provider.summarize(prompt)

            // Extract the summary text
            val extraction = SummaryExtractor.extract(response.parsed, response.parsed.rawText)

            when (extraction) {
                is SummaryExtractor.ExtractionResult.Success -> {
                    val summary = extraction.summary

                    Dev.info(log, "syn.run.ok",
                        "path" to path,
                        "len" to summary.length
                    )

                    // Cache the result
                    val now = Instant.now()
                    entries.compute(path) { _, curr ->
                        val base = curr ?: Entry(path = path, languageId = languageId)
                        base.copy(
                            modelSynopsis = summary,
                            contentHashAtSummary = currentContentHash,
                            lastSummarizedAt = now
                        )
                    }

                    // Record token usage
                    // TODO(Phase 4): Extract actual tokens from provider response
                    // For now, estimate from response length
                    val estimatedTokens = (prompt.length + summary.length) / 4
                    configService.recordTokensUsed(estimatedTokens, ExchangePurpose.FILE_SUMMARY)
                }

                is SummaryExtractor.ExtractionResult.Failed -> {
                    Dev.warn(log, "syn.run.extraction_failed", null,
                        "path" to path,
                        "reason" to extraction.reason
                    )
                }
            }

        } catch (e: Throwable) {
            Dev.warn(log, "syn.run.fail", e, "path" to path)
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
        // Kill switch turned off or mode set to OFF → cancel everything
        if ((!new.enabled && old.enabled) || (new.mode == SummaryMode.OFF && old.mode != SummaryMode.OFF)) {
            val cancelled = queue.cancelAll()
            if (cancelled > 0) {
                Dev.info(log, "syn.config_change.cancelled_queue",
                    "count" to cancelled,
                    "reason" to if (!new.enabled) "kill switch" else "mode OFF"
                )
            }
        }
    }

    // ==================== Internal Helpers ====================

    /**
     * Read the first N characters of a file.
     */
    private fun computeHeaderSample(path: String, maxChars: Int): String? {
        return try {
            val p = Path.of(path)
            if (!Files.isRegularFile(p)) return null
            val raw = Files.readString(p)
            raw.substring(0, min(raw.length, maxChars))
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Count lines in a file (for scope evaluation).
     * Returns 0 if file can't be read.
     */
    private fun countLines(path: String): Int {
        return try {
            Files.lines(Path.of(path)).use { it.count().toInt() }
        } catch (_: Throwable) {
            0
        }
    }

    // ==================== Lifecycle ====================

    override fun dispose() {
        queue.cancelAll()
        scope.cancel("Project disposed")
    }
}