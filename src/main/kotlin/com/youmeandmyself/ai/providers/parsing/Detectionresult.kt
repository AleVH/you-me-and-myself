package com.youmeandmyself.ai.providers.parsing

/**
 * Result of format detection - identifies the JSON schema and paths to content.
 *
 * This is the output of [FormatDetector], which is a pure function that
 * examines JSON structure without making decisions about how to handle it.
 *
 * Note: Uses [Confidence] from the parsing package (defined in JsonWalkHeuristic.kt)
 * to avoid enum duplication.
 *
 * @property schema The detected format type
 * @property contentPath JSONPath-like pointer to content (e.g., "$.choices[0].message.content")
 * @property errorPath JSONPath-like pointer to error message if applicable
 * @property confidence How confident we are in this detection
 * @property tokenUsage Extracted token usage if available in response
 * @property requestId Provider's request/trace ID if available (useful for support)
 * @property finishReason Why the model stopped generating (e.g., "stop", "length")
 */
data class DetectionResult(
    val schema: DetectionSchema,
    val contentPath: String?,
    val errorPath: String?,
    val confidence: Confidence,
    val tokenUsage: DetectionTokenUsage? = null,
    val requestId: String? = null,
    val finishReason: String? = null
)

/**
 * Known response schemas that FormatDetector can identify.
 *
 * This is specific to the detection phase. The parsing phase uses
 * [DetectedSchema] from ParseMetadata.kt which has more granular values.
 */
enum class DetectionSchema {
    /** OpenAI chat completions format: choices[0].message.content */
    OPENAI,

    /** Google Gemini format: candidates[0].content.parts[].text */
    GEMINI,

    /** Anthropic Claude format: content[0].text */
    ANTHROPIC,

    /** Generic error format: error.message */
    ERROR,

    /** Couldn't determine the schema */
    UNKNOWN
}

/**
 * Token usage information extracted during detection.
 * All fields optional since different providers include different subsets.
 */
data class DetectionTokenUsage(
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val totalTokens: Int? = null
)