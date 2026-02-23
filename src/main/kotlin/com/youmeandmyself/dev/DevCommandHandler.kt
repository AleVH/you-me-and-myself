package com.youmeandmyself.dev

import com.intellij.openapi.project.Project
import com.youmeandmyself.ai.chat.service.ChatUIService
import com.youmeandmyself.ai.chat.service.SystemMessageType
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
 * Handles dev-only test commands for exercising the correction flow.
 *
 * Only active when dev mode is enabled (-Dymm.devMode=true).
 * Called by ChatPanel to check if user input is a dev command.
 *
 * ## Available Commands
 *
 * - /dev-scenario1: Test known format (no correction UI)
 * - /dev-scenario2: Test heuristic with correction option
 * - /dev-scenario3: Test low confidence (immediate dialog)
 * - /dev-error: Test error response
 * - /dev-status: Show dev mode status
 * - /dev-help: Show available commands
 *
 * ## Usage in ChatPanel
 *
 * ```kotlin
 * private val devHandler = DevCommandHandler(project, chatService, correctionHelper, scope)
 *
 * private fun doSend() {
 *     val userInput = input.text?.trim().orEmpty()
 *
 *     // Check for dev commands first
 *     if (devHandler.handleIfDevCommand(userInput)) {
 *         input.text = ""
 *         return
 *     }
 *
 *     // ... rest of normal processing
 * }
 * ```
 */
