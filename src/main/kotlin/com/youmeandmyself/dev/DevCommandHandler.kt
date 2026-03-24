package com.youmeandmyself.dev

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.youmeandmyself.ai.providers.parsing.ui.CorrectionFlowHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import com.youmeandmyself.summary.config.SummaryConfigService
import com.youmeandmyself.summary.pipeline.SummaryPipeline
import com.youmeandmyself.summary.model.CodeElementKind
import com.youmeandmyself.summary.structure.CodeStructureProviderFactory
import com.youmeandmyself.summary.structure.DetectionScope
import com.youmeandmyself.summary.structure.ElementLevel
import com.youmeandmyself.summary.cache.SummaryCache
import com.youmeandmyself.storage.LocalStorageFacade
import com.youmeandmyself.storage.model.AiExchange
import com.youmeandmyself.storage.model.ExchangePurpose
import com.youmeandmyself.storage.model.ExchangeRawResponse
import com.youmeandmyself.storage.model.ExchangeRequest
import com.youmeandmyself.storage.model.ExchangeTokenUsage
import com.youmeandmyself.storage.model.IdeContextCapture

/**
 * Handles dev-only test commands for exercising the correction flow,
 * summary pipeline, git detection, and IDE context capture.
 *
 * Only active when dev mode is enabled (-Dymm.devMode=true).
 *
 * ## R6 Status — Output Channel Disconnected
 *
 * This class previously rendered output through ChatUIService (legacy chat UI).
 * That dependency was removed in R6 when the legacy chat was deleted.
 *
 * Output currently goes to the IDE log via Dev.info(). To reconnect:
 *
 * **Option A — React bridge:** Add a DEV_OUTPUT bridge event. DevCommandHandler
 * sends events through BridgeDispatcher, React renders them as system messages.
 * This is the proper solution — dev output appears in the chat alongside normal messages.
 *
 * **Option B — Tool window:** Create a dedicated "Dev Console" tab in the tool
 * window that renders dev output independently of the chat.
 *
 * **To rewire:** Replace all `output(...)` calls with the chosen channel.
 * Every dev command's logic is fully preserved — only the rendering changed.
 *
 * ## How This Was Called (Legacy)
 *
 * ChatPanel called `handleIfDevCommand(userInput)` before processing normal messages.
 * The React chat does NOT currently route to this class. When rewiring, add a
 * DEV_COMMAND bridge command in BridgeDispatcher that calls handleIfDevCommand().
 *
 * ## Available Commands
 *
 * - /dev-help: Show available commands
 * - /dev-status: Show dev mode status
 * - /dev-scenario1: Test known format (no correction UI)
 * - /dev-scenario2: Test heuristic with correction option
 * - /dev-scenario3: Test low confidence (immediate dialog)
 * - /dev-error: Test error response
 * - /dev-summary-test: Dry-run summary pipeline on current file
 * - /dev-summary-mock: Write mock FILE_SUMMARY through full save pipeline
 * - /dev-summarize: Request on-demand summary of current file
 * - /dev-summary-status: Show summary system status
 * - /dev-summary-stop: Emergency stop — cancel all queued summaries
 * - /dev-git-test: Test git branch detection step by step
 * - /dev-context-test: Test full IDE context capture
 * - /dev-structure-inspect: Inspect current file with PSI (classes, methods, hashes, context, cache)
 * - /dev-structure-inspect ClassName: Inspect a specific class and its methods
 * - /dev-summary-health: Health check for element summary system (cache vs SQLite consistency)
 */
