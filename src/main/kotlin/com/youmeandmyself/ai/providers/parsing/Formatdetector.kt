package com.youmeandmyself.ai.providers.parsing

import kotlinx.serialization.json.*

/**
 * Pure, stateless detection of JSON response structure.
 *
 * This class examines raw JSON and determines:
 * - Which known schema it matches (OpenAI, Gemini, Anthropic, Error)
 * - The JSONPath to the content
 * - Token usage metadata if present
 *
 * It does NOT:
 * - Make decisions about error handling
 * - Extract the actual content
 * - Show any UI
 *
 * The orchestration and decision-making is done by [ResponseParser].
 */
object FormatDetector {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Detect the format of a JSON response.
     *
     * @param rawJson The raw JSON string from the provider
     * @return Detection result with schema type, paths, and confidence
     */
    fun detect(rawJson: String): DetectionResult {
        val jsonElement = try {
            json.parseToJsonElement(rawJson)
        } catch (e: Exception) {
            return DetectionResult(
                schema = DetectionSchema.UNKNOWN,
                contentPath = null,
                errorPath = null,
                confidence = Confidence.LOW
            )
        }

        if (jsonElement !is JsonObject) {
            return DetectionResult(
                schema = DetectionSchema.UNKNOWN,
                contentPath = null,
                errorPath = null,
                confidence = Confidence.LOW
            )
        }

        // Check for error structure first (common across providers)
        if (isErrorResponse(jsonElement)) {
            return detectErrorFormat(jsonElement)
        }

        // Try known success schemas
        return detectOpenAi(jsonElement)
            ?: detectGemini(jsonElement)
            ?: detectAnthropic(jsonElement)
            ?: DetectionResult(
                schema = DetectionSchema.UNKNOWN,
                contentPath = null,
                errorPath = null,
                confidence = Confidence.LOW
            )
    }

    /**
     * Check if this looks like an error response.
     */
    private fun isErrorResponse(obj: JsonObject): Boolean {
        return obj.containsKey("error")
    }

    /**
     * Detect error format and extract error path.
     */
    private fun detectErrorFormat(obj: JsonObject): DetectionResult {
        val error = obj["error"]

        val errorPath = when {
            // Standard: {"error": {"message": "..."}}
            error is JsonObject && error.containsKey("message") -> "$.error.message"
            // Alternative: {"error": "string message"}
            error is JsonPrimitive -> "$.error"
            else -> null
        }

        return DetectionResult(
            schema = DetectionSchema.ERROR,
            contentPath = null,
            errorPath = errorPath,
            confidence = if (errorPath != null) Confidence.HIGH else Confidence.MEDIUM
        )
    }

    /**
     * Detect OpenAI/OpenAI-compatible format.
     *
     * Expected structure:
     * {
     *   "choices": [{
     *     "message": {"content": "..."},
     *     "finish_reason": "stop"
     *   }],
     *   "usage": {"prompt_tokens": N, "completion_tokens": N, "total_tokens": N}
     * }
     */
    private fun detectOpenAi(obj: JsonObject): DetectionResult? {
        val choices = obj["choices"] as? JsonArray ?: return null
        if (choices.isEmpty()) return null

        val firstChoice = choices[0] as? JsonObject ?: return null
        val message = firstChoice["message"] as? JsonObject ?: return null

        if (!message.containsKey("content")) return null

        // Extract metadata
        val finishReason = firstChoice["finish_reason"]?.jsonPrimitive?.contentOrNull
        val usage = extractOpenAiUsage(obj)
        val requestId = obj["id"]?.jsonPrimitive?.contentOrNull

        return DetectionResult(
            schema = DetectionSchema.OPENAI,
            contentPath = "$.choices[0].message.content",
            errorPath = null,
            confidence = Confidence.HIGH,
            tokenUsage = usage,
            requestId = requestId,
            finishReason = finishReason
        )
    }

