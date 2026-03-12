// File: src/main/kotlin/com/youmeandmyself/summary/pipeline/SummaryPipeline.kt
package com.youmeandmyself.summary.pipeline

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.youmeandmyself.dev.Dev
import com.youmeandmyself.summary.cache.SummaryCache
import com.youmeandmyself.summary.cache.SummaryState
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
import kotlinx.coroutines.withContext
import javax.swing.SwingUtilities

/**
 * Orchestrates summary generation decisions and delegates execution.
 *
 * ## Purpose
 *
 * This is the decision-making and orchestration layer for summarization.
 * It decides WHETHER to summarize (config checks) and WHEN (queue priority),
 * then delegates the actual execution to [SummarizationService].
 *
 * ## Hierarchical Generation (Chunk 3.6)
 *
 * The pipeline now supports bottom-up hierarchical generation:
 *
 *   METHOD_SUMMARY → CLASS_SUMMARY → FILE_SUMMARY → MODULE_SUMMARY → PROJECT_SUMMARY
 *
 * When a file summary is requested, the pipeline:
 * 1. Identifies classes in the file (regex heuristics)
 * 2. For each class, checks if a class summary exists — if not, generates it first
 * 3. For each class, identifies methods — checks/generates method summaries first
 * 4. Builds the file summary from class contracts (not raw code)
 *
 * Methods are leaf nodes — generated directly from source code + structural context.
 *
 * ## Confirmation Gate
 *
 * Module and project summaries can cascade into many sub-summaries.
 * These are gated behind user confirmation before proceeding.
 *
 * ## What This Class Does
 *
 * - Evaluates whether a file should be summarized (kill switch, mode, budget, scope, dry-run)
 * - Manages the [SummaryQueue] for ordered, deduplicated processing
 * - Processes queue items by delegating to [SummarizationService]
 * - Uses single-flight claims via [SummaryCache] to prevent duplicate AI calls
 * - Orchestrates hierarchical generation (method → class → file → module → project)
 * - Updates [SummaryCache] after successful generation via [SummaryCache.completeClaim]
 * - Reacts to config changes (kill switch off → cancel all queued work)
 *
 * ## What This Class Does NOT Do
 *
 * - Cache management (that's [SummaryCache])
 * - AI provider calls (that's [SummarizationService])
 * - Prompt building (that's [SummaryExtractor] via SummarizationService)
 * - Storage persistence (that's [SummarizationService] → LocalStorageFacade)
 * - Structural context extraction (that's [StructuralContextExtractor])
 *
 * ## Single Execution Path
 *
 * All summarization goes through:
 * SummaryPipeline → SummarizationService → provider + JSONL + SQLite → SummaryCache update
 *
 * There is NO direct provider.summarize() call in this class.
 *
 * ## Queue Processing
 *
 * The queue is processed sequentially — one item at a time. After each item
 * completes (success or failure), [processNextInQueue] re-triggers itself to
 * drain remaining items. This ensures the queue doesn't go dead after the
 * first item.
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
     * 3. If state is GENERATING: don't re-enqueue, return null (caller can await if needed)
     * 4. If cache miss + autoGenerate: evaluate config and potentially enqueue
     * 5. Return (null, false) if nothing available
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

        // If already generating, don't re-enqueue — avoid duplicate work
        val state = cache.getState(path)
        if (state == SummaryState.GENERATING) {
            Dev.info(log, "pipeline.already_generating", "path" to path)
            return null to false
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
        // Update cache staleness (handles READY → INVALIDATED and GENERATING dirty flag)
        cache.onHashChange(path, newHash)

        // Optionally enqueue a staleness refresh if in SMART_BACKGROUND mode
        val config = configService.getConfig()
        if (config.isActive && config.mode == SummaryMode.SMART_BACKGROUND && config.hasBudget) {
            // Only re-enqueue if the file is INVALIDATED (had a summary, now stale)
            // and not currently being generated
            val state = cache.getState(path)
            if (state == SummaryState.INVALIDATED) {
                val (existing, _) = cache.getCachedSynopsis(path, newHash)
                if (existing != null) {
                    evaluateAndEnqueue(path, null, newHash, SummaryTrigger.STALENESS_REFRESH)
                }
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
     *
     * ## Queue Drain
     *
     * After each item completes (success or failure), this method calls itself
     * again to process the next item. This ensures the entire queue is drained
     * sequentially. Processing stops when the queue is empty or when config
     * checks (kill switch, budget) prevent further execution.
     *
     * Previous bug: this method was only called once per enqueue, so if
     * multiple items were queued, only the first one ever got processed.
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
                // Still drain the queue — dry-run items should be skipped, not block others
                processNextInQueue()
                return@launch
            }

            // Re-check budget
            if (configService.getRemainingBudget()?.let { it <= 0 } == true) {
                Dev.info(log, "pipeline.process.budget_exhausted",
                    "path" to request.filePath)
                return@launch
            }

            try {
                executeSummarization(request)
            } finally {
                // QUEUE DRAIN: After each item completes (success or failure),
                // process the next one. This ensures the queue doesn't go dead.
                processNextInQueue()
            }
        }
    }

    // ==================== Summarization Execution ====================

    /**
     * Execute summarization by delegating to SummarizationService.
     *
     * Uses single-flight claims to prevent duplicate AI calls:
     * 1. tryClaim() — if already GENERATING, await the result instead
     * 2. On success → completeClaim() (sets READY, broadcasts to waiters)
     * 3. On failure → failClaim() (resets state, unblocks waiters)
     *
     * This is the ONLY place where summarization actually happens.
     * SummarizationService handles: prompt building, provider call,
     * JSONL persistence, SQLite persistence, token indexing.
     *
     * For file-level summaries, this now attempts hierarchical generation
     * (method → class → file) when possible, falling back to direct
     * source-code summarization if element detection fails.
     */
    private suspend fun executeSummarization(request: SummaryRequest) {
        val path = request.filePath
        val languageId = request.languageId

        // --- Single-flight claim ---
        val claimed = cache.tryClaim(path, languageId)
        if (!claimed) {
            // Another coroutine is already generating this file.
            // Await its result instead of making a duplicate AI call.
            Dev.info(log, "pipeline.awaiting_inflight", "path" to path)
            val result = cache.awaitResult(path, cache.claimTtlSeconds * 1000)
            if (result != null) {
                Dev.info(log, "pipeline.inflight_result_received",
                    "path" to path,
                    "synopsisLength" to result.length
                )
            } else {
                Dev.info(log, "pipeline.inflight_result_timeout", "path" to path)
            }
            return
        }

        // --- We hold the claim — proceed with generation ---
        try {
            // Get the summary provider
            val provider = com.youmeandmyself.ai.providers.ProviderRegistry.selectedSummaryProvider(project)
            if (provider == null) {
                Dev.warn(log, "pipeline.no_provider", null, "path" to path)
                cache.failClaim(path)
                return
            }

            // Get source text for the prompt
            val (headerSample, _) = cache.ensureHeaderSample(path, languageId, 1_500)
            val sourceText = headerSample ?: run {
                cache.failClaim(path)
                return
            }

            if (sourceText.isBlank()) {
                Dev.warn(log, "pipeline.empty_source", null, "path" to path)
                cache.failClaim(path)
                return
            }

            // --- Attempt hierarchical generation ---
            // Try to detect code elements and build bottom-up.
            // Falls back to direct file summarization if detection yields nothing.
            val hierarchicalResult = tryHierarchicalGeneration(
                path = path,
                languageId = languageId,
                sourceText = sourceText,
                provider = provider
            )

            val (promptTemplate, effectiveContent, purpose) = if (hierarchicalResult != null) {
                // Hierarchical path: we have class contracts, use FILE_TEMPLATE
                Dev.info(log, "pipeline.hierarchical_path",
                    "path" to path,
                    "classContracts" to hierarchicalResult.childContracts.size
                )
                val structuralContext = StructuralContextExtractor.forFile(sourceText, languageId)
                val prompt = SummaryExtractor.buildHierarchicalPrompt(
                    purpose = ExchangePurpose.FILE_SUMMARY,
                    languageId = languageId,
                    childSummaries = hierarchicalResult.childContracts,
                    structuralContext = structuralContext
                )
                Triple(prompt, "", ExchangePurpose.FILE_SUMMARY)
            } else {
                // Fallback: direct file summarization from source (original behavior)
                Dev.info(log, "pipeline.direct_path",
                    "path" to path,
                    "reason" to "no code elements detected or hierarchical generation failed"
                )
                val directTemplate = """
                    |Summarize this ${languageId ?: "code"} code concisely in plain text.
                    |No markdown formatting, no code blocks, just a clear description of what this code does.
                    |Start your response with "Summary: " followed by the summary text.
                    |
                    |{content}
                """.trimMargin()
                Triple(directTemplate, sourceText, ExchangePurpose.FILE_SUMMARY)
            }

            Dev.info(log, "pipeline.executing",
                "path" to path,
                "provider" to provider.id,
                "purpose" to purpose.name,
                "hierarchical" to (hierarchicalResult != null),
                "contentLength" to effectiveContent.length
            )

            // Delegate to SummarizationService — the single execution point
            // For hierarchical path, the prompt is pre-built (effectiveContent is empty,
            // prompt contains everything). For direct path, SummarizationService
            // inserts effectiveContent into the template via {content} placeholder.
            val result = if (hierarchicalResult != null) {
                // Hierarchical: prompt is fully built, pass as template with empty content
                summarizationService.summarize(
                    provider = provider,
                    content = "",  // prompt already contains everything
                    promptTemplate = promptTemplate,
                    purpose = purpose,
                    metadata = mapOf(
                        "filePath" to path,
                        "languageId" to (languageId ?: "unknown"),
                        "trigger" to request.triggeredBy.name,
                        "hierarchical" to "true",
                        "childContracts" to hierarchicalResult.childContracts.size.toString()
                    )
                )
            } else {
                // Direct: template + content, SummarizationService assembles
                summarizationService.summarize(
                    provider = provider,
                    content = effectiveContent,
                    promptTemplate = promptTemplate,
                    purpose = purpose,
                    metadata = mapOf(
                        "filePath" to path,
                        "languageId" to (languageId ?: "unknown"),
                        "trigger" to request.triggeredBy.name,
                        "hierarchical" to "false"
                    )
                )
            }

            if (!result.isError && result.summaryText.isNotBlank()) {
                // Extract clean summary (strip marker if present)
                val cleanSummary = SummaryExtractor.extractSummaryOnly(result.summaryText)

                // Extract contract for parent consumption (store alongside summary)
                val contract = SummaryExtractor.extractContract(result.summaryText)
                if (contract != null) {
                    Dev.info(log, "pipeline.contract_extracted",
                        "path" to path,
                        "contractLength" to contract.length
                    )
                }

                // Complete the claim — sets READY (or INVALIDATED if dirty), broadcasts to waiters
                cache.completeClaim(
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
                    "hasContract" to (contract != null),
                    "tokens" to tokensUsed
                )
            } else {
                Dev.warn(log, "pipeline.generation_failed", null,
                    "path" to path,
                    "error" to (result.errorMessage ?: "empty summary")
                )
                cache.failClaim(path)
            }

        } catch (e: Throwable) {
            Dev.warn(log, "pipeline.error", e, "path" to path)
            cache.failClaim(path)
        }
    }

    // ==================== Hierarchical Generation ====================

    /**
     * Attempt hierarchical generation for a file summary.
     *
     * Detects classes and methods in the source code, generates method and class
     * summaries bottom-up, and returns the class contracts for file-level consumption.
     *
     * Returns null if:
     * - No code elements detected (e.g., config file, script without classes)
     * - Element detection fails
     * - Any step in the chain fails critically
     *
     * The caller falls back to direct source-code summarization on null.
     *
     * @param path File path (for cache key construction)
     * @param languageId Programming language
     * @param sourceText Full file source code
     * @param provider AI provider for generation
     * @return Hierarchical result with class contracts, or null to fall back
     */
    private suspend fun tryHierarchicalGeneration(
        path: String,
        languageId: String?,
        sourceText: String,
        provider: com.youmeandmyself.ai.providers.AiProvider
    ): HierarchicalResult? {
        // Detect classes in the file
        val classes = CodeElementDetector.detectClasses(sourceText, languageId)
        if (classes.isEmpty()) {
            Dev.info(log, "pipeline.hierarchical.no_classes", "path" to path)
            return null
        }

        Dev.info(log, "pipeline.hierarchical.classes_found",
            "path" to path,
            "count" to classes.size,
            "names" to classes.map { it.name }.joinToString(", ")
        )

        val classContracts = mutableListOf<String>()

        for (classElement in classes) {
            val classContract = generateClassSummaryIfNeeded(
                filePath = path,
                classElement = classElement,
                languageId = languageId,
                provider = provider
            )
            if (classContract != null) {
                classContracts.add(classContract)
            } else {
                // If class summary generation fails, include a fallback description
                classContracts.add("${classElement.name}: (summary generation failed — see source)")
                Dev.warn(log, "pipeline.hierarchical.class_failed", null,
                    "path" to path,
                    "class" to classElement.name
                )
            }
        }

        return if (classContracts.isNotEmpty()) {
            HierarchicalResult(childContracts = classContracts)
        } else {
            null
        }
    }

    /**
     * Generate a class summary from its method contracts, if not already cached.
     *
     * @return The class contract string for file-level consumption, or null on failure
     */
    private suspend fun generateClassSummaryIfNeeded(
        filePath: String,
        classElement: CodeElement,
        languageId: String?,
        provider: com.youmeandmyself.ai.providers.AiProvider
    ): String? {
        val classKey = "$filePath#${classElement.name}"

        // Check cache for existing class summary
        val (cached, _) = cache.getCachedSynopsis(classKey, null)
        if (cached != null) {
            // Try to extract contract from cached summary
            val contract = SummaryExtractor.extractContract(cached)
            if (contract != null) return contract
            // If no contract marker in cached version, use the whole summary as contract
            return "${classElement.name}: $cached"
        }

        // Detect methods in this class
        val methods = CodeElementDetector.detectMethods(classElement.body, languageId)

        Dev.info(log, "pipeline.hierarchical.methods_found",
            "class" to classElement.name,
            "count" to methods.size
        )

        // Generate method summaries bottom-up
        val methodContracts = mutableListOf<String>()
        for (method in methods) {
            val methodContract = generateMethodSummaryIfNeeded(
                filePath = filePath,
                className = classElement.name,
                methodElement = method,
                languageId = languageId,
                provider = provider
            )
            if (methodContract != null) {
                methodContracts.add(methodContract)
            }
        }

        // Build class summary from method contracts
        val structuralContext = StructuralContextExtractor.forClass(classElement.body, languageId)
        val prompt = SummaryExtractor.buildHierarchicalPrompt(
            purpose = ExchangePurpose.CLASS_SUMMARY,
            languageId = languageId,
            childSummaries = methodContracts.ifEmpty { listOf("(no method contracts available — summarize from class structure)") },
            structuralContext = structuralContext
        )

        val result = summarizationService.summarize(
            provider = provider,
            content = if (methodContracts.isEmpty()) classElement.body else "",
            promptTemplate = prompt,
            purpose = ExchangePurpose.CLASS_SUMMARY,
            metadata = mapOf(
                "filePath" to filePath,
                "className" to classElement.name,
                "languageId" to (languageId ?: "unknown"),
                "methodCount" to methods.size.toString()
            )
        )

        if (!result.isError && result.summaryText.isNotBlank()) {
            val cleanSummary = SummaryExtractor.extractSummaryOnly(result.summaryText)
            val contract = SummaryExtractor.extractContract(result.summaryText)

            // Cache the class summary
            cache.completeClaim(
                path = classKey,
                languageId = languageId,
                synopsis = cleanSummary,
                contentHash = null
            )

            return contract ?: "${classElement.name}: $cleanSummary"
        }

        return null
    }

    /**
     * Generate a method summary from source code, if not already cached.
     *
     * Methods are leaf nodes — generated directly from source + structural context.
     *
     * @return The method contract string for class-level consumption, or null on failure
     */
    private suspend fun generateMethodSummaryIfNeeded(
        filePath: String,
        className: String,
        methodElement: CodeElement,
        languageId: String?,
        provider: com.youmeandmyself.ai.providers.AiProvider
    ): String? {
        val methodKey = "$filePath#$className#${methodElement.name}"

        // Check cache for existing method summary
        val (cached, _) = cache.getCachedSynopsis(methodKey, null)
        if (cached != null) {
            val contract = SummaryExtractor.extractContract(cached)
            if (contract != null) return contract
            return "${methodElement.name}: $cached"
        }

        // Generate from source code (leaf node)
        val structuralContext = StructuralContextExtractor.forMethod(methodElement.body, languageId)
        val prompt = SummaryExtractor.buildHierarchicalPrompt(
            purpose = ExchangePurpose.METHOD_SUMMARY,
            languageId = languageId,
            sourceText = methodElement.body,
            structuralContext = structuralContext
        )

        val result = summarizationService.summarize(
            provider = provider,
            content = "",  // prompt already contains source text
            promptTemplate = prompt,
            purpose = ExchangePurpose.METHOD_SUMMARY,
            metadata = mapOf(
                "filePath" to filePath,
                "className" to className,
                "methodName" to methodElement.name,
                "languageId" to (languageId ?: "unknown")
            )
        )

        if (!result.isError && result.summaryText.isNotBlank()) {
            val cleanSummary = SummaryExtractor.extractSummaryOnly(result.summaryText)
            val contract = SummaryExtractor.extractContract(result.summaryText)

            // Cache the method summary
            cache.completeClaim(
                path = methodKey,
                languageId = languageId,
                synopsis = cleanSummary,
                contentHash = null
            )

            return contract ?: "${methodElement.name}: $cleanSummary"
        }

        return null
    }

    // ==================== Module/Project Confirmation Gate ====================

    /**
     * Request a module-level summary with confirmation gate.
     *
     * Module summaries can cascade into generating file → class → method summaries
     * for all files in the module. This shows a confirmation dialog before proceeding.
     *
     * @param modulePath Path to the module root directory
     * @param filePaths List of file paths in the module to summarize
     * @param languageId Dominant language of the module
     * @return true if user confirmed and summarization was enqueued
     */
    fun requestModuleSummary(
        modulePath: String,
        filePaths: List<String>,
        languageId: String?
    ): Boolean {
        return requestConfirmedSummary(
            path = modulePath,
            childPaths = filePaths,
            languageId = languageId,
            purpose = ExchangePurpose.MODULE_SUMMARY,
            levelLabel = "module"
        )
    }

    /**
     * Request a project-level summary with confirmation gate.
     *
     * Project summaries cascade into module → file → class → method summaries.
     * This shows a confirmation dialog before proceeding.
     *
     * @param projectPath Path to the project root
     * @param modulePaths List of module paths in the project
     * @param languageId Dominant language of the project
     * @return true if user confirmed and summarization was enqueued
     */
    fun requestProjectSummary(
        projectPath: String,
        modulePaths: List<String>,
        languageId: String?
    ): Boolean {
        return requestConfirmedSummary(
            path = projectPath,
            childPaths = modulePaths,
            languageId = languageId,
            purpose = ExchangePurpose.PROJECT_SUMMARY,
            levelLabel = "project"
        )
    }

    /**
     * Show confirmation dialog and enqueue if user approves.
     *
     * Runs on EDT (Swing thread) for the dialog, then enqueues on confirmation.
     */
    private fun requestConfirmedSummary(
        path: String,
        childPaths: List<String>,
        languageId: String?,
        purpose: ExchangePurpose,
        levelLabel: String
    ): Boolean {
        // Estimate the cascade: how many sub-summaries might be generated
        val estimatedCount = childPaths.size
        val message = buildString {
            append("Generate a $levelLabel summary for:\n")
            append("  $path\n\n")
            append("This may generate summaries for up to $estimatedCount ${if (purpose == ExchangePurpose.PROJECT_SUMMARY) "modules" else "files"}.\n")
            append("Each file may also generate method and class summaries.\n\n")
            append("Budget checks will apply to each individual generation.\n")
            append("Continue?")
        }

        // Show dialog on EDT
        var confirmed = false
        if (SwingUtilities.isEventDispatchThread()) {
            confirmed = showConfirmationDialog(message, levelLabel)
        } else {
            SwingUtilities.invokeAndWait {
                confirmed = showConfirmationDialog(message, levelLabel)
            }
        }

        if (!confirmed) {
            Dev.info(log, "pipeline.confirmation_denied",
                "path" to path,
                "purpose" to purpose.name
            )
            return false
        }

        Dev.info(log, "pipeline.confirmation_granted",
            "path" to path,
            "purpose" to purpose.name,
            "childCount" to childPaths.size
        )

        // Enqueue child summaries — each goes through the normal evaluateAndEnqueue path
        // which checks budget per item
        for (childPath in childPaths) {
            evaluateAndEnqueue(childPath, languageId, null, SummaryTrigger.USER_REQUEST)
        }

        // TODO: After all child summaries complete, trigger the module/project-level
        // summary that aggregates their contracts. This requires a completion callback
        // or a "wait for all children" mechanism. For now, the child summaries are
        // enqueued and the aggregation step is not yet wired. This will be completed
        // when the summary queue supports dependent/chained requests.

        return true
    }

    private fun showConfirmationDialog(message: String, levelLabel: String): Boolean {
        val result = Messages.showYesNoDialog(
            project,
            message,
            "Generate ${levelLabel.replaceFirstChar { it.uppercaseChar() }} Summary",
            "Generate",
            "Cancel",
            Messages.getQuestionIcon()
        )
        return result == Messages.YES
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

// ==================== Hierarchical Generation Support ====================

/**
 * Result of hierarchical generation for a single level.
 *
 * Contains the contracts from child elements that the parent level consumes.
 *
 * @property childContracts Contract strings from the level below (e.g., class contracts for file summary)
 */
data class HierarchicalResult(
    val childContracts: List<String>
)

/**
 * A detected code element (class, method, function, interface, object).
 *
 * Used by [CodeElementDetector] to represent structural code elements
 * found via regex heuristics. These are NOT full PSI nodes — they're
 * pragmatic approximations good enough for hierarchical summarization.
 *
 * @property name Element name (e.g., "SummaryPipeline", "executeSummarization")
 * @property body The source code of this element (including signature and body)
 * @property kind What kind of element this is (class, method, interface, etc.)
 */
data class CodeElement(
    val name: String,
    val body: String,
    val kind: CodeElementKind
)

enum class CodeElementKind {
    CLASS, INTERFACE, OBJECT, ENUM, METHOD, FUNCTION
}

/**
 * Regex-based code element detector.
 *
 * Detects classes, interfaces, objects, and methods in source code using
 * regex heuristics. This is intentionally pragmatic — not a full parser.
 *
 * ## Why Regex Instead of PSI?
 *
 * - PSI requires read actions and can be slow for large files
 * - PSI isn't available in all contexts (dumb mode, background threads)
 * - The LLM understands code well even without perfect structural boundaries
 * - Regex is fast, predictable, and good enough for launch
 *
 * ## Limitations
 *
 * - Brace matching is approximate (counts nesting depth, doesn't handle strings)
 * - Nested classes may not be perfectly bounded
 * - Unusual code formatting may cause missed or mis-bounded elements
 *
 * These limitations are acceptable because:
 * 1. The LLM can handle imperfect boundaries (it reads code, not just contracts)
 * 2. The fallback path (direct file summarization) always works
 * 3. PSI integration can replace this incrementally
 */
object CodeElementDetector {

    private val log = Logger.getInstance(CodeElementDetector::class.java)

    // ==================== Class Detection ====================

    /**
     * Detect top-level classes, interfaces, objects, and enums in source code.
     *
     * @param source Full file source code
     * @param languageId Programming language
     * @return List of detected class-level elements with their bodies
     */
    fun detectClasses(source: String, languageId: String?): List<CodeElement> {
        val lang = normalizeLanguage(languageId)
        return when (lang) {
            LangFamily.JVM -> detectJvmClasses(source)
            LangFamily.JS_TS -> detectJsTsClasses(source)
            LangFamily.OTHER -> emptyList()
        }
    }

    /**
     * Detect methods/functions within a class body.
     *
     * @param classBody The source code of the class (including declaration)
     * @param languageId Programming language
     * @return List of detected method elements with their bodies
     */
    fun detectMethods(classBody: String, languageId: String?): List<CodeElement> {
        val lang = normalizeLanguage(languageId)
        return when (lang) {
            LangFamily.JVM -> detectJvmMethods(classBody)
            LangFamily.JS_TS -> detectJsTsMethods(classBody)
            LangFamily.OTHER -> emptyList()
        }
    }

    // ==================== JVM (Kotlin/Java) ====================

    private fun detectJvmClasses(source: String): List<CodeElement> {
        // Match class/interface/object/enum declarations
        // Pattern: optional modifiers + (class|interface|object|enum class) + name + optional generics + optional supers + {
        val pattern = Regex(
            """(?:^|\n)\s*(?:(?:public|private|protected|internal|open|abstract|sealed|data|inner|value|annotation)\s+)*(?:(enum\s+class|class|interface|object))\s+(\w+)""",
            RegexOption.MULTILINE
        )

        val elements = mutableListOf<CodeElement>()
        for (match in pattern.findAll(source)) {
            val kind = when (match.groupValues[1]) {
                "class" -> CodeElementKind.CLASS
                "interface" -> CodeElementKind.INTERFACE
                "object" -> CodeElementKind.OBJECT
                "enum class" -> CodeElementKind.ENUM
                else -> CodeElementKind.CLASS
            }
            val name = match.groupValues[2]
            val body = extractBraceBlock(source, match.range.first)

            if (body != null && body.length > 10) { // skip trivially empty
                elements.add(CodeElement(name = name, body = body, kind = kind))
            }
        }

        Dev.info(log, "detect.jvm_classes",
            "count" to elements.size,
            "names" to elements.map { it.name }.joinToString(", ")
        )

        return elements
    }

    private fun detectJvmMethods(classBody: String): List<CodeElement> {
        // Match fun/method declarations
        // Kotlin: fun name(...)
        // Java: returnType name(...)
        // We focus on Kotlin `fun` keyword as primary, with Java method pattern as secondary
        val kotlinPattern = Regex(
            """(?:^|\n)\s*(?:(?:public|private|protected|internal|override|open|abstract|suspend|inline|tailrec|operator)\s+)*fun\s+(?:\w+\.)?(\w+)\s*\(""",
            RegexOption.MULTILINE
        )

        val elements = mutableListOf<CodeElement>()
        for (match in kotlinPattern.findAll(classBody)) {
            val name = match.groupValues[1]
            val body = extractBraceBlock(classBody, match.range.first)
                ?: extractExpressionBody(classBody, match.range.first)

            if (body != null && body.length > 5) {
                elements.add(CodeElement(name = name, body = body, kind = CodeElementKind.METHOD))
            }
        }

        return elements
    }

    // ==================== JS/TS ====================

    private fun detectJsTsClasses(source: String): List<CodeElement> {
        val pattern = Regex(
            """(?:^|\n)\s*(?:export\s+)?(?:abstract\s+)?class\s+(\w+)""",
            RegexOption.MULTILINE
        )

        val elements = mutableListOf<CodeElement>()
        for (match in pattern.findAll(source)) {
            val name = match.groupValues[1]
            val body = extractBraceBlock(source, match.range.first)
            if (body != null && body.length > 10) {
                elements.add(CodeElement(name = name, body = body, kind = CodeElementKind.CLASS))
            }
        }

        return elements
    }

    private fun detectJsTsMethods(classBody: String): List<CodeElement> {
        // Detect methods in JS/TS classes:
        // - methodName(...) {
        // - async methodName(...) {
        // - static methodName(...) {
        // - get/set propertyName() {
        val pattern = Regex(
            """(?:^|\n)\s*(?:(?:public|private|protected|static|async|get|set)\s+)*(\w+)\s*\([^)]*\)\s*(?::\s*\S+\s*)?\{""",
            RegexOption.MULTILINE
        )

        val elements = mutableListOf<CodeElement>()
        for (match in pattern.findAll(classBody)) {
            val name = match.groupValues[1]
            if (name in setOf("if", "for", "while", "switch", "catch")) continue // skip control flow
            val body = extractBraceBlock(classBody, match.range.first)
            if (body != null && body.length > 5) {
                elements.add(CodeElement(name = name, body = body, kind = CodeElementKind.METHOD))
            }
        }

        return elements
    }

    // ==================== Brace Matching ====================

    /**
     * Extract a brace-delimited block starting from the first `{` after startIndex.
     *
     * Uses brace counting (nesting depth) to find the matching `}`.
     * This is approximate — doesn't handle braces inside strings or comments perfectly.
     * Good enough for the LLM to work with.
     *
     * @param source Full source code
     * @param startIndex Position to start searching for the opening `{`
     * @return The text from startIndex through the matching `}`, or null if not found
     */
    private fun extractBraceBlock(source: String, startIndex: Int): String? {
        val openIndex = source.indexOf('{', startIndex)
        if (openIndex == -1 || openIndex > startIndex + 500) return null // opening brace too far away

        var depth = 0
        var i = openIndex
        while (i < source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        return source.substring(startIndex, i + 1)
                    }
                }
            }
            i++
        }

        // Unmatched braces — return what we have (truncated)
        Dev.info(log, "detect.brace_unmatched",
            "startIndex" to startIndex,
            "sourceLength" to source.length
        )
        return null
    }

    /**
     * Extract a Kotlin expression body (= ...) for single-expression functions.
     *
     * Handles: fun name() = someExpression
     * Takes from startIndex to the next newline that isn't a continuation.
     */
    private fun extractExpressionBody(source: String, startIndex: Int): String? {
        val equalsIndex = source.indexOf('=', startIndex)
        if (equalsIndex == -1 || equalsIndex > startIndex + 500) return null

        // Check it's not == or =>
        if (equalsIndex + 1 < source.length && source[equalsIndex + 1] in setOf('=', '>')) return null

        // Take until double newline or next fun/class declaration
        val afterEquals = source.substring(startIndex)
        val endPattern = Regex("""\n\s*\n|\n\s*(?:fun|class|interface|object|val|var)\s""")
        val endMatch = endPattern.find(afterEquals)
        return if (endMatch != null) {
            afterEquals.substring(0, endMatch.range.first).trim()
        } else {
            afterEquals.take(2000).trim() // safety cap
        }
    }

    // ==================== Helpers ====================

    private enum class LangFamily { JVM, JS_TS, OTHER }

    private fun normalizeLanguage(languageId: String?): LangFamily {
        if (languageId == null) return LangFamily.OTHER
        val id = languageId.lowercase()
        return when {
            id.contains("kotlin") || id == "kt" || id.contains("java") && !id.contains("javascript") -> LangFamily.JVM
            id.contains("javascript") || id.contains("typescript") || id in setOf("js", "ts", "jsx", "tsx") -> LangFamily.JS_TS
            else -> LangFamily.OTHER
        }
    }
}