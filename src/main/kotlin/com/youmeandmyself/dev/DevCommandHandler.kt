package com.youmeandmyself.dev

import com.intellij.openapi.project.Project
import com.youmeandmyself.ai.providers.parsing.ui.CorrectionFlowHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import com.youmeandmyself.context.orchestrator.config.SummaryConfigService
import com.youmeandmyself.context.orchestrator.SummaryStore
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
 */
class DevCommandHandler(
    private val project: Project,
    private val correctionHelper: CorrectionFlowHelper,
    private val scope: CoroutineScope
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
        // Every line gets logged so output is visible in idea.log
        Dev.info(log, "dev.output", "message" to message)

        // TODO: When reconnected, send through chosen output channel:
        // Option A: sendEvent(DevOutputEvent(message))
        // Option B: devConsolePanel.append(message)
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

        val store = project.getService(SummaryStore::class.java)
        val contentHash = try {
            java.nio.file.Files.readString(java.nio.file.Path.of(virtualFile.path)).hashCode().toString()
        } catch (_: Throwable) { null }

        val enqueued = store.requestSummary(
            path = virtualFile.path,
            languageId = virtualFile.fileType?.name,
            currentContentHash = contentHash,
            maxTokens = 500
        )

        if (enqueued) {
            output("📝 Summarizing ${virtualFile.name}... Running in the background.")
        } else {
            output("⚠️ Could not enqueue ${virtualFile.name}. Check scope/budget settings.")
        }
    }

    private fun runSummaryStatus() {
        val configService = SummaryConfigService.getInstance(project)
        val store = project.getService(SummaryStore::class.java)
        val config = configService.getConfig()

        val status = buildString {
            appendLine("📊 Summary Status:")
            appendLine("  Enabled: ${config.enabled}")
            appendLine("  Mode: ${config.mode.displayName}")
            appendLine("  Dry-run: ${config.dryRun}")
            appendLine("  Tokens used this session: ${config.tokensUsedSession}")
            appendLine("  Budget remaining: ${config.remainingBudget?.let { "$it tokens" } ?: "unlimited"}")
            appendLine("  Files queued: ${store.queue.size()}")
            val pending = store.queue.pendingPaths()
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
        val store = project.getService(SummaryStore::class.java)

        val cancelled = store.queue.cancelAll()
        configService.setEnabled(false)

        output(
            "🛑 Summarization stopped. Cancelled $cancelled queued items. Kill switch is now OFF. " +
                    "Re-enable in Settings → Tools → YMM Assistant → Summary."
        )
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
}