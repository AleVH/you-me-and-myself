// File: src/main/kotlin/com/youmeandmyself/summary/pipeline/SummaryPipeline.kt
package com.youmeandmyself.summary.pipeline

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.youmeandmyself.dev.Dev
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.youmeandmyself.summary.cache.SummaryCache
import com.youmeandmyself.summary.cache.SummaryState
import com.youmeandmyself.summary.config.ConfigChangeListener
import com.youmeandmyself.summary.config.SummaryConfig
import com.youmeandmyself.summary.config.SummaryConfigService
import com.youmeandmyself.summary.config.SummaryMode
import com.youmeandmyself.summary.config.SummaryQueue
import com.youmeandmyself.summary.config.SummaryRequest
import com.youmeandmyself.summary.config.SummaryTrigger
import com.youmeandmyself.summary.model.CodeElement
import com.youmeandmyself.summary.model.CodeElementKind
import com.youmeandmyself.summary.structure.CodeStructureProvider
import com.youmeandmyself.summary.structure.CodeStructureProviderFactory
import com.youmeandmyself.summary.structure.DetectionScope
import com.youmeandmyself.summary.structure.ElementLevel
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

    /** Lazy reference to structure provider factory — for PSI-based code detection. */
    private val structureFactory: CodeStructureProviderFactory by lazy {
        CodeStructureProviderFactory.getInstance(project)
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

            // --- Resolve VirtualFile for PSI-based detection ---
            val virtualFile = LocalFileSystem.getInstance().findFileByPath(path)

            // --- Get the structure provider (null = PSI unavailable) ---
            val structureProvider = structureFactory.get()

            // --- Attempt hierarchical generation ---
            // Try to detect code elements via PSI and build bottom-up.
            // Falls back to direct file summarization if:
            // - PSI is unavailable (dumb mode, unsupported language)
            // - VirtualFile not found
            // - No code elements detected (e.g., config file, script without classes)
            val hierarchicalResult = if (structureProvider != null && virtualFile != null) {
                tryHierarchicalGeneration(
                    path = path,
                    file = virtualFile,
                    languageId = languageId,
                    structureProvider = structureProvider,
                    aiProvider = provider
                )
            } else {
                Dev.info(log, "pipeline.psi_unavailable",
                    "path" to path,
                    "hasProvider" to (structureProvider != null),
                    "hasVirtualFile" to (virtualFile != null),
                    "reason" to when {
                        structureProvider == null -> "PSI unavailable (dumb mode or unsupported language)"
                        virtualFile == null -> "VirtualFile not found"
                        else -> "unknown"
                    }
                )
                null
            }

            val (promptTemplate, effectiveContent, purpose) = if (hierarchicalResult != null) {
                // Hierarchical path: we have class contracts, use FILE_TEMPLATE
                Dev.info(log, "pipeline.hierarchical_path",
                    "path" to path,
                    "classContracts" to hierarchicalResult.childContracts.size
                )
                // Extract file-level structural context via PSI
                // We need a file-level CodeElement for this — create a lightweight one
                val fileContext = if (structureProvider != null && virtualFile != null) {
                    // Detect at file level for structural context
                    val fileElements = structureProvider.detectElements(virtualFile, DetectionScope.ClassesOnly)
                    if (fileElements.isNotEmpty()) {
                        // Use the first element's file range for context extraction
                        structureProvider.extractStructuralContext(virtualFile, fileElements.first(), ElementLevel.FILE)
                    } else ""
                } else ""

                val prompt = SummaryExtractor.buildHierarchicalPrompt(
                    purpose = ExchangePurpose.FILE_SUMMARY,
                    languageId = languageId,
                    childSummaries = hierarchicalResult.childContracts,
                    structuralContext = fileContext
                )
                Triple(prompt, "", ExchangePurpose.FILE_SUMMARY)
            } else {
                // Fallback: direct file summarization from source (original behavior)
                // This path is taken when PSI is unavailable or no classes detected.
                Dev.info(log, "pipeline.direct_path",
                    "path" to path,
                    "reason" to "PSI unavailable or no code elements detected"
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

    // ==================== Validation Rule: Every Level, Every Time ====================

    /**
     * Returns a cached summary ONLY if it is valid (READY and hash matches).
     * Returns null if missing, invalidated, or stale.
     *
     * This is the single point where validation happens. Every level of the
     * cascade calls this before using a cached summary. No summary is ever
     * used without passing through this check.
     *
     * @param filePath Absolute file path
     * @param element The code element to check
     * @param structureProvider PSI provider for computing the current semantic hash
     * @param file VirtualFile for hash computation
     * @return The cached synopsis if valid, or null if it needs regeneration
     */
    private fun getValidSummary(
        filePath: String,
        element: CodeElement,
        structureProvider: CodeStructureProvider,
        file: VirtualFile
    ): String? {
        val currentHash = structureProvider.computeElementHash(file, element)
        val (synopsis, isStale) = cache.getCachedElementSynopsis(filePath, element.signature, currentHash)

        return if (synopsis != null && !isStale) {
            Dev.info(log, "pipeline.validation.valid",
                "element" to element.signature,
                "action" to "reuse"
            )
            synopsis
        } else {
            Dev.info(log, "pipeline.validation.invalid",
                "element" to element.signature,
                "hasSynopsis" to (synopsis != null),
                "isStale" to isStale,
                "action" to "regenerate"
            )
            null
        }
    }

    // ==================== Hierarchical Generation ====================

    /**
     * Attempt hierarchical generation for a file summary using PSI-based detection.
     *
     * Detects classes and methods via PSI, generates method and class summaries
     * bottom-up, and returns the class contracts for file-level consumption.
     *
     * Returns null if:
     * - No code elements detected (e.g., config file, script without classes)
     * - Element detection fails
     * - Any step in the chain fails critically
     *
     * The caller falls back to direct source-code summarization on null.
     *
     * @param path File path (for cache key construction and logging)
     * @param file VirtualFile for PSI detection
     * @param languageId Programming language
     * @param structureProvider PSI-based structure provider (non-null, already checked by caller)
     * @param aiProvider AI provider for generation
     * @return Hierarchical result with class contracts, or null to fall back
     */
    private suspend fun tryHierarchicalGeneration(
        path: String,
        file: VirtualFile,
        languageId: String?,
        structureProvider: CodeStructureProvider,
        aiProvider: com.youmeandmyself.ai.providers.AiProvider
    ): HierarchicalResult? {
        // Detect classes in the file via PSI
        val classElements = structureProvider.detectElements(file, DetectionScope.ClassesOnly)
        if (classElements.isEmpty()) {
            Dev.info(log, "pipeline.hierarchical.no_classes",
                "path" to path,
                "reason" to "PSI detected no classes in file"
            )
            return null
        }

        Dev.info(log, "pipeline.hierarchical.classes_found",
            "path" to path,
            "count" to classElements.size,
            "names" to classElements.map { it.name }.joinToString(", ")
        )

        val classContracts = mutableListOf<String>()

        for (classElement in classElements) {
            val classContract = generateClassSummaryIfNeeded(
                filePath = path,
                file = file,
                classElement = classElement,
                languageId = languageId,
                structureProvider = structureProvider,
                aiProvider = aiProvider
            )
            if (classContract != null) {
                classContracts.add(classContract)
            } else {
                // If class summary generation fails, include a fallback description
                classContracts.add("${classElement.name}: (summary generation failed — see source)")
                Dev.warn(log, "pipeline.hierarchical.class_failed", null,
                    "path" to path,
                    "class" to classElement.name,
                    "signature" to classElement.signature
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
     * Uses PSI to detect methods within the class, generates method summaries
     * bottom-up, then builds the class summary from method contracts.
     *
     * @param filePath File path for cache key construction
     * @param file VirtualFile for PSI detection
     * @param classElement The class element detected by PSI
     * @param languageId Programming language
     * @param structureProvider PSI-based structure provider
     * @param aiProvider AI provider for generation
     * @return The class contract string for file-level consumption, or null on failure
     */
    private suspend fun generateClassSummaryIfNeeded(
        filePath: String,
        file: VirtualFile,
        classElement: CodeElement,
        languageId: String?,
        structureProvider: CodeStructureProvider,
        aiProvider: com.youmeandmyself.ai.providers.AiProvider
    ): String? {
        // Validation rule: check if a VALID (non-stale, non-invalidated) class summary exists.
        // This is the "every level, every time" check.
        val validClassSummary = getValidSummary(filePath, classElement, structureProvider, file)
        if (validClassSummary != null) {
            val contract = SummaryExtractor.extractContract(validClassSummary)
            if (contract != null) return contract
            return "${classElement.name}: $validClassSummary"
        }

        // No valid class summary — need to generate from method summaries.
        // But first, ensure all method summaries are valid too (cascade down).
        val classKey = "$filePath#${classElement.signature}"

        // Detect methods in this class via PSI
        val methods = structureProvider.detectElements(file, DetectionScope.MethodsOf(classElement.name))
            .filter { it.kind in setOf(CodeElementKind.METHOD, CodeElementKind.FUNCTION) }

        Dev.info(log, "pipeline.hierarchical.methods_found",
            "class" to classElement.name,
            "count" to methods.size,
            "methods" to methods.map { it.name }.joinToString(", ")
        )

        // Generate method summaries bottom-up
        val methodContracts = mutableListOf<String>()
        for (method in methods) {
            val methodContract = generateMethodSummaryIfNeeded(
                filePath = filePath,
                file = file,
                className = classElement.name,
                methodElement = method,
                languageId = languageId,
                structureProvider = structureProvider,
                aiProvider = aiProvider
            )
            if (methodContract != null) {
                methodContracts.add(methodContract)
            }
        }

        // Build class summary from method contracts using PSI structural context
        val structuralContext = structureProvider.extractStructuralContext(
            file, classElement, ElementLevel.CLASS
        )
        val prompt = SummaryExtractor.buildHierarchicalPrompt(
            purpose = ExchangePurpose.CLASS_SUMMARY,
            languageId = languageId,
            childSummaries = methodContracts.ifEmpty { listOf("(no method contracts available — summarize from class structure)") },
            structuralContext = structuralContext
        )

        val result = summarizationService.summarize(
            provider = aiProvider,
            content = if (methodContracts.isEmpty()) classElement.body else "",
            promptTemplate = prompt,
            purpose = ExchangePurpose.CLASS_SUMMARY,
            metadata = mapOf(
                "filePath" to filePath,
                "className" to classElement.name,
                "signature" to classElement.signature,
                "languageId" to (languageId ?: "unknown"),
                "methodCount" to methods.size.toString()
            )
        )

        if (!result.isError && result.summaryText.isNotBlank()) {
            val cleanSummary = SummaryExtractor.extractSummaryOnly(result.summaryText)
            val contract = SummaryExtractor.extractContract(result.summaryText)

            // Cache the class summary at element level
            val classHash = structureProvider.computeElementHash(file, classElement)
            cache.completeElementClaim(
                path = filePath,
                elementSignature = classElement.signature,
                languageId = languageId,
                synopsis = cleanSummary,
                contentHash = classHash
            )

            Dev.info(log, "pipeline.hierarchical.class_summary_generated",
                "class" to classElement.name,
                "signature" to classElement.signature,
                "summaryLength" to cleanSummary.length,
                "hasContract" to (contract != null)
            )

            return contract ?: "${classElement.name}: $cleanSummary"
        }

        Dev.warn(log, "pipeline.hierarchical.class_generation_failed", null,
            "class" to classElement.name,
            "signature" to classElement.signature,
            "error" to (result.errorMessage ?: "empty summary")
        )
        return null
    }

    /**
     * Generate a method summary from source code, if not already cached.
     *
     * Methods are leaf nodes — generated directly from source + PSI structural context.
     *
     * @param filePath File path for cache key construction
     * @param file VirtualFile for PSI structural context extraction
     * @param className Name of the containing class
     * @param methodElement The method element detected by PSI
     * @param languageId Programming language
     * @param structureProvider PSI-based structure provider
     * @param aiProvider AI provider for generation
     * @return The method contract string for class-level consumption, or null on failure
     */
    private suspend fun generateMethodSummaryIfNeeded(
        filePath: String,
        file: VirtualFile,
        className: String,
        methodElement: CodeElement,
        languageId: String?,
        structureProvider: CodeStructureProvider,
        aiProvider: com.youmeandmyself.ai.providers.AiProvider
    ): String? {
        // Validation rule: check if a VALID (non-stale, non-invalidated) summary exists.
        // This is the "every level, every time" check.
        val validSummary = getValidSummary(filePath, methodElement, structureProvider, file)
        if (validSummary != null) {
            val contract = SummaryExtractor.extractContract(validSummary)
            if (contract != null) return contract
            return "${methodElement.name}: $validSummary"
        }

        // No valid summary — need to generate
        val methodKey = "$filePath#${methodElement.signature}"

        // Generate from source code (leaf node) with PSI structural context
        val structuralContext = structureProvider.extractStructuralContext(
            file, methodElement, ElementLevel.METHOD
        )
        val prompt = SummaryExtractor.buildHierarchicalPrompt(
            purpose = ExchangePurpose.METHOD_SUMMARY,
            languageId = languageId,
            sourceText = methodElement.body,
            structuralContext = structuralContext
        )

        val result = summarizationService.summarize(
            provider = aiProvider,
            content = "",  // prompt already contains source text
            promptTemplate = prompt,
            purpose = ExchangePurpose.METHOD_SUMMARY,
            metadata = mapOf(
                "filePath" to filePath,
                "className" to className,
                "methodName" to methodElement.name,
                "signature" to methodElement.signature,
                "languageId" to (languageId ?: "unknown")
            )
        )

        if (!result.isError && result.summaryText.isNotBlank()) {
            val cleanSummary = SummaryExtractor.extractSummaryOnly(result.summaryText)
            val contract = SummaryExtractor.extractContract(result.summaryText)

            // Cache the method summary at element level
            val methodHash = structureProvider.computeElementHash(file, methodElement)
            cache.completeElementClaim(
                path = filePath,
                elementSignature = methodElement.signature,
                languageId = languageId,
                synopsis = cleanSummary,
                contentHash = methodHash
            )

            Dev.info(log, "pipeline.hierarchical.method_summary_generated",
                "method" to methodElement.name,
                "signature" to methodElement.signature,
                "summaryLength" to cleanSummary.length,
                "hasContract" to (contract != null)
            )

            return contract ?: "${methodElement.name}: $cleanSummary"
        }

        Dev.warn(log, "pipeline.hierarchical.method_generation_failed", null,
            "method" to methodElement.name,
            "signature" to methodElement.signature,
            "error" to (result.errorMessage ?: "empty summary")
        )
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

// CodeElement and CodeElementKind have been moved to com.youmeandmyself.summary.model
// CodeElementDetector (regex-based) has been replaced by PsiCodeStructureProvider
// See: summary/structure/PsiCodeStructureProvider.kt
// See: summary/model/CodeElement.kt