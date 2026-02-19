package com.youmeandmyself.ai.providers

import com.youmeandmyself.ai.providers.parsing.ParsedResponse

/**
 * Core contract every AI provider must implement.
 *
 * ## Purpose
 *
 * This interface abstracts the specifics of different LLM providers (OpenAI, Gemini,
 * local models, etc.) behind a common API. The rest of the plugin doesn't need to know
 * which provider is being used — it just calls these methods.
 *
 * ## Methods
 *
 * - **ping()**: Health check, verifies connectivity and credentials
 * - **chat()**: Send a prompt, get a response (for conversations)
 * - **summarize()**: Send code, get a summary (for code summarization)
 * - **listModels()**: Optional enumeration of available models
 *
 * ## Chat vs Summarize
 *
 * Both methods make LLM requests, but they differ in:
 *
 * | Aspect | chat() | summarize() |
 * |--------|--------|-------------|
 * | Purpose | Conversations | Code summaries |
 * | Settings | chatSettings from profile | summarySettings from profile |
 * | Storage | ExchangePurpose.CHAT | ExchangePurpose.SUMMARY |
 * | Temperature | Higher (creative) | Lower (factual) |
 * | Response use | Rendered with markdown/code highlighting | Stored as plain text |
 *
 * ## Implementation Notes
 *
 * Implementations should:
 * - Be side-effect free (no hidden state changes)
 * - Throw exceptions with useful messages on failure
 * - Save raw responses to storage before parsing (never lose data)
 * - Use the appropriate settings (chat vs summary) for each method
 */
interface AiProvider {
    /**
     * A short, unique identifier for this provider instance.
     *
     * Examples: "openai", "gemini", "ollama-local", "my-gemini"
     *
     * Used for:
     * - Logging and debugging
     * - Storage (providerId in chat_exchanges table)
     * - Format hints (different providers may need different parsing)
     */
    val id: String

    /**
     * Human-friendly name displayed in the UI.
     *
     * Examples: "OpenAI GPT-4", "Google Gemini", "Local Ollama"
     *
     * This comes from the profile label, so users can name it whatever they want.
     */
    val displayName: String

    /**
     * Quick health check to verify connectivity and credentials.
     *
     * Should be:
     * - Fast (< 2 seconds)
     * - Low cost (minimal or no token usage)
     * - Non-destructive (read-only operation)
     *
     * Typical implementation: call the provider's "list models" endpoint.
     *
     * @return Brief success message (e.g., "200" or "OK")
     * @throws Exception on failure with descriptive message
     */
    suspend fun ping(): String

    /**
     * Send a chat prompt and get a response.
     *
     * Used for interactive conversations in the chat UI.
     *
     * Flow:
     * 1. Apply chatSettings from profile (temperature, maxTokens, etc.)
     * 2. Make HTTP request to provider
     * 3. Save raw response to storage with ExchangePurpose.CHAT
     * 4. Parse response using ResponseParser
     * 5. Return ParsedResponse
     *
     * The response may need markdown rendering, code highlighting, etc.
     * for display in the chat UI.
     *
     * @param prompt The user's message/question
     * @return ParsedResponse containing extracted text, error info, and metadata
     */
    suspend fun chat(prompt: String): ParsedResponse

    /**
     * Send code for summarization and get a concise summary.
     *
     * Used for generating code summaries in the background.
     *
     * Flow:
     * 1. Apply summarySettings from profile (lower temperature, smaller maxTokens, etc.)
     * 2. Make HTTP request to provider
     * 3. Save raw response to storage with ExchangePurpose.SUMMARY
     * 4. Parse response using ResponseParser
     * 5. Return ParsedResponse
     *
     * The response should be plain text (the prompt instructs the model not to use
     * markdown formatting). It will be stored directly without rendering.
     *
     * @param prompt The summarization prompt (built by SummaryExtractor.buildPrompt())
     * @return ParsedResponse containing extracted summary text or error info
     */
    suspend fun summarize(prompt: String): ParsedResponse

    /**
     * List available models from this provider.
     *
     * Optional — providers that cannot enumerate models should return emptyList().
     *
     * Useful for:
     * - Auto-completing model names in settings UI
     * - Validating that a configured model exists
     *
     * @return List of model identifiers, or empty list if enumeration not supported
     */
    suspend fun listModels(): List<String> = emptyList()
}