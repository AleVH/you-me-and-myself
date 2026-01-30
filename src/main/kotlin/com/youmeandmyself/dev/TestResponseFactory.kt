package com.youmeandmyself.dev

import com.youmeandmyself.ai.providers.parsing.*
import com.youmeandmyself.ai.providers.parsing.ui.TextCandidate
import java.util.UUID

/**
 * Factory for generating test ParsedResponses to exercise the correction flow.
 *
 * Used exclusively in dev mode to manually trigger each scenario without
 * needing a real provider that returns unusual formats.
 *
 * ## The Three Scenarios
 *
 * 1. **Known Format**: Parser recognized the schema (OpenAI, Gemini, etc.)
 *    - High confidence, no heuristics
 *    - Just displays, no correction UI
 *
 * 2. **Heuristic + Confident**: Parser guessed using JSON walk heuristic
 *    - Medium/High confidence, has alternative candidates
 *    - Shows response with "Type /correct to fix" hint
 *
 * 3. **Low Confidence**: Parser guessed but is very uncertain
 *    - Low/None confidence, has candidates
 *    - Shows CorrectionDialog immediately before displaying
 *
 * ## Usage
 *
 * Called by ChatPanel when dev commands like /dev-scenario2 are entered.
 * The fake response is then processed through the normal correction flow
 * so all UI paths are exercised.
 */
object TestResponseFactory {

    private const val TEST_PROVIDER_ID = "dev-test-provider"
    private const val TEST_MODEL_ID = "test-model-v1"

    /**
     * Scenario 1: Known format, high confidence.
     *
     * Simulates a response from a recognized provider (OpenAI, Gemini, etc.)
     * where we know exactly how to extract the content.
     *
     * Expected behavior: Response displays immediately, no correction UI.
     */
    fun scenario1_KnownFormat(): TestResponse {
        val content = """
            |# Hello from Scenario 1! 🎯
            |
            |This is a **known format** response. The parser recognized the schema
            |and extracted the content with high confidence.
            |
            |```kotlin
            |fun main() {
            |    println("No correction UI should appear!")
            |}
            |```
            |
            |You should NOT see any "Type /correct to fix" message.
        """.trimMargin()

        val metadata = ParseMetadata(
            parseStrategy = ParseStrategy.KNOWN_SCHEMA_OPENAI,
            confidence = Confidence.HIGH,
            detectedSchema = DetectedSchema.OPENAI_CHAT,
            contentPath = ".choices[0].message.content",
            candidates = emptyList() // No alternatives needed - we're sure
        )

        return TestResponse(
            parsedResponse = ParsedResponse.success(
                rawText = content,
                exchangeId = generateTestExchangeId(),
                metadata = metadata,
                displayText = content
            ),
            rawJson = generateFakeOpenAiJson(content),
            providerId = TEST_PROVIDER_ID,
            modelId = TEST_MODEL_ID
        )
    }

    /**
     * Scenario 2: Heuristic used, medium/high confidence.
     *
     * Simulates a response from an unknown provider where we used the
     * JSON walk heuristic and found a plausible match.
     *
     * Expected behavior:
     * - Response displays immediately (best guess)
     * - System message: "Type /correct to fix"
     * - /correct command opens CorrectionDialog with alternatives
     */
    fun scenario2_HeuristicConfident(): TestResponse {
        val correctContent = """
            |# Hello from Scenario 2! 🔧
            |
            |This response was parsed using **heuristics** because the format
            |wasn't recognized. But we're fairly confident this is correct.
            |
            |```kotlin
            |fun main() {
            |    println("You should see: Type /correct to fix")
            |}
            |```
            |
            |Try the `/correct` command to see the correction dialog!
        """.trimMargin()

        val wrongContent1 = "error: null"
        val wrongContent2 = "v1.2.3-beta"
        val wrongContent3 = "{\"status\": \"ok\", \"code\": 200}"

        // Candidates ranked by score - correct one first
        val candidates = listOf(
            TextCandidate.create(
                text = correctContent,
                path = ".data.response.text",
                score = 85,
                scoreBreakdown = listOf(
                    "+40: Long text (likely content)",
                    "+25: Contains markdown formatting",
                    "+20: Contains code block"
                )
            ),
            TextCandidate.create(
                text = wrongContent1,
                path = ".error",
                score = 30,
                scoreBreakdown = listOf(
                    "+20: At 'error' path",
                    "+10: Short text"
                )
            ),
            TextCandidate.create(
                text = wrongContent2,
                path = ".version",
                score = 15,
                scoreBreakdown = listOf(
                    "+15: Looks like version string"
                )
            ),
            TextCandidate.create(
                text = wrongContent3,
                path = ".metadata",
                score = 10,
                scoreBreakdown = listOf(
                    "+10: JSON-like structure"
                )
            )
        )

        val metadata = ParseMetadata(
            parseStrategy = ParseStrategy.HEURISTIC_JSON_WALK,
            confidence = Confidence.MEDIUM,
            detectedSchema = DetectedSchema.UNKNOWN,
            contentPath = ".data.response.text",
            candidates = candidates
        )

        return TestResponse(
            parsedResponse = ParsedResponse.success(
                rawText = correctContent,
                exchangeId = generateTestExchangeId(),
                metadata = metadata,
                displayText = correctContent
            ),
            rawJson = generateFakeUnknownJson(correctContent, wrongContent1, wrongContent2, wrongContent3),
            providerId = TEST_PROVIDER_ID,
            modelId = TEST_MODEL_ID
        )
    }

