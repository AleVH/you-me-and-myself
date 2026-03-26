package com.youmeandmyself.ai.providers

import com.youmeandmyself.ai.chat.orchestrator.RequestBlocks
import com.youmeandmyself.storage.model.ConversationTurn

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
 * ## Conversation History (Phase A3)
 *
 * The [chat] method accepts an optional [history] parameter — a list of
 * [ConversationTurn] objects representing previous turns in the conversation.
 * When provided, the provider builds a multi-message request (messages[] for
 * OpenAI, contents[] for Gemini) instead of a single-message request.
 *
 * History is assembled by [ChatOrchestrator] using [ConversationManager.buildHistory()].
 * The provider never fetches history itself — it's a pure HTTP client.
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
     * ## History (Phase A3)
     *
     * When [history] is non-empty, the provider builds a multi-message request:
     * - OpenAI/Custom: messages = [{history turns...}, {current prompt as "user"}]
     * - Gemini: contents = [{history turns...}, {current prompt}]
     *
     * Historical turns preserve whatever content was stored at the time (including
     * any IDE context that was part of the original prompt). Only the current
     * message (the [prompt] parameter) carries fresh IDE context.
     *
     * ## System Prompt (Block 4)
     *
     * When [systemPrompt] is provided, it is inserted as the FIRST message in the
     * messages array:
     * - OpenAI/Custom: role="system", content=systemPrompt
     * - Gemini: role="user", content="[System Instructions] $systemPrompt"
     *   (Gemini has no native system role)
     *
     * The system prompt comes from the Assistant Profile system — a user-authored
     * personality profile that is automatically summarized and prepended to every request.
     * ChatOrchestrator reads it from AssistantProfileService.getSystemPrompt().
     *
     * Message order:
     *   [system prompt] → [history turns] → [current user message with IDE context]
     *
     * @param prompt The complete prompt to send (user input + any context)
     * @param history Previous conversation turns for multi-turn context.
     *   Empty list = single-message request (backwards compatible).
     *   Assembled by ChatOrchestrator via ConversationManager.buildHistory().
     * @param systemPrompt Optional system prompt from the Assistant Profile.
     *   When non-null, injected as the first message before history and prompt.
     *   Null = no system prompt (backwards compatible).
     * @return [ProviderResponse] with raw JSON, HTTP status, and parsed content
     */
    suspend fun chat(
        prompt: String,
        history: List<ConversationTurn> = emptyList(),
        systemPrompt: String? = null
    ): ProviderResponse

    /**
     * Send a structured chat request using the RequestBlocks model.
     *
     * ## Phase 1 — Structured Request
     *
     * This method accepts the four independent blocks (profile, history,
     * context, message) as a structured data class instead of a flat prompt
     * string. The provider handles serialization into the API's message format.
     *
     * The blocks are serialized as:
     * - [RequestBlocks.profile] → system message (first)
     * - [RequestBlocks.compactedHistory] → summary turn (when non-null, Phase 4)
     * - [RequestBlocks.verbatimHistory] → user/assistant message pairs
     * - [RequestBlocks.context] + [RequestBlocks.userMessage] → final user message
     *
     * The API output is identical to the legacy [chat] method — the restructuring
     * is internal. The concatenation of context + user message now happens at
     * serialization time in the provider instead of in the assembler.
     *
     * @param requestBlocks The structured request with four independent blocks
     * @return [ProviderResponse] with raw JSON, HTTP status, and parsed content
     *
     * @see RequestBlocks
     * @see com.youmeandmyself.ai.chat.context.ContextBlock
     */
    suspend fun chat(requestBlocks: RequestBlocks): ProviderResponse

    /**
     * Send a summarization request to the AI provider.
     *
     * Uses summary-specific request settings (lower temperature, shorter responses).
     * Called by [SummarizationService] for both code and conversation summarization.
     *
     * NOTE: Summarization is always single-shot — no history parameter needed.
     * Each summary request is self-contained (file content + prompt template).
     *
     * @param prompt The summarization prompt (content + template)
     * @return [ProviderResponse] with raw JSON, HTTP status, and parsed content
     */
    suspend fun summarize(prompt: String): ProviderResponse
}