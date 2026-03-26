package com.youmeandmyself.ai.chat.context

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.youmeandmyself.context.orchestrator.ContextBundle
import com.youmeandmyself.context.orchestrator.ContextFile
import com.youmeandmyself.context.orchestrator.ContextKind
import com.youmeandmyself.context.orchestrator.ContextOrchestrator
import com.youmeandmyself.context.orchestrator.ContextRequest
import com.youmeandmyself.context.orchestrator.DetectorRegistry
import com.youmeandmyself.context.orchestrator.OrchestratorMetrics
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import com.youmeandmyself.context.orchestrator.detectors.FrameworkDetector
import com.youmeandmyself.context.orchestrator.detectors.LanguageDetector
import com.youmeandmyself.context.orchestrator.detectors.ProjectStructureDetector
import com.youmeandmyself.context.orchestrator.detectors.RelevantFilesDetector
import com.youmeandmyself.ai.settings.ContextSettingsState
import com.youmeandmyself.context.orchestrator.Detector
import com.youmeandmyself.dev.Dev
import com.youmeandmyself.storage.LocalStorageFacade
import com.youmeandmyself.summary.config.SummaryConfigService
import com.youmeandmyself.summary.config.SummaryMode
import com.youmeandmyself.summary.model.CodeElementKind
import com.youmeandmyself.summary.structure.CodeStructureProviderFactory
import kotlinx.coroutines.CoroutineScope
import java.nio.charset.Charset

/**
 * Assembles the complete prompt that gets sent to the AI provider.
 *
 * ## Why This Exists
 *
 * Before this refactoring, prompt assembly was embedded inside ChatPanel.doSend() —
 * ~120 lines of context detection, IDE introspection, file reading, and string
 * formatting mixed with UI code. This class extracts all of that into a testable,
 * UI-free module.
 *
 * ## What It Does
 *
 * 1. **Context detection**: Determines if the user's message benefits from IDE context
 *    (code files, project structure, framework info). Uses heuristics like detecting
 *    code keywords, file extensions, error messages, or deictic phrases ("this file").
 *
 * 2. **IDE context gathering**: Uses [ContextOrchestrator] to collect project language,
 *    frameworks, build system, and relevant files (with a time budget).
 *
 * 3. **Summary enrichment** (READ PATH): For each file in the context bundle, checks
 *    [SummaryStoreProvider] for an existing summary. If a summary exists AND is
 *    meaningfully shorter than the raw content (saves tokens), replaces the raw entry
 *    with the summary. If no summary exists and the file would benefit from one,
 *    fires a generation suggestion (gated by [SummaryConfigService] — only when the
 *    user has opted into automatic summarization).
 *
 * 4. **Prompt formatting**: Combines all context into a structured prompt with clear
 *    sections ([Context], [Files], etc.) that the AI can parse.
 *
 * ## Summary Philosophy
 *
 * Summaries are an **invisible optimization layer**. The user never sees summaries
 * directly — they see the AI's response, which was informed by summaries instead of
 * raw code. The plugin never wastes tokens when a summary offers a better option,
 * but also never summarizes tiny files where the summary wouldn't save anything.
 *
 * The enrichment step is the READ PATH only. It checks for existing summaries and
 * uses them. Generation suggestions are a lightweight side effect, gated by config,
 * so that summaries get built up over time for next time.
 *
 * ## Conversation History
 *
 * History is NOT included by this class — it's the orchestrator's responsibility to
 * pass history to the provider. This class only handles the "context" portion of the
 * prompt (IDE state, code files, summaries).
 *
 * ## Thread Safety
 *
 * This class is stateless — all state is passed in via method parameters.
 * Multiple context assemblies can run concurrently without interference.
 *
 * @param project The IntelliJ project for IDE introspection
 * @param summaryStore Provider for reading code summaries (local or shared, based on tier)
 * @param summaryConfig Summary configuration service for checking mode/enabled state.
 *                       Used to gate summary generation suggestions — the read path
 *                       (checking existing summaries) always runs regardless of config.
 */