    /**
     * Detect Google Gemini format.
     *
     * Expected structure:
     * {
     *   "candidates": [{
     *     "content": {"parts": [{"text": "..."}]},
     *     "finishReason": "STOP"
     *   }],
     *   "usageMetadata": {"promptTokenCount": N, "candidatesTokenCount": N, "totalTokenCount": N}
     * }
     */
    private fun detectGemini(obj: JsonObject): DetectionResult? {
        val candidates = obj["candidates"] as? JsonArray ?: return null
        if (candidates.isEmpty()) return null

        val firstCandidate = candidates[0] as? JsonObject ?: return null
        val content = firstCandidate["content"] as? JsonObject ?: return null
        val parts = content["parts"] as? JsonArray ?: return null

        if (parts.isEmpty()) return null

        // Check that at least one part has text
        val hasText = parts.any { part ->
            (part as? JsonObject)?.containsKey("text") == true
        }
        if (!hasText) return null

        // Extract metadata
        val finishReason = firstCandidate["finishReason"]?.jsonPrimitive?.contentOrNull
        val usage = extractGeminiUsage(obj)
        val requestId = obj["responseId"]?.jsonPrimitive?.contentOrNull

        return DetectionResult(
            schema = DetectionSchema.GEMINI,
            contentPath = "$.candidates[0].content.parts[*].text",
            errorPath = null,
            confidence = Confidence.HIGH,
            tokenUsage = usage,
            requestId = requestId,
            finishReason = finishReason
        )
    }

    /**
     * Detect Anthropic Claude format.
     *
     * Expected structure:
     * {
     *   "content": [{"type": "text", "text": "..."}],
     *   "usage": {"input_tokens": N, "output_tokens": N}
     * }
     */
    private fun detectAnthropic(obj: JsonObject): DetectionResult? {
        val content = obj["content"] as? JsonArray ?: return null
        if (content.isEmpty()) return null

        // Check that it looks like Anthropic format (has type field in content blocks)
        val firstBlock = content[0] as? JsonObject ?: return null
        val type = firstBlock["type"]?.jsonPrimitive?.contentOrNull

        if (type != "text") return null
        if (!firstBlock.containsKey("text")) return null

        // Extract metadata
        val usage = extractAnthropicUsage(obj)
        val requestId = obj["id"]?.jsonPrimitive?.contentOrNull
        val stopReason = obj["stop_reason"]?.jsonPrimitive?.contentOrNull

        return DetectionResult(
            schema = DetectionSchema.ANTHROPIC,
            contentPath = "$.content[*].text",
            errorPath = null,
            confidence = Confidence.HIGH,
            tokenUsage = usage,
            requestId = requestId,
            finishReason = stopReason
        )
    }

    // ==================== Token Usage Extraction ====================

    private fun extractOpenAiUsage(obj: JsonObject): DetectionTokenUsage? {
        val usage = obj["usage"] as? JsonObject ?: return null
        return DetectionTokenUsage(
            promptTokens = usage["prompt_tokens"]?.jsonPrimitive?.intOrNull,
            completionTokens = usage["completion_tokens"]?.jsonPrimitive?.intOrNull,
            totalTokens = usage["total_tokens"]?.jsonPrimitive?.intOrNull
        )
    }

    private fun extractGeminiUsage(obj: JsonObject): DetectionTokenUsage? {
        val usage = obj["usageMetadata"] as? JsonObject ?: return null
        return DetectionTokenUsage(
            promptTokens = usage["promptTokenCount"]?.jsonPrimitive?.intOrNull,
            completionTokens = usage["candidatesTokenCount"]?.jsonPrimitive?.intOrNull,
            totalTokens = usage["totalTokenCount"]?.jsonPrimitive?.intOrNull
        )
    }

    private fun extractAnthropicUsage(obj: JsonObject): DetectionTokenUsage? {
        val usage = obj["usage"] as? JsonObject ?: return null
        return DetectionTokenUsage(
            promptTokens = usage["input_tokens"]?.jsonPrimitive?.intOrNull,
            completionTokens = usage["output_tokens"]?.jsonPrimitive?.intOrNull,
            totalTokens = null // Anthropic doesn't provide total, could calculate
        )
    }
}

// Extension to safely get primitive content
private val JsonPrimitive.contentOrNull: String?
    get() = if (isString) content else null

private val JsonPrimitive.intOrNull: Int?
    get() = try { int } catch (e: Exception) { null }