package com.youmeandmyself.ai.chat.conversation

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.youmeandmyself.ai.providers.generic.GenericLlmProvider
import com.youmeandmyself.ai.settings.AiProfilesState
import com.youmeandmyself.dev.Dev
import com.youmeandmyself.storage.LocalStorageFacade
import com.youmeandmyself.storage.model.*

/**
 * Progressive history compaction service (Phase 4).
 *
 * ## Purpose
 *
 * Long conversations accumulate many turns. Without compaction, the entire
 * verbatim history consumes an ever-growing share of the context window,
 * leaving less room for context (code summaries) and the user's message.
 *
 * This service summarizes older turns into a compact text, which is injected
 * into the request as `compactedHistory` in [RequestBlocks]. The JSONL
 * source of truth is NEVER modified — compaction is a context window
 * optimization overlay.
 *
 * ## Progressive Compaction Strategy (§10.1)
 *
 * 1. **First pass:** All turns except the last 5 are summarized via an AI
 *    call. The summary replaces them in the compacted history slot.
 * 2. **Second pass:** If the limit is hit again, the same process applies
 *    to new turns (excluding last 5). Existing compacted summary is kept.
 * 3. **Final pass:** When individual turn compaction can no longer free
 *    enough space, all compacted summaries are themselves summarized into
 *    a single summary-of-summaries.
 *
 * ## Proactive Trigger
 *
 * Compaction triggers proactively at 80% of the context window, not at the
 * hard ceiling. This avoids blocking the user's send with a compaction delay.
 *
 * ## Storage
 *
 * Compacted summaries are stored as JSONL exchanges with purpose=CHAT_SUMMARY
 * in the summaries/ folder. The conversation_id links them back to the
 * original conversation.
 *
 * @see com.youmeandmyself.ai.chat.orchestrator.RequestBlocks
 * @see com.youmeandmyself.storage.model.ExchangePurpose.CHAT_SUMMARY
 */