class ContextAssembler(
    private val project: Project,
    private val summaryStore: SummaryStoreProvider,
    private val summaryConfig: SummaryConfigService
) {
    private val log = Dev.logger(ContextAssembler::class.java)



    // ── Public API ───────────────────────────────────────────────────────

    /**
     * Assemble the complete prompt, optionally enriched with IDE context.
     *
     * This is the main entry point. It:
     * 0. Checks bypass mode — FULL skips detection/generation, uses existing summary or raw file
     * 1. Checks if context would be useful for this message
     * 2. If yes, gathers IDE context within a time budget
     * 3. Enriches files with summaries where beneficial
     * 4. Formats everything into the final prompt string
     *
     * @param userInput The raw text the user typed
     * @param scope Coroutine scope for context gathering (respects cancellation)
     * @param bypassMode Context bypass mode from the frontend's ContextDial (backend perspective).
     *   null / "OFF" = bypass off, normal context gathering runs.
     *   "FULL" = full bypass, skip detection/generation, use existing summary or raw file.
     *   "SELECTIVE" = partial bypass — run only the detectors for the given selectiveLevel.
     * @param selectiveLevel Active lever level when bypassMode is "SELECTIVE".
     *   0 = Minimal (no detectors, open file only with existing summary if available).
     *   1 = Partial (Language + Framework + RelevantFiles, skip ProjectStructure).
     *   2 = Full (all 4 detectors — same as bypassMode null). Default: 2.
     * @param summaryEnabled Per-tab summary toggle. When false, skip summary enrichment
     *   entirely (no summaries used or generated). Null = use global setting.
     *   Independent from bypassMode — summary and context are separate features.
     * @return The assembled result with the effective prompt and context metadata
     */
    suspend fun assemble(
        userInput: String,
        scope: CoroutineScope,
        bypassMode: String? = null,
        selectiveLevel: Int? = null,
        summaryEnabled: Boolean? = null,
        /**
         * Force Context scope from the control strip button.
         *
         * null = no force (normal heuristic-driven path).
         * "method" = force the method at cursor into context.
         * "class" = force the class at cursor into context.
         *
         * Complements automatic context — does NOT override it.
         * If the forced element is already in automatic context, no duplication.
         *
         * Phase C.1 — Stub: parameter accepted but not yet used in the pipeline.
         */
        forceContextScope: String? = null
    ): AssembledPrompt {
        // ── Step -1: Global context kill-switch ──────────────────────────
        // Settings → Tools → YMM Assistant → Context → "Enable context gathering"
        //
        // When false: zero context injected, not even the open file.
        // Overrides bypassMode entirely — if context is globally off, the
        // per-tab dial setting is irrelevant for this message.
        //
        // Propagation: instant. ContextSettingsState is read at call time,
        // not cached at startup. Toggling the checkbox and clicking Apply
        // updates in-memory state immediately via loadState() — no restart needed.
        val contextSettings = ContextSettingsState.getInstance(project).state
        if (!contextSettings.contextEnabled) {
            Dev.info(log, "context.kill_switch.active",
                "reason" to "contextEnabled=false in ContextSettingsState"
            )
            return AssembledPrompt(
                effectivePrompt = userInput,
                contextSummary = "[Context: disabled in settings]",
                contextTimeMs = null
            )
        }

        // ── Step 0: Bypass check ─────────────────────────────────────────
        // FULL bypass skips summarization generation and all detection steps
        // (language, framework, project structure). The open file is still
        // grabbed. If an existing up-to-date summary is available, it is
        // used. If not, the raw file content is attached instead.
        // No new summarization is requested.
        if (bypassMode?.uppercase() == "FULL") {
            Dev.info(log, "context.bypass.full.enter",
                "reason" to "user requested full summarization bypass"
            )

            val editorFile = currentEditorFile()

            if (editorFile == null) {
                Dev.info(log, "context.bypass.full.no_editor_file",
                    "result" to "no file open in editor, sending raw user input only"
                )
                return AssembledPrompt(
                    effectivePrompt = userInput,
                    contextSummary = "[Context: bypass=FULL, no file open]",
                    contextTimeMs = null
                )
            }

            Dev.info(log, "context.bypass.full.editor_file",
                "filePath" to editorFile.path,
                "fileName" to editorFile.name,
                "fileType" to editorFile.fileType.name
            )

            // Resolve project ID for summary lookup
            val projectId = try {
                LocalStorageFacade.getInstance(project).resolveProjectId()
            } catch (_: Throwable) {
                project.basePath ?: project.name
            }

            // Check for an existing up-to-date summary — no new summarization triggered.
            val summary = summaryStore.getSummary(editorFile.path, projectId)

            Dev.info(log, "context.bypass.full.summary_lookup",
                "filePath" to editorFile.path,
                "summaryFound" to (summary != null),
                "summaryStale" to (summary?.isStale ?: false),
                "summaryShared" to (summary?.isShared ?: false)
            )

            val contentBlock: String
            val attachmentKind: String

            if (summary != null && !summary.isStale) {
                // Existing up-to-date summary available — use it (no generation triggered)
                val fileName = editorFile.name
                contentBlock = buildString {
                    appendLine("[File: $fileName] (existing summary)")
                    if (!summary.headerSample.isNullOrBlank()) {
                        appendLine(summary.headerSample)
                        appendLine()
                    }
                    append(summary.synopsis)
                }
                attachmentKind = "existing summary"

                Dev.info(log, "context.bypass.full.using_existing_summary",
                    "fileName" to fileName,
                    "synopsisLength" to summary.synopsis.length,
                    "hasHeaderSample" to (!summary.headerSample.isNullOrBlank()),
                    "source" to if (summary.isShared) "shared" else "local"
                )
            } else {
                // No summary or stale — attach raw file content instead
                contentBlock = formatFileBlock(editorFile)
                attachmentKind = "raw file"

                Dev.info(log, "context.bypass.full.using_raw_file",
                    "fileName" to editorFile.name,
                    "reason" to if (summary == null) "no summary exists" else "summary is stale",
                    "contentLength" to contentBlock.length
                )
            }

            val effectivePrompt = if (contentBlock.isNotBlank()) {
                "$contentBlock\n\n$userInput"
            } else {
                userInput
            }

            Dev.info(log, "context.bypass.full.assembled",
                "attachmentKind" to attachmentKind,
                "effectivePromptLength" to effectivePrompt.length
            )

            // Build badge data for the single attached file
            val bypassBadge = listOf(ContextFileDetail(
                path = editorFile.path,
                name = editorFile.name,
                scope = "file",
                lang = fenceLangFor(editorFile),
                kind = if (attachmentKind == "existing summary") "SUMMARY" else "RAW",
                freshness = if (summary != null && !summary.isStale) "cached" else "fresh",
                tokens = contentBlock.length / 4,
                isStale = summary?.isStale ?: false,
                forced = false,
                elementSignature = null
            ))

            // Build context block for RequestBlocks (Phase 1)
            val bypassContextBlock = buildBypassContextBlock(
                contentBlock = contentBlock,
                filePath = editorFile.path,
                fileName = editorFile.name,
                kind = if (attachmentKind == "existing summary")
                    com.youmeandmyself.ai.chat.context.ContextKind.SUMMARY
                else
                    com.youmeandmyself.ai.chat.context.ContextKind.RAW,
                isStale = summary?.isStale ?: false
            )

            return AssembledPrompt(
                effectivePrompt = effectivePrompt,
                contextSummary = "[Context: bypass=FULL, $attachmentKind attached]",
                contextTimeMs = null,
                contextFiles = bypassBadge,
                contextBlock = bypassContextBlock
            )
        }

        // ── SELECTIVE bypass ─────────────────────────────────────────────
        // Partial context gathering: only the detectors defined for the given level run.
        //
        // Level 0 (Minimal): no detectors — attach only the open file using the
        //   same logic as FULL bypass (existing summary or raw). The difference from
        //   FULL bypass is that the user can still reach SELECTIVE level 0 while
        //   knowing context *was* considered — just minimised.
        // Level 1 (Partial): Language + Framework + RelevantFiles. Skips
        //   ProjectStructureDetector (build system overhead). Good for per-file
        //   questions where project structure isn't relevant.
        // Level 2 (Full): all 4 detectors active — identical to bypassMode null.
        //   The lever at maximum = same as having no bypass at all.
        if (bypassMode?.uppercase() == "SELECTIVE") {
            val level = selectiveLevel ?: 2  // Default to Full if level missing

            Dev.info(log, "context.bypass.selective.enter",
                "level" to level,
                "levelName" to when (level) { 0 -> "Minimal"; 1 -> "Partial"; else -> "Full" }
            )

            if (level == 0) {
                // Minimal — same open-file attach logic as FULL bypass (no detectors, no generation)
                val editorFile = currentEditorFile()
                if (editorFile == null) {
                    return AssembledPrompt(
                        effectivePrompt = userInput,
                        contextSummary = "[Context: selective=Minimal, no file open]",
                        contextTimeMs = null
                    )
                }
                val projectId = try {
                    LocalStorageFacade.getInstance(project).resolveProjectId()
                } catch (_: Throwable) { project.basePath ?: project.name }
                val summary = summaryStore.getSummary(editorFile.path, projectId)
                val contentBlock: String
                val attachmentKind: String
                if (summary != null && !summary.isStale) {
                    contentBlock = buildString {
                        appendLine("[File: ${editorFile.name}] (existing summary)")
                        if (!summary.headerSample.isNullOrBlank()) { appendLine(summary.headerSample); appendLine() }
                        append(summary.synopsis)
                    }
                    attachmentKind = "existing summary"
                } else {
                    contentBlock = formatFileBlock(editorFile)
                    attachmentKind = "raw file"
                }
                val effectivePrompt = if (contentBlock.isNotBlank()) "$contentBlock\n\n$userInput" else userInput

                // Build badge data for the single attached file (same as FULL bypass)
                val selectiveMinBadge = listOf(ContextFileDetail(
                    path = editorFile.path,
                    name = editorFile.name,
                    scope = "file",
                    lang = fenceLangFor(editorFile),
                    kind = if (attachmentKind == "existing summary") "SUMMARY" else "RAW",
                    freshness = if (summary != null && !summary.isStale) "cached" else "fresh",
                    tokens = contentBlock.length / 4,
                    isStale = summary?.isStale ?: false,
                    forced = false,
                    elementSignature = null
                ))

                // Build context block for RequestBlocks (Phase 1)
                val selectiveMinContextBlock = buildBypassContextBlock(
                    contentBlock = contentBlock,
                    filePath = editorFile.path,
                    fileName = editorFile.name,
                    kind = if (attachmentKind == "existing summary")
                        com.youmeandmyself.ai.chat.context.ContextKind.SUMMARY
                    else
                        com.youmeandmyself.ai.chat.context.ContextKind.RAW,
                    isStale = summary?.isStale ?: false
                )

                return AssembledPrompt(
                    effectivePrompt = effectivePrompt,
                    contextSummary = "[Context: selective=Minimal, $attachmentKind attached]",
                    contextTimeMs = null,
                    contextFiles = selectiveMinBadge,
                    contextBlock = selectiveMinContextBlock
                )
            }

            // Level 1 or 2: delegate to gatherAndStage() with the appropriate detector level.
            // This eliminates the code duplication with the normal path — both use the
            // same gathering + enrichment pipeline, just with different detector sets.
            val selectiveGatherResult = gatherAndStage(
                userInput = userInput,
                scope = scope,
                detectorLevel = level,
                summaryEnabled = summaryEnabled,
                forceContextScope = forceContextScope
            )

            if (selectiveGatherResult == null) {
                // Heuristic filter skipped or IDE indexing blocked
                return if (DumbService.isDumb(project)) {
                    AssembledPrompt.indexingBlocked()
                } else {
                    AssembledPrompt(
                        effectivePrompt = userInput,
                        contextSummary = "[Context: skipped by heuristic filter (selective)]",
                        contextTimeMs = null
                    )
                }
            }

            // Format the gathered context
            val editorFile = currentEditorFile()
            val contextNote = formatContextNote(selectiveGatherResult.enrichedBundle)
            val manifest = selectiveGatherResult.enrichedBundle.manifestLine()
            val filesBlock = selectiveGatherResult.enrichedBundle.filesSection()
            val fileBlock = buildCurrentFileBlock(userInput, editorFile)
            val effectivePrompt = buildString {
                appendLine(contextNote); appendLine(manifest); appendLine(); appendLine(filesBlock)
                if (fileBlock.isNotBlank()) { appendLine(); appendLine(fileBlock) }
                appendLine(); append(userInput)
            }

            return AssembledPrompt(
                effectivePrompt = effectivePrompt,
                contextSummary = "[Context: selective=level$level, ${selectiveGatherResult.enrichedBundle.manifestLine()}]",
                contextTimeMs = selectiveGatherResult.gatherTimeMs,
                contextFiles = selectiveGatherResult.contextFiles,
                contextBlock = selectiveGatherResult.contextBlock
            )
        }

        // ── Normal path: delegate to gatherAndStage() ────────────────────
        // The gathering + enrichment logic is extracted into gatherAndStage()
        // so it can be called synchronously here (Phase 2 B2) or in a background
        // coroutine (Phase 2 B5). The formatting step uses the result.
        val gatherResult = gatherAndStage(
            userInput = userInput,
            scope = scope,
            detectorLevel = 2,  // Full detector set for normal path
            summaryEnabled = summaryEnabled,
            forceContextScope = forceContextScope
        )

        // Handle early-exit cases returned by gatherAndStage
        if (gatherResult == null) {
            // null means gatherAndStage handled the early return internally.
            // This shouldn't happen in the normal flow because early exits
            // (heuristic skip, indexing blocked) are returned directly above
            // in the bypass checks. But as a safety net:
            return AssembledPrompt(
                effectivePrompt = userInput,
                contextSummary = "[Context: gather returned null]",
                contextTimeMs = null
            )
        }

        // Format the gathered context into a prompt string
        val editorFile = currentEditorFile()
        val contextNote = formatContextNote(gatherResult.enrichedBundle)
        val manifest = gatherResult.enrichedBundle.manifestLine()
        val filesBlock = gatherResult.enrichedBundle.filesSection()
        val fileBlock = buildCurrentFileBlock(userInput, editorFile)

        val effectivePrompt = buildString {
            appendLine(contextNote)
            appendLine(manifest)
            appendLine()
            appendLine(filesBlock)
            if (fileBlock.isNotBlank()) {
                appendLine()
                appendLine(fileBlock)
            }
            appendLine()
            appendLine(userInput)
        }.trimEnd()

        Dev.info(log, "context.assembled",
            "effectivePromptLength" to effectivePrompt.length,
            "bundleFiles" to gatherResult.enrichedBundle.files.size,
            "rawFiles" to gatherResult.enrichedBundle.files.count { it.kind == ContextKind.RAW },
            "summaryFiles" to gatherResult.enrichedBundle.files.count { it.kind == ContextKind.SUMMARY }
        )

        return AssembledPrompt(
            effectivePrompt = effectivePrompt,
            contextSummary = manifest,
            contextTimeMs = gatherResult.gatherTimeMs,
            contextFiles = gatherResult.contextFiles,
            contextBlock = gatherResult.contextBlock
        )
    }

    // ── Gather + Stage (Phase 2 B2) ─────────────────────────────────────

    /**
     * Gather IDE context and produce structured results.
     *
     * ## Phase 2 Split
     *
     * This method extracts the gathering + enrichment logic from [assemble]:
     * - Heuristic pre-filter
     * - Editor element resolution (PSI)
     * - IDE indexing check
     * - Detector-based context gathering
     * - Summary enrichment
     * - ContextBlock + ContextFileDetail construction
     *
     * The formatting step (building the prompt string) stays in [assemble].
     *
     * ## Current behavior (B2)
     *
     * Called synchronously by [assemble]. Returns a [GatherResult] with the
     * enriched bundle and structured metadata.
     *
     * ## Future behavior (B5)
     *
     * Will be called in a background coroutine by [ContextStagingService].
     * As entries are gathered, they will be added progressively to the staging
     * area with CONTEXT_BADGE_UPDATE bridge events.
     *
     * @param userInput The user's message (for heuristic check)
     * @param scope Coroutine scope for context gathering
     * @param detectorLevel Which detectors to run (1 = partial, 2 = full)
     * @param summaryEnabled Per-tab summary toggle
     * @param forceContextScope Force context selection (null/"method"/"class")
     * @return [GatherResult] with enriched bundle and metadata, or null if context
     *         was skipped (heuristic filter) or blocked (IDE indexing)
     */
    suspend fun gatherAndStage(
        userInput: String,
        scope: CoroutineScope,
        detectorLevel: Int = 2,
        summaryEnabled: Boolean? = null,
        forceContextScope: String? = null
    ): GatherResult? {

        // ── Heuristic pre-filter ────────────────────────────────────────
        val editorFile = currentEditorFile()
        val heuristicFilter = ContextHeuristicFilter()
        val heuristicEnabled = ContextSettingsState.getInstance(project).state.heuristicFilterEnabled
        if (heuristicEnabled && heuristicFilter.shouldSkipContext(userInput, editorFile)) {
            Dev.info(log, "gather.skipped_by_heuristic",
                "reason" to "heuristic_filter_active",
                "input_preview" to userInput.take(50)
            )
            return null
        }

        // ── Editor element resolution ───────────────────────────────────
        val resolvedContext = EditorElementResolver.resolve(project)

        Dev.info(log, "gather.resolved_editor",
            "file" to (resolvedContext?.file?.name ?: "none"),
            "elementAtCursor" to (resolvedContext?.elementAtCursor?.name ?: "none"),
            "elementKind" to (resolvedContext?.elementAtCursor?.kind?.name ?: "none"),
            "containingClass" to (resolvedContext?.containingClass?.name ?: "none"),
            "forceContextScope" to (forceContextScope ?: "none")
        )

        // ── IDE indexing check ──────────────────────────────────────────
        if (DumbService.isDumb(project)) {
            Dev.info(log, "gather.skipped", "reason" to "ide_indexing")
            return null
        }

        // ── Gather IDE context ──────────────────────────────────────────
        val (bundle, metrics) = gatherIdeContext(scope, buildDetectorsForLevel(detectorLevel))

        Dev.info(log, "gather.complete",
            "files" to bundle.files.size,
            "totalChars" to bundle.totalChars,
            "timeMs" to metrics.totalMillis,
            "detectorLevel" to detectorLevel
        )

        // ── Enrich with summaries ───────────────────────────────────────
        // CONTEXT vs SUMMARY — two independent features. Never conflate.
        // Context (bypassMode) = WHAT gets gathered. Already handled above.
        // Summary (summaryEnabled) = HOW COMPACT the files are.
        val enrichedBundle = if (summaryEnabled == false) {
            Dev.info(log, "gather.summary_skipped",
                "reason" to "summaryEnabled=false (per-tab toggle)"
            )
            bundle
        } else {
            enrichWithSummaries(bundle, resolvedContext)
        }

        // ── Build structured outputs ────────────────────────────────────
        val contextFiles = buildContextFileDetails(
            files = enrichedBundle.files,
            resolvedContext = resolvedContext,
            forceContextScope = forceContextScope
        )

        val contextBlock = buildContextBlock(
            bundle = enrichedBundle,
            resolvedContext = resolvedContext,
            forceContextScope = forceContextScope
        )

        return GatherResult(
            enrichedBundle = enrichedBundle,
            contextFiles = contextFiles,
            contextBlock = contextBlock,
            gatherTimeMs = metrics.totalMillis
        )
    }

    // ── Context Detection Heuristics ─────────────────────────────────────
    //
    // MOVED to ContextHeuristicFilter.kt (isolated, toggleable class).
    //
    // The heuristic methods (shouldGatherContext, isContextLikelyUseful,
    // refersToCurrentFile) and all their constants (CODE_FILE_EXTENSIONS,
    // ERROR_KEYWORDS, etc.) now live in ContextHeuristicFilter.
    //
    // Why: the heuristic was embedded in the pipeline and silently dropped
    // context for messages it didn't recognise as code-related. Extracting
    // it allows toggling on/off via settings without touching the pipeline.
    //
    // The filter is called as an optional first-pass in assemble() above.
    // buildCurrentFileBlock() below uses filter.refersToCurrentFile() to
    // decide whether to include the full file content in the prompt.
    //
    // @see ContextHeuristicFilter — the standalone filter class
    // @see ContextSettingsState.heuristicFilterEnabled — the toggle

    // ── IDE Context Gathering ────────────────────────────────────────────

    /**
     * Gather IDE context using the [ContextOrchestrator] detector pipeline.
     *
     * Accepts an explicit list of detectors so the SELECTIVE bypass path can
     * pass a filtered set without duplicating orchestration logic.
     *
     * @param scope Coroutine scope (cancellation propagates to detectors)
     * @param detectors Detectors to run. Defaults to all four for the normal path.
     * @return The context bundle and performance metrics
     */
    private suspend fun gatherIdeContext(
        scope: CoroutineScope,
        detectors: List<Detector> = buildDetectorsForLevel(2)
    ): Pair<ContextBundle, OrchestratorMetrics> {
        val registry = DetectorRegistry(detectors)
        val orchestrator = ContextOrchestrator(registry, Logger.getInstance(ContextAssembler::class.java))

        // Time budget for context gathering. If detectors take longer than this,
        // partial results are returned (whatever finished in time).
        val request = ContextRequest(project = project, maxMillis = MAX_CONTEXT_GATHER_MS)

        // ═══════════════════════════════════════════════════════════════════
        // STUB — Phase D.3: Traversal Radius & Infrastructure Visibility
        // ═══════════════════════════════════════════════════════════════════
        //
        // Two settings in ContextSettingsState that are defined but not yet read:
        //
        // 1. traversalRadius (Int, 1-5):
        //    Controls how many hops from the cursor position the context assembler
        //    reaches into the dependency graph.
        //    - radius=1: only the element at cursor + its immediate callers/callees
        //    - radius=2: their callers/callees too
        //    - radius=5: deep transitive dependency reach
        //    When implemented, this should be passed to RelevantFilesDetector to
        //    scope its file discovery. The IDE's reference search (PSI) provides
        //    the dependency graph — we do NOT build our own.
        //
        // 2. infrastructureVisibility ("OFF" | "BRIEF" | "DETAIL"):
        //    Controls how cross-cutting infrastructure classes (Auth, Logger,
        //    DI containers, Middleware) are included in context:
        //    - OFF: excluded entirely (saves tokens, focused context)
        //    - BRIEF: summary only (one-line description of what it does)
        //    - DETAIL: full content (for questions specifically about infrastructure)
        //    When implemented, this should filter the ContextBundle AFTER gathering
        //    and BEFORE enrichment. The filter reads the element's package/path to
        //    classify it as infrastructure or not.
        //
        // Both settings are per-project (global) with per-tab overrides in TabStateDto.
        //
        // READ from: ContextSettingsState.getInstance(project).state.traversalRadius
        //            ContextSettingsState.getInstance(project).state.infrastructureVisibility
        //
        // See: Context System — Complete UI & Behaviour Specification.md
        // See: Action Plan — Context System Implementation.md, Phase D.3
        // ═══════════════════════════════════════════════════════════════════

        return orchestrator.gather(request, scope)
    }

    /**
     * Build the detector list for a given SELECTIVE bypass level.
     *
     * Level 1 excludes [ProjectStructureDetector] (build system / module map)
     * because most per-file questions don't benefit from it.
     * Level 2 (and anything else) includes all four detectors — full pipeline.
     *
     * @param level 0 is handled by the caller (Minimal path). Only 1 and 2 arrive here.
     */
    private fun buildDetectorsForLevel(level: Int): List<Detector> = when (level) {
        1    -> listOf(LanguageDetector(), FrameworkDetector(), RelevantFilesDetector())
        else -> listOf(LanguageDetector(), FrameworkDetector(), ProjectStructureDetector(), RelevantFilesDetector())

        // ═══════════════════════════════════════════════════════════════════
        // STUB — Phase D.1: SELECTIVE Lever Extension Points
        // ═══════════════════════════════════════════════════════════════════
        //
        // The context lever (visible when Context Dial is set to "Custom") lets the
        // user select different context compositions. Each lever level corresponds
        // to a different combination of:
        //
        //   - Which detectors run (this method)
        //   - Traversal radius (how many dependency hops from cursor) — see D.3 stub
        //   - Infrastructure visibility (Auth, Logger, DI classes) — see D.3 stub
        //   - File scope (current file, current module, full project)
        //
        // The lever positions are NOT quantity-based — they represent different
        // context configurations tailored for different kinds of questions.
        //
        // When the lever is at its lowest position, it means "no context" — the lever
        // should disappear and the Context Dial should move to "Off". This is the
        // original behaviour restored: lowest lever = off.
        //
        // Lever levels CANNOT be defined until the backend capabilities are fully
        // understood (requires Phases A and B to be complete). Each new capability
        // (element-level scoping, traversal radius, infrastructure filtering) may
        // add or modify lever levels.
        //
        // See: Context System — Complete UI & Behaviour Specification.md, Section 3.4
        // See: Action Plan — Context System Implementation.md, Phase D.1
        // ═══════════════════════════════════════════════════════════════════
    }

    // ── Summary Enrichment (READ PATH) ──────────────────────────────────

    /**
     * Enrich a ContextBundle by replacing files with summaries where beneficial.
     *
     * ## Logic
     *
     * For EVERY file in the bundle (not just truncated ones):
     * 1. Check [SummaryStoreProvider] for an existing summary
     * 2. If summary exists AND is meaningfully shorter than raw (saves tokens) → use it
     * 3. If summary exists but file is tiny (summary doesn't save much) → keep raw
     * 4. If no summary exists AND summary service is enabled → suggest generation
     *    (fire-and-forget, gated by [SummaryConfigService] mode)
     * 5. If no summary exists AND summary service is disabled → keep raw
     *
     * ## Token Efficiency
     *
     * A summary is considered "beneficial" if its size (synopsis + headerSample) is
     * less than [SUMMARY_BENEFIT_RATIO] (50%) of the raw file size. Below that ratio,
     * the raw content is kept because the token savings are negligible and raw is
     * more accurate.
     *
     * ## Side Effects
     *
     * The only side effect is the generation suggestion (step 4), which is:
     * - Fire-and-forget (doesn't block context assembly)
     * - Gated by config (only fires when user has opted into automatic summarization)
     * - Defense-in-depth gated again inside [SummaryStoreProvider.suggestSummarization]
     *
     * The suggestion ensures summaries get built up over time, so they're available
     * on future requests. The user never sees this — they see the AI's response,
     * which next time will be informed by the summary instead of raw code.
     *
     * @param bundle The context bundle with all-RAW files from MergePolicy
     * @return A new bundle with files replaced by summaries where beneficial
     */
    private suspend fun enrichWithSummaries(
        bundle: ContextBundle,
        resolvedContext: ResolvedEditorContext? = null
    ): ContextBundle {
        if (bundle.files.isEmpty()) return bundle

        // ═══════════════════════════════════════════════════════════════════
        // STUB — Phase D.4: Module/Project-Level Demand-Driven Cascade (2.2)
        // ═══════════════════════════════════════════════════════════════════
        //
        // When the user asks about relationships between classes/files that
        // span a module boundary, the assembler would need to:
        //
        // 1. Detect that the question requires module-level understanding
        //    (heuristic or NLP analysis of user input — separate concern)
        //
        // 2. Trigger SummaryPipeline module-level cascade with CONFIRMATION GATE.
        //    Module/project summaries are large and expensive — they MUST require
        //    user confirmation before generation ("This summary covers N files
        //    across M modules. Estimated cost: ~X tokens. Proceed?")
        //
        // 3. Wait for all file summaries → module summary → project summary
        //    (bottom-up cascade, same as method → class but one level up)
        //
        // 4. Attach the module/project summary instead of individual file summaries
        //    (saves tokens when the question is about architecture, not a specific file)
        //
        // Requirements for implementation:
        // - Module boundary detection via IDE's project model (NOT regex or custom parsing)
        // - Confirmation UI in the frontend (modal or inline prompt)
        // - Integration with SummaryPipeline confirmation gate (templates exist)
        // - Budget check before generation (module summaries can be expensive)
        //
        // This is ON-DEMAND ONLY. Module/project summaries are NEVER generated
        // automatically in the background.
        //
        // See: Summarization — Agreed Direction.md, "Three-Step Execution Order"
        // See: Plugin Infrastructure Principles.md — use IDE's module system
        // See: Action Plan — Context System Implementation.md, Phase D.4
        // ═══════════════════════════════════════════════════════════════════

        val projectId = try {
            LocalStorageFacade.getInstance(project).resolveProjectId()
        } catch (_: Throwable) {
            project.basePath ?: project.name
        }

        // Batch fetch summaries for ALL file paths (not just truncated)
        val allPaths = bundle.files.map { it.path }
        val summaries = summaryStore.getSummaries(allPaths, projectId)

        // Determine if summary generation suggestions are allowed.
        // This is checked ONCE for the whole batch — avoids repeated config lookups.
        val suggestionsAllowed = isSummaryGenerationAllowed()

        Dev.info(log, "context.enrichment",
            "totalFiles" to allPaths.size,
            "summariesFound" to summaries.size,
            "suggestionsAllowed" to suggestionsAllowed
        )

        // Identify the current file path for element-level scoping.
        // If the user has a file open with cursor on a specific element, we try
        // to use the element's summary (method/class level) instead of the whole file.
        val currentFilePath = resolvedContext?.file?.path
        val elementAtCursor = resolvedContext?.elementAtCursor
        val containingClass = resolvedContext?.containingClass

        // Compute the current semantic hash for the element at cursor.
        // This is used to validate cached summaries: if the hash changed since
        // the summary was generated, the summary is stale and must not be used.
        // The hash is based on structural/behavioural properties (signature, control
        // flow, dependencies) — not cosmetic changes (variable names, whitespace).
        //
        // Validation rule: EVERY level, EVERY time, check before using.
        val currentElementHash: String? = if (resolvedContext?.file != null && elementAtCursor != null) {
            try {
                val provider = CodeStructureProviderFactory
                    .getInstance(project).get()
                provider?.computeElementHash(resolvedContext.file, elementAtCursor)
            } catch (e: Throwable) {
                Dev.warn(log, "context.enrichment.hash_failed", e,
                    "element" to elementAtCursor.name
                )
                null  // Can't validate — treat as stale, fall through to file-level
            }
        } else null

        // Rebuild the files list, replacing with summaries where beneficial
        var totalChars = 0
        var summariesUsed = 0
        var suggestionsQueued = 0
        var elementLevelUsed = 0

        val enrichedFiles = bundle.files.map { cf ->
            // ── Element-level enrichment for the current file ──
            // If this is the file the user has open AND we know what element they're
            // looking at, try to use the element-level summary instead of the file-level one.
            // This is much more precise: a method summary is typically 50-200 tokens vs
            // 500-5000 for a whole file summary.
            if (cf.path == currentFilePath && elementAtCursor != null) {
                val elementSummary = summaryStore.getElementSummary(
                    cf.path, elementAtCursor.signature, projectId, currentElementHash
                )

                if (elementSummary != null && !elementSummary.isStale) {
                    val synopsisChars = elementSummary.synopsis.length
                    if (synopsisChars < cf.charCount * SUMMARY_BENEFIT_RATIO) {
                        elementLevelUsed++
                        summariesUsed++
                        totalChars += synopsisChars

                        Dev.info(log, "context.enrichment.element_level",
                            "file" to cf.path,
                            "element" to elementAtCursor.name,
                            "elementKind" to elementAtCursor.kind.name,
                            "synopsisChars" to synopsisChars,
                            "rawChars" to cf.charCount
                        )

                        return@map cf.copy(
                            kind = ContextKind.SUMMARY,
                            charCount = synopsisChars,
                            truncated = false,
                            modelSynopsis = elementSummary.synopsis,
                            headerSample = elementSummary.headerSample,
                            isStale = false,
                            source = "element-summary"
                        )
                    }
                }

                // Element summary not available, stale, or not beneficial.
                // Attempt synchronous generation if config allows — the user asked NOW,
                // so the summary should be available NOW, not on the next request.
                //
                // This is the core of demand-driven summarization (Agreed Direction, Steps 2-3).
                // Only the element at cursor gets synchronous generation — other files in the
                // bundle continue with fire-and-forget suggestions (less critical, supporting context).
                val needsGeneration = elementSummary == null || elementSummary.isStale
                if (needsGeneration && suggestionsAllowed) {
                    Dev.info(log, "context.enrichment.element_sync_gen",
                        "file" to cf.path,
                        "element" to elementAtCursor.name,
                        "elementKind" to elementAtCursor.kind.name,
                        "reason" to if (elementSummary == null) "no_summary" else "stale"
                    )

                    // Determine if this is a method or class and call the appropriate generator
                    val parentName = elementAtCursor.parentName ?: ""
                    val syncSynopsis: String? = when (elementAtCursor.kind) {
                        CodeElementKind.METHOD,
                        CodeElementKind.FUNCTION,
                        CodeElementKind.CONSTRUCTOR -> {
                            summaryStore.generateMethodSummaryNow(cf.path, elementAtCursor, parentName)
                        }
                        CodeElementKind.CLASS,
                        CodeElementKind.INTERFACE,
                        CodeElementKind.OBJECT,
                        CodeElementKind.ENUM -> {
                            summaryStore.generateClassSummaryNow(cf.path, elementAtCursor)
                        }
                        else -> null  // Properties, OTHER — too small to warrant summarization
                    }

                    if (syncSynopsis != null) {
                        val synopsisChars = syncSynopsis.length
                        if (synopsisChars < cf.charCount * SUMMARY_BENEFIT_RATIO) {
                            elementLevelUsed++
                            summariesUsed++
                            totalChars += synopsisChars

                            Dev.info(log, "context.enrichment.element_sync_success",
                                "file" to cf.path,
                                "element" to elementAtCursor.name,
                                "synopsisChars" to synopsisChars,
                                "rawChars" to cf.charCount
                            )

                            return@map cf.copy(
                                kind = ContextKind.SUMMARY,
                                charCount = synopsisChars,
                                truncated = false,
                                modelSynopsis = syncSynopsis,
                                headerSample = null,
                                isStale = false,
                                source = "element-summary-sync"
                            )
                        }
                    }
                }

                // Sync generation failed or not allowed — fall through to file-level
                Dev.info(log, "context.enrichment.element_fallback",
                    "file" to cf.path,
                    "element" to elementAtCursor.name,
                    "reason" to when {
                        !needsGeneration -> "not_beneficial"
                        !suggestionsAllowed -> "config_denied"
                        else -> "generation_failed"
                    }
                )
            }

            // ── File-level enrichment (original path) ──
            val summary = summaries[cf.path]

            if (summary != null) {
                // Summary exists — check if it's worth using (saves tokens)
                val synopsisChars = (summary.headerSample?.length ?: 0) + summary.synopsis.length
                val rawChars = cf.charCount

                if (synopsisChars < rawChars * SUMMARY_BENEFIT_RATIO) {
                    // Summary is meaningfully shorter — use it
                    summariesUsed++
                    totalChars += synopsisChars
                    cf.copy(
                        kind = ContextKind.SUMMARY,
                        charCount = synopsisChars,
                        truncated = false,
                        headerSample = summary.headerSample,
                        modelSynopsis = summary.synopsis,
                        isStale = summary.isStale,
                        source = if (summary.isShared) "shared-summary" else "local-summary"
                    )
                } else {
                    // Summary doesn't save enough tokens — keep raw
                    totalChars += cf.charCount
                    cf
                }
            } else {
                // No summary exists — keep raw, optionally suggest generation
                totalChars += cf.charCount

                if (suggestionsAllowed) {
                    suggestGeneration(cf.path, projectId)
                    suggestionsQueued++
                }

                cf
            }
        }

        Dev.info(log, "context.enrichment.done",
            "summariesUsed" to summariesUsed,
            "elementLevelUsed" to elementLevelUsed,
            "suggestionsQueued" to suggestionsQueued,
            "rawKept" to (enrichedFiles.size - summariesUsed),
            "totalChars" to totalChars
        )

        return bundle.copy(
            files = enrichedFiles,
            totalChars = totalChars
        )
    }

    /**
     * Check whether summary generation suggestions are allowed by config.
     *
     * Generation suggestions are ONLY allowed when:
     * 1. The summary system is enabled (kill switch is on)
     * 2. The mode allows automatic generation (SMART_BACKGROUND or ON_DEMAND)
     *
     * If mode is OFF, no suggestions at all.
     * If mode is ON_DEMAND, suggestions are allowed because the user's chat message
     * IS the demand — they asked a code question, the system should prepare summaries.
     * If mode is SMART_BACKGROUND, suggestions are allowed as background optimization.
     *
     * @return true if suggestSummarization() calls are permitted
     */
    private fun isSummaryGenerationAllowed(): Boolean {
        val config = summaryConfig.getConfig()

        if (!config.enabled) return false

        return when (config.mode) {
            SummaryMode.OFF -> false
            SummaryMode.ON_DEMAND -> true
            SummaryMode.SMART_BACKGROUND -> true
            SummaryMode.SUMMARIZE_PATH -> false // path-based mode has its own trigger
        }
    }

    /**
     * Fire-and-forget suggestion to generate a summary for a file.
     *
     * This is a lightweight hint to the pipeline. It does NOT block context assembly.
     * The pipeline will evaluate the request against budget, scope, and other config
     * before deciding whether to actually generate.
     *
     * Defense-in-depth: [SummaryStoreProvider.suggestSummarization] has its own config
     * gate, so even if this method is called incorrectly, the pipeline won't run
     * unsanctioned work.
     *
     * @param filePath The file that would benefit from a summary
     * @param projectId Current project ID
     */
    private suspend fun suggestGeneration(filePath: String, projectId: String) {
        try {
            summaryStore.suggestSummarization(filePath, projectId)
        } catch (e: Throwable) {
            // Fire-and-forget — log but don't block context assembly
            Dev.warn(log, "context.suggest_failed", e, "filePath" to filePath)
        }
    }

    // ── Prompt Formatting ────────────────────────────────────────────────

    /**
     * Format a compact context note from a [ContextBundle] for the AI model.
     *
     * This gives the AI a quick overview of the project environment without
     * overwhelming it with details. Example output:
     *
     * ```
     * [Context]
     * Language: kotlin
     * Frameworks: Spring Boot 3.2, Kotlin Coroutines
     * Build: gradle
     * Modules: app, core, api
     * Files: 5 (truncated: 2) (~12400 chars)
     * - src/main/kotlin/Foo.kt
     * - src/main/kotlin/Bar.kt
     * ```
     *
     * @param bundle The gathered IDE context
     * @return Formatted context note string
     */
    private fun formatContextNote(bundle: ContextBundle): String {
        val lang = bundle.language?.languageId ?: "unknown"

        val frameworks = bundle.frameworks.joinToString(", ") { f ->
            if (f.version.isNullOrBlank()) f.name else "${f.name} ${f.version}"
        }.ifBlank { "none" }

        val build = bundle.projectStructure?.buildSystem ?: "unknown"
        val modules = bundle.projectStructure?.modules?.joinToString(", ")?.ifBlank { null }

        // File preview: show first 5 file paths as a quick reference
        val filesLine = if (bundle.files.isNotEmpty()) {
            val preview = bundle.files.take(5).joinToString("\n- ") { it.path }
            val truncNote = if (bundle.truncatedCount > 0) " (truncated: ${bundle.truncatedCount})" else ""
            "\nFiles: ${bundle.files.size}$truncNote (~${bundle.totalChars} chars)\n- $preview"
        } else ""

        val modulesLine = modules?.let { "\nModules: $it" } ?: ""

        return """
            [Context]
            Language: $lang
            Frameworks: $frameworks
            Build: $build$modulesLine$filesLine
        """.trimIndent()
    }

    /**
     * Format a file's content into a fenced prompt block.
     *
     * Shared by both the FULL bypass path and the normal deictic file inclusion.
     * Reads the file content with a safety cap to prevent sending enormous files.
     *
     * @param editorFile The file to format
     * @return Formatted file block, or empty string if reading failed
     */
    private fun formatFileBlock(editorFile: VirtualFile): String {
        val text = readFileTextCapped(editorFile) ?: return ""
        if (text.isBlank()) return ""

        val lang = fenceLangFor(editorFile)
        val path = editorFile.path.substringAfterLast('/')

        return """
            [File: $path]
            ```$lang
            ${text.trim()}
            ```
        """.trimIndent()
    }

    /**
     * Build the full content block for the currently open editor file.
     *
     * Only included when the user explicitly references "this file" (deictic phrasing).
     *
     * @param userInput The user's message (checked for deictic references)
     * @param editorFile The currently open file (null if nothing is open)
     * @return Formatted file block, or empty string if not applicable
     */
    private fun buildCurrentFileBlock(userInput: String, editorFile: VirtualFile?): String {
        // Uses ContextHeuristicFilter.refersToCurrentFile() to detect deictic phrasing
        // ("this file", "explain this file", etc.). This check runs regardless of whether
        // the heuristic filter is enabled — it controls full-file inclusion, not filtering.
        val filter = ContextHeuristicFilter()
        if (editorFile == null || !filter.refersToCurrentFile(userInput)) return ""
        return formatFileBlock(editorFile)
    }

    // ── File Reading Helpers ─────────────────────────────────────────────

    /**
     * Get the currently active file in the editor.
     *
     * Returns null if no file is open or the editor manager is unavailable.
     * This is the file the user is looking at when they send their message.
     */
    private fun currentEditorFile(): VirtualFile? {
        return try {
            FileEditorManager.getInstance(project).selectedFiles.firstOrNull()
        } catch (e: Exception) {
            // FileEditorManager might not be available in all contexts (e.g., during tests)
            Dev.warn(log, "context.editor_file_unavailable", e)
            null
        }
    }

    /**
     * Read file content with a safety cap, using IntelliJ's ReadAction for thread safety.
     *
     * Tries two approaches:
     * 1. Read from IntelliJ's document model (preferred — picks up unsaved changes)
     * 2. Fall back to reading the VirtualFile directly (for files not in the document model)
     *
     * The cap prevents accidentally sending a 10MB generated file to the AI.
     *
     * @param vf The file to read
     * @param maxChars Maximum characters to read (default 50K ≈ ~12K tokens)
     * @return The file content, or null if reading failed
     */
    private fun readFileTextCapped(vf: VirtualFile, maxChars: Int = MAX_FILE_CHARS): String? {
        return try {
            ReadAction.compute<String?, Throwable> {
                val fdm = FileDocumentManager.getInstance()
                val doc = fdm.getDocument(vf)

                if (doc != null) {
                    // Prefer the document model — it has unsaved changes
                    val seq = doc.charsSequence
                    val end = minOf(seq.length, maxChars)
                    seq.subSequence(0, end).toString()
                } else {
                    // Fall back to raw file content
                    if (!vf.isValid) return@compute null
                    val cs: Charset = vf.charset
                    vf.inputStream.use { input ->
                        val bytes = input.readNBytes(maxChars + 1)
                        val len = bytes.size.coerceAtMost(maxChars)
                        String(bytes, 0, len, cs)
                    }
                }
            }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Derive a code fence language identifier from a VirtualFile's type.
     *
     * Used when including file content in the prompt so the AI knows
     * what language the code is in. Returns empty string for unknown types
     * (the fence will still work, just without syntax hints).
     *
     * @param vf The file to get the language for
     * @return Language identifier (e.g., "kotlin", "java", "ts") or empty string
     */
    private fun fenceLangFor(vf: VirtualFile): String {
        val n = vf.fileType.name.lowercase()
        return when {
            n.contains("kotlin") -> "kotlin"
            n.contains("java") -> "java"
            n.contains("typescript") -> "ts"
            n.contains("javascript") -> "js"
            n.contains("json") -> "json"
            n.contains("xml") -> "xml"
            n.contains("yaml") || n.contains("yml") -> "yaml"
            n.contains("python") -> "python"
            n.contains("go") -> "go"
            n.contains("rust") -> "rust"
            n.contains("c++") || n.contains("cpp") -> "cpp"
            n.contains("c#") || n.contains("csharp") -> "csharp"
            else -> ""
        }
    }

    // ── ContextBlock Builder (Phase 1 — RequestBlocks) ────────────────────

    /**
     * Build a [ContextBlock] from an enriched [ContextBundle].
     *
     * Converts each [ContextFile] in the bundle into a [ContextEntry] and partitions
     * them into summaries, raw, and other lists. Non-file context (framework info,
     * project structure) is packaged into the "other" list.
     *
     * This method mirrors [buildContextFileDetails] — both consume the same data.
     * [buildContextFileDetails] produces badge metadata for the frontend tray.
     * This method produces the structured context block for [RequestBlocks].
     *
     * @param bundle The enriched context bundle (files may be RAW or SUMMARY)
     * @param resolvedContext The cursor context (for element-level entries)
     * @param forceContextScope The user's force context selection
     * @return Structured context block for the RequestBlocks model
     */
    private fun buildContextBlock(
        bundle: ContextBundle,
        resolvedContext: ResolvedEditorContext? = null,
        forceContextScope: String? = null
    ): ContextBlock {
        val currentFilePath = resolvedContext?.file?.path
        val elementAtCursor = resolvedContext?.elementAtCursor
        val now = Instant.now()

        val summaries = mutableListOf<ContextEntry>()
        val raw = mutableListOf<ContextEntry>()
        val other = mutableListOf<ContextEntry>()

        // Convert each file in the bundle to a ContextEntry
        for (cf in bundle.files) {
            val isCurrentFile = cf.path == currentFilePath && elementAtCursor != null
            val forced = forceContextScope != null && isCurrentFile

            // Build the content string for this entry — the actual text that goes into the prompt.
            // For SUMMARY entries: synopsis (+ optional header sample).
            // For RAW entries: the file content text.
            val content = when (cf.kind) {
                ContextKind.SUMMARY -> buildString {
                    if (!cf.headerSample.isNullOrBlank()) {
                        appendLine(cf.headerSample)
                        appendLine()
                    }
                    if (!cf.modelSynopsis.isNullOrBlank()) {
                        append(cf.modelSynopsis)
                    }
                }
                ContextKind.RAW -> {
                    // RAW file content is not stored directly on ContextFile — it's read
                    // from disk during filesSection() formatting. For the ContextEntry, we
                    // store a placeholder path reference. The actual content serialization
                    // happens in formatContextBlock() at send time.
                    //
                    // Phase 2 will populate this with the real content when the staging area
                    // manages entries with full content.
                    "[RAW: ${cf.path}]"
                }
            }

            val entryKind = when (cf.kind) {
                ContextKind.SUMMARY -> com.youmeandmyself.ai.chat.context.ContextKind.SUMMARY
                ContextKind.RAW -> com.youmeandmyself.ai.chat.context.ContextKind.RAW
            }

            val source = when {
                forced -> "forced"
                cf.source != null -> cf.source
                cf.kind == ContextKind.SUMMARY -> "auto-summary"
                else -> "auto"
            }

            val entry = ContextEntry(
                id = UUID.randomUUID().toString(),
                path = cf.path,
                name = cf.path.substringAfterLast("/"),
                content = content,
                kind = entryKind,
                contentHash = computeContentHash(content),
                tokenEstimate = cf.charCount / 4,
                source = source,
                gatheredAt = now,
                isStale = cf.isStale,
                elementSignature = if (isCurrentFile && elementAtCursor != null) {
                    elementAtCursor.signature
                } else null
            )

            when (entryKind) {
                com.youmeandmyself.ai.chat.context.ContextKind.SUMMARY -> summaries.add(entry)
                com.youmeandmyself.ai.chat.context.ContextKind.RAW -> raw.add(entry)
                com.youmeandmyself.ai.chat.context.ContextKind.OTHER -> other.add(entry)
            }
        }

        // Package non-file context (framework info, project structure) into "other"
        val contextNote = formatContextNote(bundle)
        if (contextNote.isNotBlank()) {
            other.add(ContextEntry(
                id = UUID.randomUUID().toString(),
                path = null,
                name = "project-context",
                content = contextNote,
                kind = com.youmeandmyself.ai.chat.context.ContextKind.OTHER,
                contentHash = computeContentHash(contextNote),
                tokenEstimate = contextNote.length / 4,
                source = "auto",
                gatheredAt = now,
                isStale = false
            ))
        }

        return ContextBlock(summaries = summaries, raw = raw, other = other)
    }

    /**
     * Build a single-entry [ContextBlock] for bypass paths (FULL bypass, SELECTIVE level 0).
     *
     * These paths produce a single content block for one file (the open editor file),
     * either as an existing summary or raw content. This wraps that into a ContextBlock.
     *
     * @param contentBlock The formatted content string (summary or raw file)
     * @param filePath The file path
     * @param fileName The file name
     * @param kind Whether this is a summary or raw content
     * @param isStale Whether the content is stale
     * @param forced Whether the user forced this context
     * @return Single-entry ContextBlock
     */
    private fun buildBypassContextBlock(
        contentBlock: String,
        filePath: String,
        fileName: String,
        kind: com.youmeandmyself.ai.chat.context.ContextKind,
        isStale: Boolean,
        forced: Boolean = false
    ): ContextBlock {
        if (contentBlock.isBlank()) return ContextBlock.empty()

        val entry = ContextEntry(
            id = UUID.randomUUID().toString(),
            path = filePath,
            name = fileName,
            content = contentBlock,
            kind = kind,
            contentHash = computeContentHash(contentBlock),
            tokenEstimate = contentBlock.length / 4,
            source = if (forced) "forced" else "auto",
            gatheredAt = Instant.now(),
            isStale = isStale
        )

        return when (kind) {
            com.youmeandmyself.ai.chat.context.ContextKind.SUMMARY -> ContextBlock(summaries = listOf(entry), raw = emptyList(), other = emptyList())
            com.youmeandmyself.ai.chat.context.ContextKind.RAW -> ContextBlock(summaries = emptyList(), raw = listOf(entry), other = emptyList())
            com.youmeandmyself.ai.chat.context.ContextKind.OTHER -> ContextBlock(summaries = emptyList(), raw = emptyList(), other = listOf(entry))
        }
    }

    /**
     * Compute a SHA-256 hash of content for deduplication and staleness detection.
     *
     * @param content The text to hash
     * @return Hex-encoded SHA-256 hash, or null if hashing fails
     */
    private fun computeContentHash(content: String): String? {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            val hashBytes = digest.digest(content.toByteArray(Charsets.UTF_8))
            hashBytes.joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            null
        }
    }

    // ── Badge Data Builder ────────────────────────────────────────────────

    /**
     * Build a list of [ContextFileDetail] from enriched context files.
     *
     * Each entry becomes a badge in the frontend tray. Extracts metadata
     * from the enriched [ContextFile] list and the optional [ResolvedEditorContext]
     * to determine scope (method/class/file), freshness, and forced status.
     *
     * @param files The enriched context files from the bundle
     * @param resolvedContext The cursor context (null if no element resolved)
     * @param forceContextScope The user's force context selection (null/"method"/"class")
     * @return Structured badge data for the frontend
     */
    private fun buildContextFileDetails(
        files: List<ContextFile>,
        resolvedContext: ResolvedEditorContext? = null,
        forceContextScope: String? = null
    ): List<ContextFileDetail> {
        val currentFilePath = resolvedContext?.file?.path
        val elementAtCursor = resolvedContext?.elementAtCursor

        return files.map { cf ->
            // Determine scope: if this is the cursor file and we have an element,
            // report the element's scope. Otherwise, it's file-level.
            val isCurrentFile = cf.path == currentFilePath && elementAtCursor != null
            val scope = if (isCurrentFile && (cf.source == "element-summary" || cf.source == "element-summary-sync")) {
                when (elementAtCursor?.kind) {
                    CodeElementKind.METHOD,
                    CodeElementKind.FUNCTION,
                    CodeElementKind.CONSTRUCTOR -> "method"
                    CodeElementKind.CLASS,
                    CodeElementKind.INTERFACE,
                    CodeElementKind.OBJECT,
                    CodeElementKind.ENUM -> "class"
                    else -> "file"
                }
            } else {
                "file"
            }

            // Determine freshness based on source and staleness
            val freshness = when {
                cf.isStale -> "rough"
                cf.kind == ContextKind.RAW -> "fresh"
                cf.source == "element-summary" || cf.source == "element-summary-sync" -> "cached"
                cf.source == "local-summary" -> "cached"
                cf.source == "shared-summary" -> "cached"
                else -> "fresh"
            }

            // Determine display name: element name for element-level, file name for file-level
            val name = if (isCurrentFile && (cf.source == "element-summary" || cf.source == "element-summary-sync") && elementAtCursor != null) {
                elementAtCursor.name
            } else {
                cf.path.substringAfterLast("/")
            }

            // Determine if this was forced by the user
            val forced = forceContextScope != null && isCurrentFile

            // Estimate tokens (~4 chars per token is a rough but standard heuristic)
            val tokens = cf.charCount / 4

            ContextFileDetail(
                path = cf.path,
                name = name,
                scope = scope,
                lang = cf.languageId ?: "",
                kind = if (cf.kind == ContextKind.SUMMARY) "SUMMARY" else "RAW",
                freshness = freshness,
                tokens = tokens,
                isStale = cf.isStale,
                forced = forced,
                elementSignature = if (isCurrentFile && elementAtCursor != null) {
                    elementAtCursor.signature
                } else null
            )
        }
    }

    // ── Constants ────────────────────────────────────────────────────────

    companion object {
        /**
         * Ratio threshold: use a summary only if its size is less than this fraction
         * of the raw file size. At 0.5, a summary must be less than half the raw size
         * to be worth using. Below this threshold, the raw content is more useful
         * because the token savings are negligible.
         */
        private const val SUMMARY_BENEFIT_RATIO = 0.5

        /**
         * Maximum time (ms) to spend gathering IDE context.
         * If detectors take longer, partial results are used.
         */
        private const val MAX_CONTEXT_GATHER_MS = 1400L

        /**
         * Maximum file content to read (characters).
         * ~50K chars ≈ ~12K tokens. Prevents sending huge files to the AI.
         */
        private const val MAX_FILE_CHARS = 50_000

        // ── Heuristic Constants (MOVED) ──────────────────────────────
        //
        // All heuristic keyword lists and regex patterns have been moved
        // to ContextHeuristicFilter.kt (companion object).
        //
        // Previously here: CODE_FILE_EXTENSIONS, GENERIC_ANALYSIS_WORDS,
        // ERROR_KEYWORDS, FILE_EXTENSION_HINTS, FILE_PATH_REGEX,
        // CODE_KEYWORDS, GREETINGS, DEICTIC_FILE_PHRASES.
        //
        // @see ContextHeuristicFilter — now owns all heuristic constants
    }
}

/**
 * Result of prompt assembly.
 *
 * Contains the final prompt string and metadata about what context was included.
 * The orchestrator uses [effectivePrompt] for the AI call and the metadata
 * fields for the [ChatResult] it returns to the UI.
 *
 * @property effectivePrompt The complete prompt to send to the AI provider.
 *                           May be just the user's raw input (no context) or
 *                           the user's input enriched with context sections.
 * @property contextSummary Human-readable summary of attached context (e.g., "[Context: 3 files...]").
 *                          Null if no context was gathered. Shown to the user as a system message.
 * @property contextTimeMs How long context gathering took. Null if no context was gathered.
 * @property isBlockedByIndexing True if context was needed but IDE indexing prevented gathering.
 *                               The orchestrator should show an error message in this case.
 */
data class AssembledPrompt(
    val effectivePrompt: String,
    val contextSummary: String?,
    val contextTimeMs: Long?,
    val isBlockedByIndexing: Boolean = false,

    /**
     * Structured metadata about each piece of context attached to this request.
     *
     * ## Purpose
     *
     * Feeds the badge tray in the frontend — each entry becomes a badge
     * the user can hover/inspect. Also feeds the sidebar (conversation-level
     * audit trail of all context attached across messages).
     *
     * ## Data flow
     *
     * AssembledPrompt.contextFiles → ChatResultEvent.contextFiles → React ContextBadgeTray
     *
     * ## Phase D.2 — Stub
     *
     * Currently always emptyList(). Populated in Phase A.3 once element-level
     * context scoping is implemented.
     *
     * @see ContextFileDetail
     */
    val contextFiles: List<ContextFileDetail> = emptyList(),

    /**
     * Structured context block for the RequestBlocks model.
     *
     * ## Phase 1
     *
     * Populated alongside [contextFiles] from the same gathered context data.
     * Each [ContextEntry] carries the full content text that would be injected
     * into the prompt, plus metadata for tracking, deduplication, and staleness.
     *
     * ## Data flow
     *
     * AssembledPrompt.contextBlock → ChatOrchestrator → RequestBlocks.context →
     * GenericLlmProvider (serialized into the API message)
     *
     * @see ContextBlock
     * @see com.youmeandmyself.ai.chat.orchestrator.RequestBlocks
     */
    val contextBlock: ContextBlock = ContextBlock.empty()
) {
    companion object {
        /**
         * Factory for the case where context is needed but IDE indexing blocks it.
         *
         * The orchestrator checks [isBlockedByIndexing] and returns an error ChatResult
         * asking the user to wait for indexing to finish.
         */
        fun indexingBlocked(): AssembledPrompt = AssembledPrompt(
            effectivePrompt = "",
            contextSummary = null,
            contextTimeMs = null,
            isBlockedByIndexing = true
        )
    }
}

/**
 * Metadata about a single piece of context attached to a request.
 *
 * ## Purpose
 *
 * Each instance represents one context entry (a method, class, file, or config)
 * that was included in the prompt. The frontend displays these as badges in the
 * badge tray (below the prompt) and in the sidebar (conversation audit trail).
 *
 * ## Why structured, not just a string
 *
 * The frontend needs to:
 * - Show scope icons (method/class/file)
 * - Color-code freshness (fresh/cached/rough)
 * - Display token estimates
 * - Distinguish forced vs automatic context
 * - Show stale warnings
 *
 * A single summary string can't provide this. Structured data can.
 *
 * ## Lifecycle
 *
 * Created during context assembly → serialized to JSON in ChatResultEvent →
 * deserialized in React → rendered as badges → migrated to sidebar on Send.
 *
 * @param path Absolute file path
 * @param name Display name (file name, method name, class name)
 * @param scope Granularity: "method", "class", "file", "module", "config"
 * @param lang Programming language (e.g., "kotlin", "java", "python")
 * @param kind Whether this is "RAW" (full code) or "SUMMARY" (compressed)
 * @param freshness Summary freshness: "fresh" (just generated), "cached" (from store), "rough" (stale but usable)
 * @param tokens Estimated token count for this entry
 * @param isStale Whether the summary is outdated (source changed since last summarization)
 * @param forced Whether the user explicitly forced this via the Force Context button
 * @param elementSignature PSI element signature (null for file-level entries).
 *                         Used for element-level cache lookups and invalidation.
 */
@kotlinx.serialization.Serializable
data class ContextFileDetail(
    val path: String,
    val name: String,
    val scope: String,
    val lang: String,
    val kind: String,
    val freshness: String,
    val tokens: Int,
    val isStale: Boolean,
    val forced: Boolean = false,
    val elementSignature: String? = null
)

/**
 * Result of the gathering + enrichment phase.
 *
 * ## Phase 2 Split
 *
 * Returned by [ContextAssembler.gatherAndStage]. Contains the enriched
 * context bundle and all structured outputs needed for prompt formatting
 * and RequestBlocks construction.
 *
 * The caller (currently [ContextAssembler.assemble], later [ContextStagingService])
 * uses this to build the prompt string and populate [RequestBlocks.context].
 *
 * @property enrichedBundle The context bundle after summary enrichment. Contains
 *   file metadata, language, frameworks, project structure.
 * @property contextFiles Badge metadata for the frontend tray.
 * @property contextBlock Structured context for [RequestBlocks].
 * @property gatherTimeMs How long the gathering took in milliseconds.
 */
data class GatherResult(
    val enrichedBundle: ContextBundle,
    val contextFiles: List<ContextFileDetail>,
    val contextBlock: ContextBlock,
    val gatherTimeMs: Long
)

// ── Extension Functions ─────────────────────────────────────────────────
//
// These format ContextBundle data into prompt sections.
// Extracted as extensions to keep ContextAssembler focused on orchestration.

/**
 * One-line summary of the context bundle for display.
 *
 * Example: "[Context: 5 files (3 raw, 2 summaries), ~12400 chars]"
 */
internal fun ContextBundle.manifestLine(): String {
    val raw = files.count { it.kind == ContextKind.RAW }
    val sum = files.count { it.kind == ContextKind.SUMMARY }
    return "[Context: ${files.size} files ($raw raw, $sum summaries), ~${totalChars} chars]"
}

/**
 * Detailed files section for the prompt.
 *
 * Each file gets a fenced block with metadata (path, language, kind, reason)
 * and content. Summaries include the synopsis and optional header sample.
 *
 * The AI uses this structured format to understand which files are raw content
 * vs. summaries, which are stale, and why each file was included.
 */
internal fun ContextBundle.filesSection(): String = buildString {
    appendLine("### Files")
    files.forEach { cf ->
        appendLine()
        appendLine("```text")
        appendLine("// path: ${cf.path} | lang: ${cf.languageId ?: "unknown"} | why: ${cf.reason}")
        when (cf.kind) {
            ContextKind.RAW -> {
                appendLine("// kind: RAW${if (cf.truncated) " (truncated)" else ""}")
            }
            ContextKind.SUMMARY -> {
                val status = when {
                    cf.modelSynopsis.isNullOrBlank() -> " (synopsis pending)"
                    cf.isStale -> " (synopsis may be outdated)"
                    else -> ""
                }
                appendLine("// kind: SUMMARY$status")
                if (!cf.headerSample.isNullOrBlank()) {
                    appendLine("[HEADER SAMPLE]")
                    appendLine(cf.headerSample)
                }
                if (!cf.modelSynopsis.isNullOrBlank()) {
                    appendLine()
                    appendLine("[SYNOPSIS]")
                    appendLine(cf.modelSynopsis)
                }
            }
        }
        appendLine("```")
    }
}