package com.youmeandmyself.ai.providers

/**
 * Interface for AI providers — the HTTP abstraction layer.
 *
 * ## Post-Refactoring Contract
 *
 * Implementations of this interface are **pure HTTP clients**. They:
 * 1. Build protocol-specific requests
 * 2. Send them to the API
 * 3. Parse the response
 * 4. Return [ProviderResponse] — done.
 *
 * They do NOT:
 * - Save to storage (orchestrator does this)
 * - Index metadata (orchestrator does this)
 * - Capture IDE context (orchestrator does this)
 * - Manage conversations (orchestrator does this)
 *
 * ## Return Type Change
 *
 * Previously returned [ParsedResponse]. Now returns [ProviderResponse] which
 * wraps ParsedResponse + raw JSON + HTTP status + prompt. The orchestrator
 * needs all of these to persist the complete exchange.
 *
 * ## Implementations
 *
 * - [GenericLlmProvider]: Vendor-agnostic provider supporting OpenAI, Gemini, and Custom protocols
 * - Future: Streaming provider, batch provider, etc.
 */
interface AiProvider {
    /** Unique identifier for this provider instance (matches AiProfile.id). */
    val id: String

    /** Human-friendly name for logging and UI display. */
    val displayName: String

    /**
     * Quick health check — verify connectivity and credentials.
     *
     * Calls a lightweight endpoint (e.g., /v1/models) to check if the
     * API key is valid and the service is reachable.
     *
     * @return HTTP status code as string (e.g., "200")
     * @throws Exception if the network request fails
     */
    suspend fun ping(): String

    /**
     * Send a chat message to the AI provider.
     *
     * Uses chat-specific request settings (higher temperature, longer responses).
     * The prompt may include IDE context assembled by [ContextAssembler].
     *
     * @param prompt The complete prompt to send (user input + any context)
     * @return [ProviderResponse] with raw JSON, HTTP status, and parsed content
     */
    suspend fun chat(prompt: String): ProviderResponse

    /**
     * Send a summarization request to the AI provider.
     *
     * Uses summary-specific request settings (lower temperature, shorter responses).
     * Called by [SummarizationService] for both code and conversation summarization.
     *
     * @param prompt The summarization prompt (content + template)
     * @return [ProviderResponse] with raw JSON, HTTP status, and parsed content
     */
    suspend fun summarize(prompt: String): ProviderResponse
}