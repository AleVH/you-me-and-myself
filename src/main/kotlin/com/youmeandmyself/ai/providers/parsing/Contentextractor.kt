package com.youmeandmyself.ai.providers.parsing

import kotlinx.serialization.json.*

/**
 * Extracts actual content from JSON using the paths identified by FormatDetector.
 *
 * This is separated from detection because:
 * 1. Detection identifies WHAT the format is
 * 2. Extraction pulls out the actual content
 *
 * They're different responsibilities with different failure modes.
 */
object ContentExtractor {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Extract content from JSON using the detected schema.
     *
     * @param rawJson The raw JSON response
     * @param schema The detected schema type
     * @return The extracted text content, or null if extraction failed
     */
    fun extract(rawJson: String, schema: DetectionSchema): String? {
        return try {
            val obj = json.parseToJsonElement(rawJson) as? JsonObject ?: return null

            when (schema) {
                DetectionSchema.OPENAI -> extractOpenAi(obj)
                DetectionSchema.GEMINI -> extractGemini(obj)
                DetectionSchema.ANTHROPIC -> extractAnthropic(obj)
                DetectionSchema.ERROR -> extractError(obj)
                DetectionSchema.UNKNOWN -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Extract error message from JSON.
     */
    fun extractErrorMessage(rawJson: String): String? {
        return try {
            val obj = json.parseToJsonElement(rawJson) as? JsonObject ?: return null
            extractError(obj)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Extract from OpenAI format: choices[0].message.content
     */
    private fun extractOpenAi(obj: JsonObject): String? {
        val choices = obj["choices"] as? JsonArray ?: return null
        if (choices.isEmpty()) return null

        val firstChoice = choices[0] as? JsonObject ?: return null
        val message = firstChoice["message"] as? JsonObject ?: return null
        val content = message["content"]

        return when (content) {
            is JsonPrimitive -> content.contentOrNull
            is JsonNull -> null
            else -> null
        }
    }

    /**
     * Extract from Gemini format: candidates[0].content.parts[*].text (joined)
     */
    private fun extractGemini(obj: JsonObject): String? {
        val candidates = obj["candidates"] as? JsonArray ?: return null
        if (candidates.isEmpty()) return null

        val firstCandidate = candidates[0] as? JsonObject ?: return null
        val content = firstCandidate["content"] as? JsonObject ?: return null
        val parts = content["parts"] as? JsonArray ?: return null

        // Join all text parts
        val texts = parts.mapNotNull { part ->
            (part as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull
        }

        return if (texts.isNotEmpty()) texts.joinToString("") else null
    }

    /**
     * Extract from Anthropic format: content[*].text (joined, text blocks only)
     */
    private fun extractAnthropic(obj: JsonObject): String? {
        val content = obj["content"] as? JsonArray ?: return null
        if (content.isEmpty()) return null

        // Join all text blocks
        val texts = content.mapNotNull { block ->
            val blockObj = block as? JsonObject ?: return@mapNotNull null
            val type = blockObj["type"]?.jsonPrimitive?.contentOrNull

            if (type == "text") {
                blockObj["text"]?.jsonPrimitive?.contentOrNull
            } else {
                null
            }
        }

        return if (texts.isNotEmpty()) texts.joinToString("") else null
    }

    /**
     * Extract error message from error object.
     */
    private fun extractError(obj: JsonObject): String? {
        val error = obj["error"] ?: return null

        return when (error) {
            is JsonObject -> error["message"]?.jsonPrimitive?.contentOrNull
            is JsonPrimitive -> error.contentOrNull
            else -> null
        }
    }
}

// Extension
private val JsonPrimitive.contentOrNull: String?
    get() = if (isString) content else null