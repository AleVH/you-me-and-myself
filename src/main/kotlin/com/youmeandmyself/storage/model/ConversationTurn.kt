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
 * Role in a conversation turn.
 *
 * Maps directly to the "role" field in provider API messages:
 * - USER → "user" in OpenAI/Gemini/etc.
 * - ASSISTANT → "assistant" in OpenAI, "model" in Gemini
 * - SYSTEM → "system" in OpenAI (used for conversation summary injection)
 */
enum class TurnRole {
    USER,
    ASSISTANT,
    SYSTEM;

    /**
     * Convert to the provider-specific role string.
     * OpenAI protocol uses "user"/"assistant"/"system".
     * Gemini protocol uses "user"/"model" (no system — injected as user turn).
     */
    fun toOpenAiRole(): String = when (this) {
        USER -> "user"
        ASSISTANT -> "assistant"
        SYSTEM -> "system"
    }

    fun toGeminiRole(): String = when (this) {
        USER -> "user"
        ASSISTANT -> "model"
        SYSTEM -> "user"  // Gemini has no system role; send as user with clear framing
    }
}