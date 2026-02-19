// File: src/main/kotlin/com/youmeandmyself/context/orchestrator/SummaryExtractor.kt
package com.youmeandmyself.context.orchestrator

import com.intellij.openapi.diagnostic.Logger
import com.youmeandmyself.ai.providers.parsing.ParsedResponse
import com.youmeandmyself.ai.settings.RequestSettings
import com.youmeandmyself.dev.Dev
import kotlinx.serialization.json.*

/**
 * Handles summary prompt construction and response extraction.
 *
 * ## Purpose
 *
 * This class encapsulates all the logic specific to code summarization:
 * - Building prompts that instruct the LLM to produce concise, plain-text summaries
 * - Extracting summary text from LLM responses (handling various formats)
 * - Providing fallback extraction when standard parsing fails
 *
 * ## Why Separate From SummaryStore?
 *
 * Separation of concerns:
 * - SummaryStore: orchestration (when to summarize, caching, staleness)
 * - SummaryExtractor: the "how" of summarization (prompts, extraction)
 *
 * This makes both classes easier to test and modify independently.
 *
 * ## The "Summary:" Marker Strategy
 *
 * LLM responses can come in unpredictable formats, especially from unknown providers.
 * Our prompt instructs the model to start its response with "Summary: ". This gives us
 * a reliable extraction fallback:
 *
 * 1. First, try standard response parsing (handles OpenAI, Gemini, etc.)
 * 2. If that fails, scan the raw JSON for any string starting with "Summary: "
 * 3. If found, extract everything after the marker
 * 4. Only ask the user as an absolute last resort (should rarely happen)
 *
 * This is less intrusive than the chat correction flow because:
 * - Summaries happen in the background, not blocking the user
 * - We have a self-describing marker to find the content
 * - The user didn't explicitly request this output
 */
object SummaryExtractor {

    private val log = Logger.getInstance(SummaryExtractor::class.java)

    /** The marker we instruct the LLM to prefix its summary with. */
    private const val SUMMARY_MARKER = "Summary: "

    /** Default prompt template when none is configured in profile settings. */
    private val DEFAULT_PROMPT_TEMPLATE = """
        |Summarize this {languageId} code concisely in plain text.
        |No markdown formatting, no code blocks, just a clear description of what this code does.
        |Start your response with "Summary: " followed by the summary text.
        |
        |{sourceText}
    """.trimMargin()

    // ==================== Prompt Building ====================

    /**
     * Build the complete prompt for a summary request.
     *
     * Takes the prompt template (from settings or default) and replaces placeholders:
     * - {languageId} → the programming language (e.g., "Kotlin", "Python")
     * - {sourceText} → the actual code to summarize
     *
     * @param languageId Programming language identifier (e.g., "Kotlin", "Java", "Python").
     *                   If null or blank, uses "code" as a generic fallback.
     * @param sourceText The code snippet to summarize.
     * @param settings Optional request settings containing a custom prompt template.
     *                 If null or if systemPrompt is blank, uses DEFAULT_PROMPT_TEMPLATE.
     * @return The complete prompt ready to send to the LLM.
     */
    fun buildPrompt(
        languageId: String?,
        sourceText: String,
        settings: RequestSettings? = null
    ): String {
        // Use custom template if provided, otherwise default
        val template = settings?.systemPrompt?.takeIf { it.isNotBlank() }
            ?: DEFAULT_PROMPT_TEMPLATE

        // Normalize language ID
        val language = languageId?.takeIf { it.isNotBlank() } ?: "code"

        // Replace placeholders
        val prompt = template
            .replace("{languageId}", language)
            .replace("{sourceText}", sourceText)

        Dev.info(log, "prompt.built",
            "languageId" to language,
            "sourceLength" to sourceText.length,
            "promptLength" to prompt.length,
            "usingCustomTemplate" to (settings?.systemPrompt != null)
        )

        return prompt
    }

    // ==================== Response Extraction ====================

