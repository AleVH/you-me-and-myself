package com.youmeandmyself.dev

import com.intellij.openapi.project.Project
import com.youmeandmyself.ai.chat.service.ChatUIService
import com.youmeandmyself.ai.chat.service.SystemMessageType
import com.youmeandmyself.ai.providers.parsing.ui.CorrectionFlowHelper
import com.youmeandmyself.storage.StorageFacade
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

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
}