class DevCommandHandler(
    private val project: Project,
    private val correctionHelper: CorrectionFlowHelper,
    private val scope: CoroutineScope,
    private val sendEvent: ((String) -> Unit)? = null
) {
    private val log = Dev.logger(DevCommandHandler::class.java)

    /**
     * Temporary output function — sends dev command output to IDE log.
     *
     * PLACEHOLDER: Replace with bridge event / React system message / dev console
     * when the output channel is reconnected. All dev commands call this instead
     * of the old chatService.addSystemMessage().
     *
     * @param message The dev output text to display
     */
    private fun output(message: String) {
        Dev.info(log, "dev.output", "message" to message)

        // Send through bridge if wired
        sendEvent?.let { send ->
            try {
                val event = com.youmeandmyself.ai.chat.bridge.BridgeMessage.DevOutputEvent(
                    content = message
                )
                val json = com.youmeandmyself.ai.chat.bridge.BridgeMessage.serializeEvent(event)
                send(json)
            } catch (e: Exception) {
                Dev.warn(log, "dev.output.bridge_failed", e)
            }
        }
    }

    /**
     * Check if the input is a dev command and handle it.
     *
     * @param input The user's input text
     * @return true if it was a dev command (handled), false otherwise (continue normal flow)
     */
    fun handleIfDevCommand(input: String): Boolean {
        if (!input.lowercase().startsWith("/dev")) {
            return false
        }

        if (!DevMode.isEnabled()) {
            return false
        }

        val command = input.lowercase().trim()

        when {
            command == "/dev-help" || command == "/dev" -> showHelp()
            command == "/dev-status" -> showStatus()
            command == "/dev-scenario1" -> runScenario1()
            command == "/dev-scenario2" -> runScenario2()
            command == "/dev-scenario3" -> runScenario3()
            command == "/dev-error" -> runErrorScenario()
            command == "/dev-summary-test" -> runSummaryTest()
            command == "/dev-summarize" -> runSummarize()
            command == "/dev-summary-status" -> runSummaryStatus()
            command == "/dev-summary-stop" -> runSummaryStop()
            command == "/dev-git-test" -> runGitTest()
            command == "/dev-context-test" -> runContextTest()
            command == "/dev-summary-mock" -> runMockSummary()
            command == "/dev-structure-inspect" -> runPsiInspect()
            command.startsWith("/dev-structure-inspect ") -> runPsiInspect(command.removePrefix("/dev-structure-inspect ").trim())
            command == "/dev-summary-health" -> runSummaryHealth()
            // Context gathering dry-run commands (no AI calls, no tokens)
            command == "/dev-context-single" -> runContextDry("single")
            command == "/dev-context-radius" -> runContextDry("radius")
            command == "/dev-context-chain" -> runContextDry("chain")
            else -> {
                output("Unknown dev command: $input\nType /dev-help to see available commands.")
            }
        }

        return true
    }

    private fun showHelp() {
        output(DevMode.helpText())
    }

    private fun showStatus() {
        val status = buildString {
            appendLine("🔧 YMM Dev Mode Status")
            appendLine()
            appendLine("  Enabled: ${DevMode.isEnabled()}")
            appendLine("  Property: -Dymm.devMode=${System.getProperty("ymm.devMode") ?: "(not set)"}")
            appendLine()
            appendLine("  Correction helper has context: ${correctionHelper.hasCorrectableResponse()}")
        }
        output(status)
    }

    // ══════════════════════════════════════════════════════════════════════
    //  CORRECTION FLOW TEST SCENARIOS
    //
    //  These test the response parsing + correction dialog pipeline.
    //  They use TestResponseFactory to create fake AI responses with
    //  known characteristics, then run them through CorrectionFlowHelper.
    //
    //  R6 NOTE: processTestResponse() previously rendered results through
    //  ChatUIService. The logic is preserved below but the rendering calls
    //  now go through output(). The correction flow itself still works —
    //  CorrectionFlowHelper is independent of the UI layer.
    // ══════════════════════════════════════════════════════════════════════

    private fun runScenario1() {
        output("🧪 Running Scenario 1: Known Format Test")
        val testResponse = TestResponseFactory.scenario1_KnownFormat()
        processTestResponse(testResponse, expectCorrectionUI = false)
    }

    private fun runScenario2() {
        output("🧪 Running Scenario 2: Heuristic + Confident Test")
        val testResponse = TestResponseFactory.scenario2_HeuristicConfident()
        processTestResponse(testResponse, expectCorrectionUI = true)
    }

    private fun runScenario3() {
        output("🧪 Running Scenario 3: Low Confidence Test\n⚠️ A dialog should appear - select a response option.")
        val testResponse = TestResponseFactory.scenario3_LowConfidence()
        processTestResponse(testResponse, expectCorrectionUI = true)
    }

    private fun runErrorScenario() {
        output("🧪 Running Error Response Test")
        val testResponse = TestResponseFactory.errorResponse()
        output("Error response display text: ${testResponse.parsedResponse.displayText}")
    }

    private fun processTestResponse(testResponse: TestResponse, expectCorrectionUI: Boolean) {
        val result = testResponse.parsedResponse
        correctionHelper.clearCorrectionContext()

        scope.launch {
            val finalDisplayText: String

            when {
                correctionHelper.shouldAskImmediately(result) && result.metadata.candidates.isNotEmpty() -> {
                    val corrected = correctionHelper.handleImmediateCorrection(
                        result = result,
                        providerId = testResponse.providerId,
                        modelId = testResponse.modelId
                    )

                    if (corrected != null) {
                        finalDisplayText = corrected.displayText
                    } else {
                        finalDisplayText = result.displayText
                        storeForCorrectionWithRawJson(testResponse)
                    }
                }

                correctionHelper.shouldOfferPostCorrection(result) -> {
                    finalDisplayText = result.displayText
                    storeForCorrectionWithRawJson(testResponse)
                }

                else -> {
                    finalDisplayText = result.displayText
                }
            }

            output("Response: $finalDisplayText")

            if (correctionHelper.hasCorrectableResponse()) {
                output("ℹ️ Response auto-detected. Correction context stored.")
            }

            if (expectCorrectionUI && correctionHelper.hasCorrectableResponse()) {
                output("✅ Test passed: Correction context stored.")
            } else if (!expectCorrectionUI && !correctionHelper.hasCorrectableResponse()) {
                output("✅ Test passed: No correction UI (as expected for known format)")
            }
        }
    }

    private fun storeForCorrectionWithRawJson(testResponse: TestResponse) {
        correctionHelper.storeForPostCorrection(
            result = testResponse.parsedResponse,
            providerId = testResponse.providerId,
            modelId = testResponse.modelId,
            force = true
        )
        correctionHelper.storeTestRawJson(testResponse.rawJson)
    }

    // ══════════════════════════════════════════════════════════════════════
    //  SUMMARY PIPELINE COMMANDS
    //
    //  These exercise the summarization config, scope evaluation,
    //  dry-run mode, and the full save pipeline (for mock summaries).
    //  All logic preserved — only output channel changed.
    // ══════════════════════════════════════════════════════════════════════

    private fun runSummaryTest() {
        output("🧪 Running Summary Pipeline Test (dry-run)")

        val configService = SummaryConfigService.getInstance(project)
        val config = configService.getConfig()

        val configStatus = buildString {
            appendLine("📋 Current Summary Config:")
            appendLine("  Mode: ${config.mode.displayName}")
            appendLine("  Enabled (kill switch): ${config.enabled}")
            appendLine("  Dry-run: ${config.dryRun}")
            appendLine("  Budget: ${config.maxTokensPerSession?.let { "$it tokens/session" } ?: "unlimited"}")
            appendLine("  Tokens used this session: ${config.tokensUsedSession}")
            appendLine("  Min file lines: ${config.minFileLines ?: "none"}")
            appendLine("  Complexity threshold: ${config.complexityThreshold ?: "none"}")
            appendLine("  Include patterns: ${config.includePatterns.ifEmpty { listOf("(all)") }}")
            appendLine("  Exclude patterns: ${config.excludePatterns.ifEmpty { listOf("(none)") }}")
        }
        output(configStatus)

        val editor = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).selectedTextEditor
        val virtualFile = editor?.virtualFile

        if (virtualFile == null) {
            output("⚠️ No file open in editor. Open a file and run /dev-summary-test again.")
            return
        }

        val filePath = virtualFile.path
        val lineCount = editor.document.lineCount
        val languageId = virtualFile.fileType?.name

        output("📄 Testing against: ${virtualFile.name} ($lineCount lines, language: $languageId)")

        val scopeDecision = configService.shouldSummarize(filePath, lineCount)
        val scopeIcon = if (scopeDecision.allowed) "✅" else "❌"
        output("$scopeIcon Scope decision: ${scopeDecision.reason}")

        val dryRun = configService.evaluateDryRun(filePath, lineCount)
        val dryRunStatus = buildString {
            appendLine("🔍 Dry-run evaluation:")
            appendLine("  Would summarize: ${dryRun.wouldSummarize}")
            appendLine("  Reason: ${dryRun.reason}")
            appendLine("  Estimated tokens: ${dryRun.estimatedTokens ?: "unknown"}")
            appendLine("  Budget remaining: ${dryRun.budgetRemaining?.let { "$it tokens" } ?: "unlimited"}")
            appendLine("  Provider: ${dryRun.providerInfo}")
            if (dryRun.matchedPattern != null) {
                appendLine("  Matched pattern: ${dryRun.matchedPattern}")
            }
        }
        output(dryRunStatus)

        val verdict = if (dryRun.wouldSummarize) {
            "✅ This file WOULD be summarized (dry-run prevented the API call)"
        } else {
            "⛔ This file would NOT be summarized: ${dryRun.reason}"
        }
        output(verdict)
    }

    private fun runMockSummary() {
        val editor = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).selectedTextEditor
        val virtualFile = editor?.virtualFile

        if (virtualFile == null) {
            output("⚠️ No file open in editor. Open a file and run /dev-summary-mock again.")
            return
        }

        output("🧪 Writing mock FILE_SUMMARY for: ${virtualFile.name}")

        val facade = LocalStorageFacade.getInstance(project)
        val projectId = facade.resolveProjectId()
        val exchangeId = java.util.UUID.randomUUID().toString()
        val now = java.time.Instant.now()

        val mockExchange = AiExchange(
            id = exchangeId,
            timestamp = now,
            providerId = "dev-mock",
            modelId = "dev-mock-model",
            purpose = ExchangePurpose.FILE_SUMMARY,
            request = ExchangeRequest(
                input = "Summarize: ${virtualFile.path}"
            ),
            rawResponse = ExchangeRawResponse(
                json = """{"mock": true, "summary": "Mock summary of ${virtualFile.name} generated by /dev-summary-mock at $now"}""",
                httpStatus = 200
            ),
            tokenUsage = ExchangeTokenUsage(
                promptTokens = 100,
                completionTokens = 50,
                totalTokens = 150
            )
        )

        kotlinx.coroutines.runBlocking {
            val savedId = facade.saveExchange(mockExchange, projectId)
            if (savedId != null) {
                output("✅ Mock FILE_SUMMARY saved (id: ${savedId.take(8)}…). Check ~/YouMeAndMyself/summaries/ for the JSONL file.")

                val config = facade.getStorageConfig()
                val summariesDir = config.summariesDirForProject(projectId)
                val chatDir = config.chatDirForProject(projectId)

                val inSummaries = summariesDir.listFiles()?.any { file ->
                    file.useLines { lines -> lines.any { it.contains(savedId) } }
                } ?: false

                val inChat = chatDir.listFiles()?.any { file ->
                    file.useLines { lines -> lines.any { it.contains(savedId) } }
                } ?: false

                val routingResult = when {
                    inSummaries && !inChat -> "✅ ROUTING OK — found in summaries/, not in chat/"
                    inChat && !inSummaries -> "❌ ROUTING BUG — found in chat/, not in summaries/"
                    inSummaries && inChat -> "⚠️ Found in BOTH folders — unexpected"
                    else -> "⚠️ Not found in either folder — check logs"
                }
                output(routingResult)

                facade.withDatabase { db ->
                    db.execute("DELETE FROM chat_exchanges WHERE id = ?", savedId)
                }

                val targetDir = if (inSummaries) summariesDir else chatDir
                targetDir.listFiles()?.forEach { file ->
                    if (file.extension == "jsonl") {
                        val lines = file.readLines()
                        val filtered = lines.filter { !it.contains(savedId) }
                        if (filtered.size < lines.size) {
                            file.writeText(filtered.joinToString("\n") + if (filtered.isNotEmpty()) "\n" else "")
                        }
                    }
                }

                output("🧹 Cleanup complete — mock exchange removed from SQLite and JSONL.")
            } else {
                output("❌ saveExchange returned null — check logs.")
            }
        }
    }

    private fun runSummarize() {
        val editor = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).selectedTextEditor
        val virtualFile = editor?.virtualFile

        if (virtualFile == null) {
            output("⚠️ No file open in editor. Open a file and run /dev-summarize again.")
            return
        }

        val configService = SummaryConfigService.getInstance(project)

        if (!configService.isEnabled()) {
            output("⛔ Summarization is disabled. Enable it in Settings → Tools → YMM Assistant → Summary.")
            return
        }

        if (configService.isDryRun()) {
            val lineCount = editor.document.lineCount
            val dryRun = configService.evaluateDryRun(virtualFile.path, lineCount)
            output(
                "🔍 Dry-run mode: would summarize ${virtualFile.name} " +
                        "(~${dryRun.estimatedTokens} tokens, provider: ${dryRun.providerInfo}). " +
                        "Turn off dry-run in Settings to actually summarize."
            )
            return
        }

        val pipeline = SummaryPipeline.getInstance(project)
        val contentHash = try {
            java.nio.file.Files.readString(java.nio.file.Path.of(virtualFile.path)).hashCode().toString()
        } catch (_: Throwable) { null }

        val enqueued = pipeline.requestSummary(
            path = virtualFile.path,
            languageId = virtualFile.fileType?.name,
            currentContentHash = contentHash
        )

        if (enqueued) {
            output("📝 Summarizing ${virtualFile.name}... Running in the background.")
        } else {
            output("⚠️ Could not enqueue ${virtualFile.name}. Check scope/budget settings.")
        }
    }

    private fun runSummaryStatus() {
        val configService = SummaryConfigService.getInstance(project)
        val pipeline = SummaryPipeline.getInstance(project)
        val config = configService.getConfig()

        val status = buildString {
            appendLine("📊 Summary Status:")
            appendLine("  Enabled: ${config.enabled}")
            appendLine("  Mode: ${config.mode.displayName}")
            appendLine("  Dry-run: ${config.dryRun}")
            appendLine("  Tokens used this session: ${config.tokensUsedSession}")
            appendLine("  Budget remaining: ${config.remainingBudget?.let { "$it tokens" } ?: "unlimited"}")
            appendLine("  Files queued: ${pipeline.queue.size()}")
            val pending = pipeline.queue.pendingPaths()
            if (pending.isNotEmpty()) {
                appendLine("  Queued files:")
                pending.take(10).forEach { appendLine("    • $it") }
                if (pending.size > 10) appendLine("    ... and ${pending.size - 10} more")
            }
        }
        output(status)
    }

    private fun runSummaryStop() {
        val configService = SummaryConfigService.getInstance(project)
        val pipeline = SummaryPipeline.getInstance(project)
        val cancelled = pipeline.queue.cancelAll()
        configService.setEnabled(false)

        output(
            "🛑 Summarization stopped. Cancelled $cancelled queued items. Kill switch is now OFF. " +
                    "Re-enable in Settings → Tools → YMM Assistant → Summary."
        )
    }

    // ══════════════════════════════════════════════════════════════════════
    //  PSI STRUCTURE INSPECTION
    //
    //  Tests the PSI-based code structure detection that replaced regex.
    //  Runs against the currently open file, showing:
    //  - What classes and methods PSI detects
    //  - Semantic fingerprint hashes for each element
    //  - Structural context for each element
    //  - Cache state for each element (if any summaries exist)
    //
    //  Usage:
    //    /dev-structure-inspect           → inspect entire file (all elements)
    //    /dev-structure-inspect ClassName → inspect one class and its methods
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Inspect the current file using PSI-based code structure detection.
     *
     * Shows detection results, semantic hashes, structural context, and
     * cache state for each detected element. Useful for verifying PSI
     * integration is working correctly without triggering AI calls.
     *
     * @param filterClass Optional class name to inspect just that class and its methods.
     *                    If null, inspects all elements in the file.
     */
    private fun runPsiInspect(filterClass: String? = null) {
        // Get the currently open file
        val editor = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).selectedTextEditor
        val virtualFile = editor?.virtualFile

        if (virtualFile == null) {
            output("⚠️ No file open in editor. Open a file and run /dev-structure-inspect again.")
            return
        }

        val languageId = virtualFile.fileType?.name
        val filePath = virtualFile.path

        output("🔍 PSI Inspect: ${virtualFile.name} (language: $languageId)")
        output("─".repeat(60))

        // Step 1: Check if the factory can provide a provider
        val factory = CodeStructureProviderFactory.getInstance(project)
        val provider = factory.get()

        if (provider == null) {
            val reason = if (com.intellij.openapi.project.DumbService.isDumb(project)) {
                "IDE is in dumb mode (indexing). PSI is not available yet. Wait for indexing to finish."
            } else {
                "No Structure View available for this file. The IDE may not support this language."
            }
            output("⛔ Provider: null — $reason")
            return
        }

        output("✅ Provider: PsiCodeStructureProvider (PSI available)")
        output("")

        // Step 2: Determine detection scope
        val scope = if (filterClass != null) {
            output("🔎 Scope: SingleClass('$filterClass', includeMethods=true)")
            DetectionScope.SingleClass(filterClass, includeMethods = true)
        } else {
            output("🔎 Scope: All (classes + methods + top-level functions)")
            DetectionScope.All
        }
        output("")

        // Step 3: Detect elements
        val elements = provider.detectElements(virtualFile, scope)

        if (elements.isEmpty()) {
            output("⚠️ No elements detected.")
            if (filterClass != null) {
                output("   Did you spell the class name correctly? Try /dev-structure-inspect without a class name to see all elements.")
            }
            return
        }

        output("📦 Detected ${elements.size} element(s):")
        output("")

        // Step 4: Get cache for state checking
        val cache = SummaryCache.getInstance(project)

        // Step 5: Display each element
        for ((index, element) in elements.withIndex()) {
            val kindIcon = when (element.kind) {
                CodeElementKind.CLASS -> "📦"
                CodeElementKind.INTERFACE -> "📐"
                CodeElementKind.OBJECT -> "🔹"
                CodeElementKind.ENUM -> "📋"
                CodeElementKind.METHOD -> "  🔧"
                CodeElementKind.FUNCTION -> "  ⚡"
                CodeElementKind.PROPERTY -> "  📎"
                CodeElementKind.CONSTRUCTOR -> "  🔨"
                CodeElementKind.OTHER -> "  ❓"
            }

            output("$kindIcon ${element.kind}: ${element.name}")
            output("   Signature: ${element.signature}")
            output("   Offset: ${element.offsetRange.first}..${element.offsetRange.last} (${element.offsetRange.last - element.offsetRange.first} chars)")
            output("   Parent: ${element.parentName ?: "(top-level)"}")

            // Semantic hash
            val hash = try {
                provider.computeElementHash(virtualFile, element)
            } catch (e: Throwable) {
                "ERROR: ${e.message}"
            }
            output("   Semantic hash: ${hash.take(16)}…")

            // Cache state
            val (cachedSynopsis, isStale) = cache.getCachedElementSynopsis(filePath, element.signature, hash)
            val cacheStatus = when {
                cachedSynopsis != null && !isStale -> "✅ READY (${cachedSynopsis.length} chars)"
                cachedSynopsis != null && isStale -> "⚠️ INVALIDATED (stale, ${cachedSynopsis.length} chars)"
                else -> "❌ No cached summary"
            }
            output("   Cache: $cacheStatus")

            // Structural context
            val context = try {
                val level = when (element.kind) {
                    CodeElementKind.METHOD,
                    CodeElementKind.FUNCTION -> ElementLevel.METHOD
                    else -> ElementLevel.CLASS
                }
                provider.extractStructuralContext(virtualFile, element, level)
            } catch (e: Throwable) {
                "ERROR: ${e.message}"
            }
            if (context.isNotBlank()) {
                output("   Structural context:")
                context.lines().forEach { line ->
                    output("     $line")
                }
            } else {
                output("   Structural context: (none)")
            }

            // Body preview (first 100 chars)
            val preview = element.body.take(100).replace("\n", "\\n")
            output("   Body preview: $preview…")

            if (index < elements.size - 1) output("")
        }

        output("")
        output("─".repeat(60))

        // Summary stats
        val classes = elements.count { it.kind in setOf(
            CodeElementKind.CLASS,
            CodeElementKind.INTERFACE,
            CodeElementKind.OBJECT,
            CodeElementKind.ENUM
        )}
        val methods = elements.count { it.kind in setOf(
            CodeElementKind.METHOD,
            CodeElementKind.FUNCTION
        )}
        output("📊 Total: $classes class-level + $methods method-level = ${elements.size} elements")

        // Hash consistency check: detect all elements again and compare hashes
        // This verifies the semantic hash is stable (same input → same output)
        output("")
        output("🔁 Hash stability check (detect again, compare hashes)...")
        val elements2 = provider.detectElements(virtualFile, scope)
        var allStable = true
        for (element in elements) {
            val hash1 = try { provider.computeElementHash(virtualFile, element) } catch (_: Throwable) { "err1" }
            val matching = elements2.find { it.signature == element.signature }
            if (matching != null) {
                val hash2 = try { provider.computeElementHash(virtualFile, matching) } catch (_: Throwable) { "err2" }
                if (hash1 != hash2) {
                    output("   ❌ UNSTABLE: ${element.signature} — hash changed between two identical detections!")
                    allStable = false
                }
            } else {
                output("   ⚠️ Element ${element.signature} not found in second detection")
                allStable = false
            }
        }
        if (allStable) {
            output("   ✅ All hashes stable across two detections")
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  GIT & IDE CONTEXT COMMANDS
    //
    //  These diagnose git branch detection and IDE context capture.
    //  Useful for verifying the context pipeline before sending real chats.
    //  All logic preserved — only output channel changed.
    // ══════════════════════════════════════════════════════════════════════

    private fun runGitTest() {
        output("🧪 Testing Git Branch Detection")

        val report = buildString {
            appendLine("Step 1: Load git4idea.repo.GitRepositoryManager class")
            val clazz = try {
                val c = Class.forName("git4idea.repo.GitRepositoryManager")
                appendLine("  ✅ Class found: ${c.name}")
                c
            } catch (e: ClassNotFoundException) {
                appendLine("  ❌ Class not found — Git4Idea plugin may not be installed")
                appendLine("  Error: ${e.message}")
                null
            } catch (e: Exception) {
                appendLine("  ❌ Unexpected error loading class")
                appendLine("  Error: ${e.javaClass.simpleName}: ${e.message}")
                null
            }

            if (clazz == null) {
                appendLine()
                appendLine("🔧 Fix: Add Git4Idea as optional dependency in plugin.xml:")
                appendLine("  <depends optional=\"true\" config-file=\"git-integration.xml\">Git4Idea</depends>")
                return@buildString
            }

            appendLine()
            appendLine("Step 2: Get GitRepositoryManager service from project")
            val manager = try {
                val m = project.getService(clazz)
                if (m != null) {
                    appendLine("  ✅ Service instance obtained: ${m.javaClass.name}")
                } else {
                    appendLine("  ❌ getService returned null — service not registered for this project")
                }
                m
            } catch (e: Exception) {
                appendLine("  ❌ getService failed")
                appendLine("  Error: ${e.javaClass.simpleName}: ${e.message}")
                null
            }

            if (manager == null) return@buildString

            appendLine()
            appendLine("Step 3: Call getRepositories()")
            val repos = try {
                val method = manager.javaClass.getMethod("getRepositories")
                val result = method.invoke(manager) as? List<*>
                appendLine("  ✅ Got ${result?.size ?: 0} repositories")
                result?.forEachIndexed { i, repo ->
                    appendLine("  [$i] ${repo?.javaClass?.simpleName}: $repo")
                }
                result
            } catch (e: NoSuchMethodException) {
                appendLine("  ❌ Method 'getRepositories' not found")
                appendLine("  Available methods: ${manager.javaClass.methods.map { it.name }.distinct().sorted().take(20)}")
                null
            } catch (e: Exception) {
                appendLine("  ❌ Failed to invoke getRepositories")
                appendLine("  Error: ${e.javaClass.simpleName}: ${e.message}")
                null
            }

            if (repos.isNullOrEmpty()) {
                appendLine()
                appendLine("  ⚠️ No git repositories found. Is the project root a git repo?")
                appendLine("  Project basePath: ${project.basePath}")
                return@buildString
            }

            appendLine()
            appendLine("Step 4: Get current branch from first repository")
            val firstRepo = repos.first()!!
            try {
                val getBranch = firstRepo.javaClass.getMethod("getCurrentBranch")
                val branch = getBranch.invoke(firstRepo)
                if (branch != null) {
                    val getName = branch.javaClass.getMethod("getName")
                    val branchName = getName.invoke(branch) as? String
                    appendLine("  ✅ Current branch: $branchName")
                } else {
                    appendLine("  ⚠️ getCurrentBranch() returned null")
                    appendLine("  This can happen in detached HEAD state or during rebase")
                }
            } catch (e: NoSuchMethodException) {
                appendLine("  ❌ Method not found on repository object")
                appendLine("  Repo class: ${firstRepo.javaClass.name}")
            } catch (e: Exception) {
                appendLine("  ❌ Failed to get branch")
                appendLine("  Error: ${e.javaClass.simpleName}: ${e.message}")
            }
        }

        output(report)
    }

    private fun runContextTest() {
        output("🧪 Testing IDE Context Capture")

        val context = try {
            IdeContextCapture.capture(project)
        } catch (e: Exception) {
            output("❌ Capture threw exception: ${e.javaClass.simpleName}: ${e.message}")
            return
        }

        val report = buildString {
            if (context.isEmpty) {
                appendLine("⚠️ Context is empty — no editor state available")
                appendLine()
            }

            appendLine("📄 Active file: ${context.activeFile ?: "(none)"}")
            appendLine("🗣️ Language: ${context.language ?: "(unknown)"}")
            appendLine("📦 Module: ${context.module ?: "(unknown)"}")
            appendLine("🌿 Branch: ${context.branch ?: "(not detected)"}")
            appendLine()

            val openFiles = context.openFiles
            if (openFiles.isNullOrEmpty()) {
                appendLine("📑 Open tabs: (none besides active)")
            } else {
                appendLine("📑 Open tabs (${openFiles.size}):")
                openFiles.forEachIndexed { i, path ->
                    appendLine("  ${i + 1}. $path")
                }
            }

            appendLine()
            appendLine("isEmpty: ${context.isEmpty}")
        }

        output(report)
    }

    // ==================== /dev-summary-health ====================
    //
    // Safeguard #2: Diagnostic command that reports the health of the element
    // summary system. Checks consistency between in-memory cache and SQLite.
    //
    // See: BUG FIX — Element Summary Hash Validation.md, Safeguard #2
    //

    private fun runSummaryHealth() {
        output("🏥 Summary Health Check")
        output("════════════════════════════════════════════════════════════")

        val cache = SummaryCache.getInstance(project)
        val storage = LocalStorageFacade.getInstance(project)

        // 1. In-memory cache stats
        val allEntries = cache.getElementEntries("", null) // empty prefix = won't match — need a different approach
        // Use reflection or expose a method to get total count.
        // For now, report what we can directly.

        output("")
        output("📊 Stale-Served Counter")
        output("   Times hash validation caught a stale summary: ${cache.staleCaughtCount.get()}")
        output("   (If this is 0 after active development, validation may not be running)")

        // 2. Check SQLite element summaries
        output("")
        output("💾 SQLite Element Summaries")
        try {
            val projectId = storage.resolveProjectId()
            val elementRows = storage.loadElementSummaries(projectId)
            val withSynopsis = elementRows.count { it.synopsis.isNotBlank() }
            val withHash = elementRows.count { it.contentHashAtGen.isNotBlank() }
            val nullHash = elementRows.count { it.contentHashAtGen.isBlank() }

            output("   Total in summaries table: ${elementRows.size}")
            output("   With synopsis text: $withSynopsis")
            output("   With content hash: $withHash")
            output("   With NULL/empty hash: $nullHash")

            if (nullHash > 0) {
                output("   ⚠️ WARNING: $nullHash entries have no hash. These cannot be validated for freshness.")
                output("   This should be ZERO after the fix. If not, the persistence path regressed.")
            } else {
                output("   ✅ All entries have hashes. Persistence is working correctly.")
            }

            // 3. PSI validation sample (if PSI available)
            output("")
            output("🔬 PSI Validation Sample (up to 10 entries)")
            val factory = CodeStructureProviderFactory.getInstance(project)
            val provider = factory.get()

            if (provider == null) {
                output("   ⛔ PSI unavailable (dumb mode). Skipping validation sample.")
            } else {
                var checked = 0
                var valid = 0
                var stale = 0
                var skipped = 0

                for (row in elementRows.take(10)) {
                    try {
                        val vf = LocalFileSystem.getInstance()
                            .findFileByPath(row.filePath) ?: run { skipped++; continue }

                        val elements = provider.detectElements(vf, DetectionScope.All)
                        val element = elements.find { it.name == row.elementName }
                        if (element == null) { skipped++; continue }

                        val currentHash = provider.computeElementHash(vf, element)
                        checked++

                        if (currentHash == row.contentHashAtGen) {
                            valid++
                        } else {
                            stale++
                            output("   ⚠️ STALE: ${row.filePath}#${row.elementName}")
                            output("      stored=${row.contentHashAtGen.take(16)} current=${currentHash.take(16)}")
                        }
                    } catch (e: Throwable) {
                        skipped++
                    }
                }

                output("   Checked: $checked | Valid: $valid | Stale: $stale | Skipped: $skipped")
                if (stale == 0 && checked > 0) {
                    output("   ✅ All sampled summaries are fresh.")
                }
            }
        } catch (e: Exception) {
            output("   ❌ ERROR: ${e.message}")
        }

        // 4. Cache warmed status
        output("")
        output("🔥 Cache Status")
        output("   Warmed from storage: ${cache.isWarmed()}")

        output("")
        output("════════════════════════════════════════════════════════════")
        output("Done.")
    }

    // ==================== /dev-context-single, /dev-context-radius, /dev-context-chain ====================

    /**
     * Dry-run context gathering commands. No AI calls, no tokens spent.
     *
     * - "single": Resolves the current file + element at cursor via PSI.
     *   Shows what would be attached as context for the current file only.
     *
     * - "radius": Resolves cursor element, then shows what the RelevantFiles
     *   detector would gather (neighbouring files).
     *   TODO: Implement radius traversal once traversalRadius setting is wired.
     *
     * - "chain": Full dry-run of the context assembly pipeline (detectors +
     *   enrichment). Shows the complete context that would be sent with a request.
     *   TODO: Wire to ContextAssembler.assemble() in dry-run mode.
     */
    private fun runContextDry(mode: String) {
        output("🧪 Context Dry Run — mode: $mode")
        output("════════════════════════════════════════════════════════════")

        when (mode) {
            "single" -> runContextDrySingle()
            "radius" -> {
                output("")
                output("⚠️ /dev-context-radius is a stub.")
                output("   Traversal radius requires the traversalRadius setting to be wired")
                output("   to RelevantFilesDetector. See: Action Plan Phase D.3.")
                output("   For now, use /dev-context-single to inspect the current file.")
            }
            "chain" -> {
                output("")
                output("⚠️ /dev-context-chain is a stub.")
                output("   Full chain requires ContextAssembler.assemble() to support a dry-run flag")
                output("   that skips the AI call but still runs detectors + enrichment.")
                output("   See: Action Plan Phase B.1.")
                output("   For now, use /dev-context-single to inspect the current file.")
            }
        }

        output("")
        output("════════════════════════════════════════════════════════════")
        output("Done.")
    }

    /**
     * Single-file context dry run: resolve cursor element via PSI,
     * check for cached summary, show what would be attached.
     */
    private fun runContextDrySingle() {
        // Step 1: Resolve cursor position
        val resolved = com.youmeandmyself.ai.chat.context.EditorElementResolver.resolve(project)

        if (resolved == null) {
            output("⚠️ No file open in editor.")
            return
        }

        output("")
        output("📄 File: ${resolved.file.name}")
        output("   Path: ${resolved.file.path}")
        output("   Cursor offset: ${resolved.cursorOffset}")
        output("   Selected text: ${if (resolved.selectedText != null) "\"${resolved.selectedText.take(50)}...\"" else "(none)"}")

        val element = resolved.elementAtCursor
        val containingClass = resolved.containingClass

        if (element == null) {
            output("   Element at cursor: (none — cursor is between elements)")
            output("")
            output("   📎 Would attach: full file (raw content)")
            return
        }

        output("")
        output("🔍 Element at cursor:")
        output("   Name: ${element.name}")
        output("   Kind: ${element.kind}")
        output("   Signature: ${element.signature}")
        output("   Body size: ${element.body.length} chars (~${element.body.length / 4} tokens)")

        if (containingClass != null) {
            output("")
            output("📦 Containing class:")
            output("   Name: ${containingClass.name}")
            output("   Signature: ${containingClass.signature}")
            output("   Body size: ${containingClass.body.length} chars (~${containingClass.body.length / 4} tokens)")
        }

        // Step 2: Check for cached summaries
        output("")
        output("💾 Cache check:")

        val structureProvider = com.youmeandmyself.summary.structure.CodeStructureProviderFactory
            .getInstance(project).get()

        if (structureProvider == null) {
            output("   ⚠️ PSI unavailable (dumb mode). Cannot compute hash or check cache.")
            return
        }

        try {
            val hash = structureProvider.computeElementHash(resolved.file, element)
            output("   Element hash: ${hash.take(16)}…")

            val cache = SummaryCache.getInstance(project)
            val cached = cache.getCachedElementSynopsis(resolved.file.path, element.signature, hash)
            if (cached.first != null && !cached.second) {
                output("   ✅ Summary cached: ${cached.first!!.length} chars (VALID)")
                output("   📎 Would attach: element summary")
            } else if (cached.first != null && cached.second) {
                output("   ⚠️ Summary cached but STALE (hash mismatch)")
                output("   📎 Would attach: regenerate summary synchronously, then attach")
            } else {
                output("   ❌ No cached summary")
                output("   📎 Would attach: generate summary synchronously, then attach")
            }
        } catch (e: Throwable) {
            output("   ❌ Error computing hash: ${e.message}")
        }

        // Step 3: Show what context assembly would produce
        output("")
        output("📋 Context assembly preview:")
        output("   Force context: would guarantee this element is attached")
        output("   Heuristic + smart mode: would also gather neighbouring files (see /dev-context-radius)")
        output("   Full chain: would run all detectors + enrichment (see /dev-context-chain)")
    }
}