@Service(Service.Level.PROJECT)
class HistoryCompactionService(
    private val project: Project,
) {
    private val log = Logger.getInstance(HistoryCompactionService::class.java)

    private val storage: LocalStorageFacade by lazy {
        LocalStorageFacade.getInstance(project)
    }

    private val conversationManager: ConversationManager by lazy {
        ConversationManager.getInstance(project)
    }

    /** Verbatim window — how many recent turns are kept uncompacted. */
    val verbatimWindow: Int = 5

    /**
     * Check if compaction is needed for a conversation.
     *
     * Compares the estimated token usage of the full history against the
     * context window size. Returns true if usage exceeds the threshold.
     *
     * @param conversationId The conversation to check
     * @param contextWindowSize The provider's context window size in tokens
     * @param currentRequestTokens Estimated tokens already consumed by profile + context + message
     * @param threshold Fraction of context window that triggers compaction (default 0.8)
     * @return true if compaction should be triggered
     */
    fun needsCompaction(
        conversationId: String,
        contextWindowSize: Int,
        currentRequestTokens: Int,
        threshold: Double = 0.80
    ): Boolean {
        val allTurns = conversationManager.buildHistory(conversationId, Int.MAX_VALUE)
        val historyTokens = allTurns.sumOf { it.content.length / 4 }  // chars/4 heuristic
        val totalTokens = currentRequestTokens + historyTokens
        val ratio = totalTokens.toDouble() / contextWindowSize

        Dev.info(log, "compaction.check",
            "conversationId" to conversationId,
            "totalTurns" to allTurns.size,
            "historyTokens" to historyTokens,
            "totalTokens" to totalTokens,
            "contextWindow" to contextWindowSize,
            "ratio" to "%.2f".format(ratio),
            "threshold" to "%.2f".format(threshold),
            "needed" to (ratio > threshold)
        )

        return ratio > threshold
    }

    /**
     * Get existing compacted summary for a conversation.
     *
     * Queries the JSONL/SQLite for the most recent CHAT_SUMMARY exchange
     * linked to this conversation.
     *
     * @param conversationId The conversation to check
     * @return The compacted summary text, or null if no summary exists
     */
    fun getCompactedSummary(conversationId: String): String? {
        return try {
            val summary = storage.getLatestChatSummary(conversationId)
            if (summary != null) {
                Dev.info(log, "compaction.existing_summary_found",
                    "conversationId" to conversationId,
                    "summaryLength" to summary.length
                )
            }
            summary
        } catch (e: Exception) {
            Dev.warn(log, "compaction.lookup_failed", e,
                "conversationId" to conversationId
            )
            null
        }
    }

    /**
     * Compact older conversation turns into a summary.
     *
     * Takes all turns except the last [verbatimWindow] and summarizes them
     * via an AI call. The summary is stored as a CHAT_SUMMARY exchange.
     *
     * This is an expensive operation (API call) and should be called
     * proactively, not at send time.
     *
     * @param conversationId The conversation to compact
     * @param provider The AI provider to use for summarization
     * @return The compacted summary text, or null if compaction failed or wasn't needed
     */
    suspend fun compact(
        conversationId: String,
        provider: GenericLlmProvider
    ): String? {
        val allTurns = conversationManager.buildHistory(conversationId, Int.MAX_VALUE)

        // Not enough turns to compact
        if (allTurns.size <= verbatimWindow) {
            Dev.info(log, "compaction.skip",
                "conversationId" to conversationId,
                "totalTurns" to allTurns.size,
                "verbatimWindow" to verbatimWindow,
                "reason" to "not enough turns"
            )
            return null
        }

        val turnsToCompact = allTurns.dropLast(verbatimWindow)
        val existingSummary = getCompactedSummary(conversationId)

        Dev.info(log, "compaction.start",
            "conversationId" to conversationId,
            "totalTurns" to allTurns.size,
            "turnsToCompact" to turnsToCompact.size,
            "verbatimKept" to verbatimWindow,
            "hasExistingSummary" to (existingSummary != null)
        )

        // Build the compaction prompt
        val prompt = buildCompactionPrompt(turnsToCompact, existingSummary)

        return try {
            val response = provider.chat(
                com.youmeandmyself.ai.chat.orchestrator.RequestBlocks(
                    profile = null,  // No personality for summarization
                    compactedHistory = null,
                    verbatimHistory = emptyList(),
                    context = com.youmeandmyself.ai.chat.context.ContextBlock(
                        summaries = emptyList(),
                        raw = emptyList(),
                        other = emptyList()
                    ),
                    userMessage = prompt
                )
            )

            val summaryText: String = response.displayText
            if (summaryText.isBlank()) {
                Dev.warn(log, "compaction.empty_response", null,
                    "conversationId" to conversationId
                )
                return null
            }

            // Store the summary as a CHAT_SUMMARY exchange
            // projectId is needed for save — get it from storage config
            val projectId = storage.resolveProjectId()
            if (projectId != null) {
                storage.saveChatSummary(conversationId, summaryText, turnsToCompact.size, projectId)
            }

            Dev.info(log, "compaction.complete",
                "conversationId" to conversationId,
                "turnsCompacted" to turnsToCompact.size,
                "summaryLength" to summaryText.length,
                "summaryTokens" to (summaryText.length / 4)
            )

            summaryText
        } catch (e: Exception) {
            Dev.error(log, "compaction.failed", e,
                "conversationId" to conversationId
            )
            null
        }
    }

    /**
     * Build the prompt for conversation compaction.
     *
     * If an existing summary exists, we're in pass 2+ — include it
     * and ask for a combined summary.
     */
    private fun buildCompactionPrompt(
        turns: List<ConversationTurn>,
        existingSummary: String?
    ): String {
        val turnText = turns.joinToString("\n\n") { turn ->
            val role = when (turn.role) {
                TurnRole.USER -> "User"
                TurnRole.ASSISTANT -> "Assistant"
                TurnRole.SYSTEM -> "System"
            }
            "$role: ${turn.content}"
        }

        return if (existingSummary != null) {
            """
            |You are summarizing a conversation for context continuity.
            |
            |An earlier part of this conversation was already summarized:
            |
            |--- PREVIOUS SUMMARY ---
            |$existingSummary
            |--- END PREVIOUS SUMMARY ---
            |
            |The following additional turns happened after that summary:
            |
            |--- CONVERSATION TURNS ---
            |$turnText
            |--- END CONVERSATION TURNS ---
            |
            |Create a single, coherent summary that combines the previous summary
            |with the new turns. Focus on: key decisions made, questions asked and
            |answered, code discussed, action items, and any context the AI needs
            |to continue the conversation naturally.
            |
            |Keep the summary concise but complete — it replaces the full history
            |in future requests. Do not include greetings, filler, or meta-commentary.
            """.trimMargin()
        } else {
            """
            |You are summarizing a conversation for context continuity.
            |
            |--- CONVERSATION TURNS ---
            |$turnText
            |--- END CONVERSATION TURNS ---
            |
            |Create a concise summary of this conversation. Focus on:
            |key decisions made, questions asked and answered, code discussed,
            |action items, and any context the AI needs to continue the
            |conversation naturally.
            |
            |Keep the summary concise but complete — it replaces the full history
            |in future requests. Do not include greetings, filler, or meta-commentary.
            """.trimMargin()
        }
    }

    companion object {
        fun getInstance(project: Project): HistoryCompactionService =
            project.getService(HistoryCompactionService::class.java)
    }
}