    /**
     * Scenario 3: Heuristic used, low confidence.
     *
     * Simulates a response where we really don't know which content is correct.
     * Multiple candidates with similar scores.
     *
     * Expected behavior:
     * - CorrectionDialog appears IMMEDIATELY (before showing any response)
     * - User must pick the correct content
     * - Selected content then displays
     */
    fun scenario3_LowConfidence(): TestResponse {
        val content1 = """
            |# Option A: Main Content
            |
            |This might be the actual response. It has some markdown
            |and looks like something an AI would say.
            |
            |```python
            |print("Hello World")
            |```
        """.trimMargin()

        val content2 = """
            |## Option B: Alternative Content
            |
            |Or maybe THIS is the real response? Hard to tell.
            |The parser found multiple plausible candidates.
            |
            |Try selecting different options to see how it works!
        """.trimMargin()

        val content3 = "Error: Unable to process request. Please try again."

        val content4 = """
            |{
            |  "thinking": "The user wants a greeting...",
            |  "response": "Hello! How can I help you today?"
            |}
        """.trimMargin()

        // Candidates with similar scores - hard to pick automatically
        val candidates = listOf(
            TextCandidate.create(
                text = content1,
                path = ".output.message",
                score = 45,
                scoreBreakdown = listOf(
                    "+25: Contains markdown",
                    "+20: Contains code block"
                )
            ),
            TextCandidate.create(
                text = content2,
                path = ".result.content",
                score = 42,
                scoreBreakdown = listOf(
                    "+25: Contains markdown",
                    "+17: Reasonable length"
                )
            ),
            TextCandidate.create(
                text = content3,
                path = ".error.message",
                score = 38,
                scoreBreakdown = listOf(
                    "+20: At error path",
                    "+18: Looks like error message"
                )
            ),
            TextCandidate.create(
                text = content4,
                path = ".debug.raw",
                score = 35,
                scoreBreakdown = listOf(
                    "+20: Contains 'response' key",
                    "+15: JSON structure"
                )
            )
        )

        val metadata = ParseMetadata(
            parseStrategy = ParseStrategy.HEURISTIC_JSON_WALK,
            confidence = Confidence.LOW, // This triggers immediate dialog
            detectedSchema = DetectedSchema.UNKNOWN,
            contentPath = ".output.message",
            candidates = candidates
        )

        return TestResponse(
            parsedResponse = ParsedResponse.success(
                rawText = content1, // Best guess, but not confident
                exchangeId = generateTestExchangeId(),
                metadata = metadata,
                displayText = content1
            ),
            rawJson = generateFakeAmbiguousJson(content1, content2, content3, content4),
            providerId = TEST_PROVIDER_ID,
            modelId = TEST_MODEL_ID
        )
    }

    /**
     * Error response test.
     *
     * Simulates a provider error (rate limit, auth failure, etc.)
     * to verify error display and classification.
     */
    fun errorResponse(): TestResponse {
        val errorMessage = "Rate limit exceeded. Please wait 60 seconds before retrying."

        val metadata = ParseMetadata(
            parseStrategy = ParseStrategy.ERROR_DETECTED,
            confidence = Confidence.HIGH,
            detectedSchema = DetectedSchema.UNKNOWN
        )

        return TestResponse(
            parsedResponse = ParsedResponse.error(
                errorMessage = errorMessage,
                errorType = ErrorType.RATE_LIMITED,
                exchangeId = generateTestExchangeId(),
                metadata = metadata,
                displayText = "⚠️ $errorMessage"
            ),
            rawJson = """{"error": {"message": "$errorMessage", "type": "rate_limit_error", "code": 429}}""",
            providerId = TEST_PROVIDER_ID,
            modelId = TEST_MODEL_ID
        )
    }

    // --- Helper methods to generate fake JSON payloads ---

    private fun generateTestExchangeId(): String {
        return "test-${UUID.randomUUID().toString().take(8)}"
    }

    private fun generateFakeOpenAiJson(content: String): String {
        val escaped = content.replace("\"", "\\\"").replace("\n", "\\n")
        return """
            {
              "id": "chatcmpl-test123",
              "object": "chat.completion",
              "created": 1234567890,
              "model": "gpt-4",
              "choices": [
                {
                  "index": 0,
                  "message": {
                    "role": "assistant",
                    "content": "$escaped"
                  },
                  "finish_reason": "stop"
                }
              ],
              "usage": {
                "prompt_tokens": 10,
                "completion_tokens": 50,
                "total_tokens": 60
              }
            }
        """.trimIndent()
    }

    private fun generateFakeUnknownJson(main: String, err: String, ver: String, meta: String): String {
        val escapedMain = main.replace("\"", "\\\"").replace("\n", "\\n")
        return """
            {
              "version": "$ver",
              "error": "$err",
              "metadata": $meta,
              "data": {
                "response": {
                  "text": "$escapedMain"
                }
              }
            }
        """.trimIndent()
    }

    private fun generateFakeAmbiguousJson(c1: String, c2: String, c3: String, c4: String): String {
        fun escape(s: String) = s.replace("\"", "\\\"").replace("\n", "\\n")
        return """
            {
              "output": {
                "message": "${escape(c1)}"
              },
              "result": {
                "content": "${escape(c2)}"
              },
              "error": {
                "message": "${escape(c3)}"
              },
              "debug": {
                "raw": "${escape(c4)}"
              }
            }
        """.trimIndent()
    }
}

/**
 * Container for a test response with all associated data.
 *
 * Bundles the ParsedResponse with the raw JSON and provider info
 * so the test can simulate the full flow including /raw command.
 */
data class TestResponse(
    val parsedResponse: ParsedResponse,
    val rawJson: String,
    val providerId: String,
    val modelId: String
)