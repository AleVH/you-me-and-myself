package com.youmeandmyself.storage.model

/**
 * Lightweight DTO for conversation history loading.
 *
 * Used by [LocalStorageFacade.getExchangesForConversation] to return
 * only the fields needed to reconstruct the chat display in a restored tab.
 * Deliberately simpler than [AiExchange] — no raw JSON, no derived metadata,
 * no token breakdown.
 *
 * The orchestrator maps these to [BridgeMessage.HistoryMessageDto] before
 * sending to the frontend.
 *
 * @param id Exchange ID (used as exchangeId in the frontend for starring)
 * @param userPrompt The user's message text, or null if not stored
 * @param assistantText The cached assistant response, or null if not yet cached
 * @param timestamp ISO timestamp string from storage
 * @param isStarred Whether this exchange is starred (favourite)
 */
data class ConversationExchange(
    val id: String,
    val userPrompt: String?,
    val assistantText: String?,
    val timestamp: String,
    val isStarred: Boolean
)