    /**
     * Extract summary text from a parsed LLM response.
     *
     * Extraction strategy (in order):
     * 1. Use displayText from standard parsing if available and valid
     * 2. Scan raw JSON for "Summary: " marker if standard parsing failed
     * 3. Return null if all extraction attempts fail (caller should handle)
     *
     * The extracted text has the "Summary: " prefix stripped if present.
     *
     * @param response The parsed response from the LLM provider.
     * @param rawJson The raw JSON response (for fallback extraction).
     * @return The extracted summary text, or null if extraction failed.
     */
    fun extract(response: ParsedResponse, rawJson: String?): ExtractionResult {
        Dev.info(log, "extract.start",
            "exchangeId" to response.exchangeId,
            "isError" to response.isError,
            "hasDisplayText" to (response.displayText.isNotBlank())
        )

        // Strategy 1: Standard parsing succeeded
        if (!response.isError && response.displayText.isNotBlank()) {
            val cleaned = stripMarker(response.displayText)
            Dev.info(log, "extract.standard_success",
                "exchangeId" to response.exchangeId,
                "length" to cleaned.length
            )
            return ExtractionResult.Success(cleaned)
        }

        // Strategy 2: Scan raw JSON for marker
        if (!rawJson.isNullOrBlank()) {
            val fromMarker = extractByMarker(rawJson)
            if (fromMarker != null) {
                Dev.info(log, "extract.marker_success",
                    "exchangeId" to response.exchangeId,
                    "length" to fromMarker.length
                )
                return ExtractionResult.Success(fromMarker)
            }
        }

        // Strategy 3: All extraction failed
        Dev.warn(log, "extract.failed", null,
            "exchangeId" to response.exchangeId,
            "errorType" to response.errorType?.name,
            "errorMessage" to response.errorMessage?.take(100)
        )

        return ExtractionResult.Failed(
            reason = response.errorMessage ?: "Could not extract summary from response",
            rawJson = rawJson
        )
    }

    /**
     * Attempt to extract summary by scanning for the "Summary: " marker.
     *
     * This is the fallback strategy for unknown response formats. It recursively
     * scans all string values in the JSON looking for one that starts with our marker.
     *
     * @param rawJson The raw JSON response string.
     * @return The summary text (marker stripped), or null if not found.
     */
    private fun extractByMarker(rawJson: String): String? {
        return try {
            val jsonElement = Json.parseToJsonElement(rawJson)
            findMarkerInJson(jsonElement)
        } catch (e: Exception) {
            Dev.warn(log, "extract.marker_parse_failed", e,
                "rawPreview" to rawJson.take(200)
            )
            // Last ditch: maybe it's not even JSON, just search the raw string
            findMarkerInText(rawJson)
        }
    }

    /**
     * Recursively search a JSON structure for a string starting with "Summary: ".
     *
     * Traverses objects, arrays, and primitives looking for our marker.
     * Returns the first match found (with marker stripped).
     */
    private fun findMarkerInJson(element: JsonElement): String? {
        return when (element) {
            is JsonPrimitive -> {
                if (element.isString) {
                    val text = element.content
                    if (text.startsWith(SUMMARY_MARKER, ignoreCase = true)) {
                        text.substring(SUMMARY_MARKER.length).trim()
                    } else {
                        null
                    }
                } else {
                    null
                }
            }
            is JsonArray -> {
                // Search each element, return first match
                element.firstNotNullOfOrNull { findMarkerInJson(it) }
            }
            is JsonObject -> {
                // Search each value, return first match
                element.values.firstNotNullOfOrNull { findMarkerInJson(it) }
            }
        }
    }

    /**
     * Search plain text for the "Summary: " marker.
     *
     * Used when the response isn't valid JSON at all.
     * Finds the marker and returns everything after it until end of line or string.
     */
    private fun findMarkerInText(text: String): String? {
        val markerIndex = text.indexOf(SUMMARY_MARKER, ignoreCase = true)
        if (markerIndex == -1) return null

        val afterMarker = text.substring(markerIndex + SUMMARY_MARKER.length)
        // Take until end of line or end of string, then trim
        val endIndex = afterMarker.indexOfAny(charArrayOf('\n', '\r', '"'))
        return if (endIndex == -1) {
            afterMarker.trim()
        } else {
            afterMarker.substring(0, endIndex).trim()
        }
    }

    /**
     * Strip the "Summary: " marker prefix if present.
     *
     * The marker is just an extraction aid — we don't want it in the stored summary.
     */
    private fun stripMarker(text: String): String {
        val trimmed = text.trim()
        return if (trimmed.startsWith(SUMMARY_MARKER, ignoreCase = true)) {
            trimmed.substring(SUMMARY_MARKER.length).trim()
        } else {
            trimmed
        }
    }

    // ==================== Result Types ====================

    /**
     * Result of a summary extraction attempt.
     *
     * Sealed class to clearly distinguish between:
     * - Success: we got a summary
     * - Failed: extraction failed, contains reason for logging/debugging
     */
    sealed class ExtractionResult {
        /**
         * Summary was successfully extracted.
         * @param summary The clean summary text (no marker prefix).
         */
        data class Success(val summary: String) : ExtractionResult()

        /**
         * Extraction failed.
         * @param reason Human-readable explanation of what went wrong.
         * @param rawJson The raw response for debugging (can be shown to user as last resort).
         */
        data class Failed(val reason: String, val rawJson: String?) : ExtractionResult()

        /** Convenience check for success. */
        val isSuccess: Boolean get() = this is Success

        /** Get summary if successful, null otherwise. */
        fun summaryOrNull(): String? = (this as? Success)?.summary
    }
}