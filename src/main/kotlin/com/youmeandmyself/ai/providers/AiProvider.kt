package com.youmeandmyself.ai.providers

import com.youmeandmyself.ai.providers.parsing.ParsedResponse

/**
 * Core contract every AI provider must implement.
 * Implementations should be side-effect free and throw exceptions with useful messages on failure.
 */
interface AiProvider {
    /** A short, unique id (e.g., "openai", "gemini", "mock"). */
    val id: String

    /** Human-friendly name for UI. */
    val displayName: String

    /**
     * Quick health check. Should be fast, low cost, and not consume significant quota.
     * Return a brief success message. Throw on failure.
     */
    suspend fun ping(): String

    /**
     * Return a model-generated reply for the given prompt.
     * This is used by the Chat UI; keep it simple and synchronous (no streaming yet).
     *
     * @return [ParsedResponse] containing the extracted text, error info, and metadata
     */
    suspend fun chat(prompt: String): ParsedResponse

    /**
     * Optional helper for UIs that list available model ids.
     * Providers that cannot enumerate models can just return emptyList().
     */
    suspend fun listModels(): List<String> = emptyList()
}

/**
 * DEPRECATED: Use ParsedResponse instead.
 *
 * This class is kept temporarily for backward compatibility during migration.
 * It will be removed once all consumers are updated to use ParsedResponse.
 *
 * @property text The extracted/parsed response text for display
 * @property rawJson The complete, unmodified JSON response from the provider
 * @property httpStatus The HTTP status code from the response
 */
@Deprecated(
    message = "Use ParsedResponse instead - it provides richer error handling and metadata",
    replaceWith = ReplaceWith("ParsedResponse", "com.youmeandmyself.ai.providers.parsing.ParsedResponse")
)
data class ChatResult(
    val text: String,
    val rawJson: String,
    val httpStatus: Int? = null
)