class DevCommandHandler(
    private val project: Project,
    private val chatService: ChatUIService,
    private val correctionHelper: CorrectionFlowHelper,
    private val scope: CoroutineScope
) {
    /**
     * Check if the input is a dev command and handle it.
     *
     * @param input The user's input text
     * @return true if it was a dev command (handled), false otherwise (continue normal flow)
     */
    fun handleIfDevCommand(input: String): Boolean {
        // Dev commands start with /dev
        if (!input.lowercase().startsWith("/dev")) {
            return false
        }

        // If dev mode is not enabled, silently ignore (don't reveal commands exist)
        if (!DevMode.isEnabled()) {
            return false
        }

        // Parse and handle the command
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
                chatService.addSystemMessage(
                    "Unknown dev command: $input\nType /dev-help to see available commands.",
                    SystemMessageType.INFO
                )
            }
        }

        return true
    }

    /**
     * Show available dev commands.
     */
    private fun showHelp() {
        chatService.addSystemMessage(DevMode.helpText(), SystemMessageType.INFO)
    }

    /**
     * Show current dev mode status and configuration.
     */
    private fun showStatus() {
        val status = buildString {
            appendLine("🔧 YMM Dev Mode Status")
            appendLine()
            appendLine("  Enabled: ${DevMode.isEnabled()}")
            appendLine("  Property: -Dymm.devMode=${System.getProperty("ymm.devMode") ?: "(not set)"}")
            appendLine()
            appendLine("  Correction helper has context: ${correctionHelper.hasCorrectableResponse()}")
        }
        chatService.addSystemMessage(status, SystemMessageType.INFO)
    }

    /**
     * Test Scenario 1: Known format, high confidence.
     * Should display response immediately with no correction UI.
     */
    private fun runScenario1() {
        chatService.addSystemMessage(
            "🧪 Running Scenario 1: Known Format Test",
            SystemMessageType.INFO
        )

        val testResponse = TestResponseFactory.scenario1_KnownFormat()
        processTestResponse(testResponse, expectCorrectionUI = false)
    }

    /**
     * Test Scenario 2: Heuristic with medium confidence.
     * Should display response with "Type /correct to fix" hint.
     */
    private fun runScenario2() {
        chatService.addSystemMessage(
            "🧪 Running Scenario 2: Heuristic + Confident Test",
            SystemMessageType.INFO
        )

        val testResponse = TestResponseFactory.scenario2_HeuristicConfident()
        processTestResponse(testResponse, expectCorrectionUI = true)
    }

    /**
     * Test Scenario 3: Low confidence.
     * Should show CorrectionDialog immediately before displaying.
     */
    private fun runScenario3() {
        chatService.addSystemMessage(
            "🧪 Running Scenario 3: Low Confidence Test\n" +
                    "⚠️ A dialog should appear - select a response option.",
            SystemMessageType.INFO
        )

        val testResponse = TestResponseFactory.scenario3_LowConfidence()
        processTestResponse(testResponse, expectCorrectionUI = true)
    }

    /**
     * Test error response handling.
     */
    private fun runErrorScenario() {
        chatService.addSystemMessage(
            "🧪 Running Error Response Test",
            SystemMessageType.INFO
        )

        val testResponse = TestResponseFactory.errorResponse()

        // Error responses just display, no correction flow
        chatService.addAssistantMessage(
            testResponse.parsedResponse.displayText,
            testResponse.providerId,
            testResponse.parsedResponse.isError
        )
    }

    /**
     * Process a test response through the correction flow.
     * Mimics what ChatPanel does with real provider responses.
     */
    private fun processTestResponse(testResponse: TestResponse, expectCorrectionUI: Boolean) {
        val result = testResponse.parsedResponse

        // Clear any previous correction context
        correctionHelper.clearCorrectionContext()

        scope.launch {
            val finalDisplayText: String
            val finalIsError: Boolean

            when {
                // Scenario 3: Low confidence - ask immediately
                correctionHelper.shouldAskImmediately(result) && result.metadata.candidates.isNotEmpty() -> {
                    val corrected = correctionHelper.handleImmediateCorrection(
                        result = result,
                        providerId = testResponse.providerId,
                        modelId = testResponse.modelId
                    )

                    if (corrected != null) {
                        finalDisplayText = corrected.displayText
                        finalIsError = false
                    } else {
                        // User cancelled
                        finalDisplayText = result.displayText
                        finalIsError = result.isError
                        storeForCorrectionWithRawJson(testResponse)
                    }
                }

                // Scenario 2: Heuristic but confident
                correctionHelper.shouldOfferPostCorrection(result) -> {
                    finalDisplayText = result.displayText
                    finalIsError = result.isError
                    storeForCorrectionWithRawJson(testResponse)
                }

                // Scenario 1: Known format
                else -> {
                    finalDisplayText = result.displayText
                    finalIsError = result.isError
                }
            }

            // Display the response
            chatService.addAssistantMessage(
                finalDisplayText,
                testResponse.providerId,
                finalIsError
            )

            // Show correction hint for Scenario 2
            if (correctionHelper.hasCorrectableResponse()) {
                chatService.addSystemMessage(
                    "ℹ️ Response auto-detected. Not what you expected? Type /correct to fix.",
                    SystemMessageType.INFO
                )
            }

            // Show test verification message
            if (expectCorrectionUI && correctionHelper.hasCorrectableResponse()) {
                chatService.addSystemMessage(
                    "✅ Test passed: Correction context stored. Try /correct or /raw",
                    SystemMessageType.INFO
                )
            } else if (!expectCorrectionUI && !correctionHelper.hasCorrectableResponse()) {
                chatService.addSystemMessage(
                    "✅ Test passed: No correction UI (as expected for known format)",
                    SystemMessageType.INFO
                )
            }
        }
    }

    /**
     * Store test response for correction, including the fake raw JSON.
     *
     * For real responses, the raw JSON is fetched from storage via exchangeId.
     * For test responses, we need to inject it into the correction context
     * so /raw command works.
     */
    private fun storeForCorrectionWithRawJson(testResponse: TestResponse) {
        correctionHelper.storeForPostCorrection(
            result = testResponse.parsedResponse,
            providerId = testResponse.providerId,
            modelId = testResponse.modelId,
            force = true // Force storage for test scenarios
        )

        // Store raw JSON for /raw command
        // Note: This uses a test-specific mechanism since we're not persisting to real storage
        correctionHelper.storeTestRawJson(testResponse.rawJson)
    }

    /**
     * Test the summary pipeline in dry-run mode.
     *
     * Evaluates the currently open file against summary config:
     * 1. Checks config (mode, kill switch, budget, scope)
     * 2. Shows the decision and reasoning
     * 3. Shows what provider/model would be used
     * 4. Estimates token cost
     * 5. Does NOT make any API call
     */
    private fun runSummaryTest() {
        chatService.addSystemMessage(
            "🧪 Running Summary Pipeline Test (dry-run)",
            SystemMessageType.INFO
        )

        val configService = SummaryConfigService.getInstance(project)
        val config = configService.getConfig()

        // Show current config
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
        chatService.addSystemMessage(configStatus, SystemMessageType.INFO)

        // Try to get the currently open file
        val editor = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).selectedTextEditor
        val virtualFile = editor?.virtualFile

        if (virtualFile == null) {
            chatService.addSystemMessage(
                "⚠️ No file open in editor. Open a file and run /dev-summary-test again.",
                SystemMessageType.INFO
            )
            return
        }

        val filePath = virtualFile.path
        val lineCount = editor.document.lineCount
        val languageId = virtualFile.fileType?.name

        chatService.addSystemMessage(
            "📄 Testing against: ${virtualFile.name} ($lineCount lines, language: $languageId)",
            SystemMessageType.INFO
        )

        // Evaluate scope
        val scopeDecision = configService.shouldSummarize(filePath, lineCount)
        val scopeIcon = if (scopeDecision.allowed) "✅" else "❌"
        chatService.addSystemMessage(
            "$scopeIcon Scope decision: ${scopeDecision.reason}",
            SystemMessageType.INFO
        )

        // Dry-run evaluation
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
        chatService.addSystemMessage(dryRunStatus, SystemMessageType.INFO)

        // Final verdict
        val verdict = if (dryRun.wouldSummarize) {
            "✅ This file WOULD be summarized (dry-run prevented the API call)"
        } else {
            "⛔ This file would NOT be summarized: ${dryRun.reason}"
        }
        chatService.addSystemMessage(verdict, SystemMessageType.INFO)
    }

    /**
     * Write a mock FILE_SUMMARY exchange through the full save pipeline.
     *
     * Creates a fake summary for the currently open file and persists it
     * through saveExchange — same code path as a real summary. Used to
     * verify storage routing (summaries → summaries/ folder) without
     * making any API call or spending tokens.
     *
     * The mock exchange has:
     * - purpose = FILE_SUMMARY
     * - providerId/modelId = "dev-mock"
     * - A recognizable fake response so it's easy to spot in JSONL
     */
    private fun runMockSummary() {
        val editor = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).selectedTextEditor
        val virtualFile = editor?.virtualFile

        if (virtualFile == null) {
            chatService.addSystemMessage(
                "⚠️ No file open in editor. Open a file and run /dev-summary-mock again.",
                SystemMessageType.INFO
            )
            return
        }

        chatService.addSystemMessage(
            "🧪 Writing mock FILE_SUMMARY for: ${virtualFile.name}",
            SystemMessageType.INFO
        )

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

        // Go through the real save pipeline — this is what we're testing
        kotlinx.coroutines.runBlocking {
            val savedId = facade.saveExchange(mockExchange, projectId)
            if (savedId != null) {
                chatService.addSystemMessage(
                    "✅ Mock FILE_SUMMARY saved (id: ${savedId.take(8)}…). Check ~/YouMeAndMyself/summaries/ for the JSONL file.",
                    SystemMessageType.INFO
                )

                // Verify the file landed in the right folder
                val config = facade.getStorageConfig()  // you may need to expose this
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
                chatService.addSystemMessage(routingResult, SystemMessageType.INFO)

                // Cleanup: remove mock from SQLite
                facade.withDatabase { db ->
                    db.execute("DELETE FROM chat_exchanges WHERE id = ?", savedId)
                }

                // Cleanup: remove mock line from JSONL
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

                chatService.addSystemMessage(
                    "🧹 Cleanup complete — mock exchange removed from SQLite and JSONL.",
                    SystemMessageType.INFO
                )
            } else {
                chatService.addSystemMessage(
                    "❌ saveExchange returned null — check logs.",
                    SystemMessageType.INFO
                )
            }
        }
    }

    /**
     * Request an on-demand summary of the currently open file.
     * Respects config — will make an API call if not in dry-run mode.
     */
    private fun runSummarize() {
        val editor = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).selectedTextEditor
        val virtualFile = editor?.virtualFile

        if (virtualFile == null) {
            chatService.addSystemMessage(
                "⚠️ No file open in editor. Open a file and run /dev-summarize again.",
                SystemMessageType.INFO
            )
            return
        }

        val configService = SummaryConfigService.getInstance(project)

        if (!configService.isEnabled()) {
            chatService.addSystemMessage(
                "⛔ Summarization is disabled. Enable it in Settings → Tools → YMM Assistant → Summary.",
                SystemMessageType.INFO
            )
            return
        }

        if (configService.isDryRun()) {
            val lineCount = editor.document.lineCount
            val dryRun = configService.evaluateDryRun(virtualFile.path, lineCount)
            chatService.addSystemMessage(
                "🔍 Dry-run mode: would summarize ${virtualFile.name} " +
                        "(~${dryRun.estimatedTokens} tokens, provider: ${dryRun.providerInfo}). " +
                        "Turn off dry-run in Settings to actually summarize.",
                SystemMessageType.INFO
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
            chatService.addSystemMessage(
                "📝 Summarizing ${virtualFile.name}... Running in the background.",
                SystemMessageType.INFO
            )
        } else {
            chatService.addSystemMessage(
                "⚠️ Could not enqueue ${virtualFile.name}. Check scope/budget settings.",
                SystemMessageType.INFO
            )
        }
    }

    /**
     * Show current summary system status.
     */
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

        chatService.addSystemMessage(status, SystemMessageType.INFO)
    }

    /**
     * Emergency stop — cancel all queued summaries and flip the kill switch.
     */
    private fun runSummaryStop() {
        val configService = SummaryConfigService.getInstance(project)
        val store = project.getService(SummaryStore::class.java)

        val cancelled = store.queue.cancelAll()
        configService.setEnabled(false)

        chatService.addSystemMessage(
            "🛑 Summarization stopped. Cancelled $cancelled queued items. Kill switch is now OFF. " +
                    "Re-enable in Settings → Tools → YMM Assistant → Summary.",
            SystemMessageType.INFO
        )
    }

    /**
     * Test git branch detection step by step.
     *
     * Reports exactly where the detection succeeds or fails, including
     * the specific exception at each step. This helps diagnose why
     * branch might be null in IdeContext.
     */
    private fun runGitTest() {
        chatService.addSystemMessage(
            "🧪 Testing Git Branch Detection",
            SystemMessageType.INFO
        )

        val report = buildString {
            // Step 1: Can we load the class?
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

            // Step 2: Can we get the service?
            appendLine()
            appendLine("Step 2: Get GitRepositoryManager service from project")
            val manager = try {
                val m = project.getService(clazz)
                if (m != null) {
                    appendLine("  ✅ Service instance obtained: ${m.javaClass.name}")
                } else {
                    appendLine("  ❌ getService returned null — service not registered for this project")
                    appendLine("  This usually means Git4Idea plugin is installed but not active for this project")
                }
                m
            } catch (e: Exception) {
                appendLine("  ❌ getService failed")
                appendLine("  Error: ${e.javaClass.simpleName}: ${e.message}")
                null
            }

            if (manager == null) return@buildString

            // Step 3: Can we call getRepositories?
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
                appendLine("  Error: ${e.message}")
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

            // Step 4: Can we get the branch?
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
                appendLine("  Error: ${e.message}")
                appendLine("  Repo class: ${firstRepo.javaClass.name}")
                appendLine("  Available methods: ${firstRepo.javaClass.methods.map { it.name }.distinct().sorted().take(20)}")
            } catch (e: Exception) {
                appendLine("  ❌ Failed to get branch")
                appendLine("  Error: ${e.javaClass.simpleName}: ${e.message}")
            }
        }

        chatService.addSystemMessage(report, SystemMessageType.INFO)
    }

    /**
     * Test the full IdeContext capture pipeline.
     *
     * Runs IdeContextCapture.capture() and displays everything it found:
     * active file, all open files, language, module, git branch.
     * Useful for verifying the capture works before sending a real chat.
     */
    private fun runContextTest() {
        chatService.addSystemMessage(
            "🧪 Testing IDE Context Capture",
            SystemMessageType.INFO
        )

        val context = try {
            IdeContextCapture.capture(project)
        } catch (e: Exception) {
            chatService.addSystemMessage(
                "❌ Capture threw exception: ${e.javaClass.simpleName}: ${e.message}",
                SystemMessageType.INFO
            )
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

        chatService.addSystemMessage(report, SystemMessageType.INFO)
    }
}