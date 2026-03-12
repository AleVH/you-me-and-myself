// File: src/main/kotlin/com/youmeandmyself/storage/model/ConversationTurn.kt
package com.youmeandmyself.storage.model

/**
 * A single turn in a conversation history, sent to the AI provider for multi-turn context.
 *
 * This represents what gets included in the `messages[]` array when calling AI APIs.
 * It is NOT the same as an AiExchange — it's a simplified view optimized for the
 * provider request format.
 *
 * ## Relationship to AiExchange
 *
 * Each AiExchange (stored in JSONL/SQLite) can produce two ConversationTurns:
 *   1. USER turn from exchange.request.input
 *   2. ASSISTANT turn from the parsed assistant response text
 *
 * ## Relationship to IDE Context
 *
 * IDE context (from ContextOrchestrator) is attached to the USER turn content.
 * Historical turns preserve whatever context was attached at the time.
 * Only the latest/current turn gets fresh IDE context.
 *
 * @property role Who produced this turn (USER or ASSISTANT)
 * @property content The text content of this turn
 */
data class ConversationTurn(
    val role: TurnRole,
    val content: String
)

/**
 * Role in a conversation turn — provider-agnostic.
 *
 * This enum represents the LOGICAL role of a message in a conversation.
 * It deliberately has NO vendor-specific methods (no toOpenAiRole, no toGeminiRole).
 *
 * ## Provider-Agnostic Principle
 *
 * TurnRole is a shared model class used across the codebase (storage, orchestrator,
 * conversation manager). Putting vendor-specific role mappings here would leak
 * protocol knowledge into the data layer, violating the agnostic architecture.
 *
 * ## Where Role Mapping Lives
 *
 * Protocol-specific role strings (e.g., "user"/"assistant"/"system" for OpenAI,
 * "user"/"model" for Gemini) are mapped inside [GenericLlmProvider], which is
 * the ONLY class that knows about specific API protocols. The mapping is done
 * via private extension functions on TurnRole within GenericLlmProvider.
 *
 * ## Adding New Roles
 *
 * If a new role is needed (e.g., TOOL for function-calling responses), add the
 * enum value here and add its protocol mappings in GenericLlmProvider. No other
 * files need to change.
 *
 * @see GenericLlmProvider for protocol-specific role mapping
 */
enum class TurnRole {
    /** Message from the user. Maps to "user" in all known protocols. */
    USER,

    /** Response from the AI model. Maps to "assistant" (OpenAI) or "model" (Gemini). */
    ASSISTANT,

    /**
     * System instruction or injected context.
     *
     * Used for:
     * - Conversation summary injection (Phase B: compressed history as system context)
     * - System prompt from AiProfile (Block 4: persona/instructions)
     *
     * Maps to "system" in OpenAI. Gemini has no system role — GenericLlmProvider
     * handles this by sending SYSTEM content as "user" with a "[System] " prefix.
     */
    SYSTEM
}