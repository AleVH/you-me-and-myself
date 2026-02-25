// File: src/main/kotlin/com/youmeandmyself/ai/chat/conversation/ConversationManager.kt
package com.youmeandmyself.ai.chat.conversation

import com.intellij.openapi.project.Project
import com.youmeandmyself.dev.Dev
import com.youmeandmyself.storage.LocalStorageFacade
import com.youmeandmyself.storage.model.Conversation
import com.youmeandmyself.storage.model.ConversationTurn
import com.youmeandmyself.storage.model.TurnRole
import java.time.Instant
import java.util.UUID

/**
 * Manages the lifecycle of conversations — the grouping unit for multi-turn AI dialogues.
 *
 * This is the single point of access for all conversation operations:
 * - Creating new conversations (when a new chat tab gets its first message)
 * - Listing conversations for the Library view
 * - Loading exchanges for a conversation (for tab rendering and history replay)
 * - Updating metadata after each exchange
 *
 * ## Relationship to Other Components
 *
 * ```
 * ChatPanel / ChatTabManager
 *     ↓ creates/loads conversations
 * ConversationManager (this class)
 *     ↓ reads/writes via
 * LocalStorageFacade
 *     ↓ persists to
 * SQLite (conversations + chat_exchanges tables)
 * JSONL (raw exchange content)
 * ```
 *
 * ## Storage Readiness
 *
 * LocalStorageFacade is initialized asynchronously by StorageInitializer (a postStartupActivity).
 * ConversationManager may be called before initialization completes — for example, if the user
 * sends a message immediately after IDE startup. All database methods check [isStorageReady]
 * and gracefully return null/empty when storage is not yet available. This follows the same
 * pattern as LocalStorageFacade's own `if (mode == StorageMode.OFF) return` guards.
 *
 * ## Thread Safety
 *
 * All database operations go through LocalStorageFacade which manages
 * its own Mutex for write serialization. This class does not need
 * additional synchronization.
 */
class ConversationManager(private val project: Project) {

    private val log = Dev.logger(ConversationManager::class.java)
    private val facade = LocalStorageFacade.getInstance(project)

    // ── Storage readiness ───────────────────────────────────────────────

    /**
     * Check if the storage subsystem is initialized and ready for database operations.
     *
     * StorageInitializer runs as a postStartupActivity — it may not have completed
     * when the user sends their first message. All database methods in this class
     * check this before proceeding and gracefully degrade if storage is not ready.
     */
    private fun isStorageReady(): Boolean {
        if (!facade.isInitialized) {
            Dev.info(log, "conversation.storage_not_ready")
            return false
        }
        return true
    }

    // ── Create ──────────────────────────────────────────────────────────

    /**
     * Create a new conversation.
     *
     * Called when the user sends the first message in a new chat tab.
     * The title is auto-generated from the prompt and can be edited later.
     *
     * @param firstPrompt The user's first message (used for title generation)
     * @param providerId The provider handling this conversation
     * @param modelId The model being used
     * @return The newly created Conversation, or null if storage is not ready
     */
    fun createConversation(
        firstPrompt: String,
        providerId: String,
        modelId: String
    ): Conversation? {
        if (!isStorageReady()) return null

        val projectId = facade.resolveProjectId()
        val now = Instant.now()
        val title = Conversation.titleFromPrompt(firstPrompt)

        val conversation = Conversation(
            id = UUID.randomUUID().toString(),
            projectId = projectId,
            title = title,
            createdAt = now,
            updatedAt = now,
            providerId = providerId,
            modelId = modelId,
            turnCount = 0,
            isActive = true
        )

        facade.withDatabase { db ->
            db.execute(
                """INSERT INTO conversations 
                   (id, project_id, title, created_at, updated_at, provider_id, model_id, turn_count, is_active, max_history_tokens_override)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                conversation.id,
                conversation.projectId,
                conversation.title,
                conversation.createdAt.toString(),
                conversation.updatedAt.toString(),
                conversation.providerId,
                conversation.modelId,
                conversation.turnCount,
                conversation.isActive,
                conversation.maxHistoryTokensOverride
            )
        }

        Dev.info(log, "conversation.created",
            "id" to conversation.id,
            "title" to title,
            "provider" to providerId,
            "model" to modelId
        )

        return conversation
    }

    // ── Read ────────────────────────────────────────────────────────────

    /**
     * Get a single conversation by ID.
     *
     * @return The conversation, or null if not found or storage not ready
     */
    fun getConversation(conversationId: String): Conversation? {
        if (!isStorageReady()) return null

        return facade.withReadableDatabase { db ->
            db.queryOne(
                "SELECT * FROM conversations WHERE id = ?",
                conversationId
            ) { rs -> mapConversation(rs) }
        }
    }

    /**
     * List conversations for the current project.
     *
     * Ordered by updated_at DESC (most recently active first).
     * Used by the Library tab to display the conversations list.
     *
     * @param activeOnly If true, only return active (non-archived) conversations
     * @param limit Maximum number of results
     * @param offset Pagination offset
     * @return List of conversations, newest first. Empty if storage not ready.
     */
    fun listConversations(
        activeOnly: Boolean = true,
        limit: Int = 50,
        offset: Int = 0
    ): List<Conversation> {
        if (!isStorageReady()) return emptyList()

        val projectId = facade.resolveProjectId()
        val activeClause = if (activeOnly) "AND is_active = 1" else ""

        return facade.withReadableDatabase { db ->
            db.query(
                """SELECT * FROM conversations 
                   WHERE project_id = ? $activeClause
                   ORDER BY updated_at DESC
                   LIMIT ? OFFSET ?""",
                projectId, limit, offset
            ) { rs -> mapConversation(rs) }
        }
    }

    /**
     * Count conversations for the current project.
     *
     * @return Count, or 0 if storage not ready
     */
    fun countConversations(activeOnly: Boolean = true): Int {
        if (!isStorageReady()) return 0

        val projectId = facade.resolveProjectId()
        val activeClause = if (activeOnly) "AND is_active = 1" else ""

        return facade.withReadableDatabase { db ->
            db.queryScalar(
                "SELECT COUNT(*) FROM conversations WHERE project_id = ? $activeClause",
                projectId
            )
        }
    }

    /**
     * Load all exchanges for a conversation, ordered chronologically (oldest first).
     *
     * Used by the Library to display the full conversation transcript
     * and by the history builder to construct the messages array.
     *
     * Returns lightweight metadata — use LocalStorageFacade.getExchange()
     * for full raw content when needed.
     *
     * @param conversationId The conversation to load
     * @return List of exchanges, oldest first. Empty if storage not ready.
     */
    fun loadExchanges(conversationId: String): List<ConversationExchangeInfo> {
        if (!isStorageReady()) return emptyList()

        return facade.withReadableDatabase { db ->
            db.query(
                """SELECT id, provider_id, model_id, purpose, timestamp,
                          prompt_tokens, completion_tokens, total_tokens,
                          assistant_text, user_prompt, flags, has_code_block, code_languages,
                          detected_topics
                   FROM chat_exchanges
                   WHERE conversation_id = ?
                   ORDER BY timestamp ASC""",
                conversationId
            ) { rs ->
                ConversationExchangeInfo(
                    exchangeId = rs.getString("id"),
                    providerId = rs.getString("provider_id"),
                    modelId = rs.getString("model_id"),
                    purpose = rs.getString("purpose"),
                    timestamp = Instant.parse(rs.getString("timestamp")),
                    promptTokens = rs.getInt("prompt_tokens").takeIf { !rs.wasNull() },
                    completionTokens = rs.getInt("completion_tokens").takeIf { !rs.wasNull() },
                    totalTokens = rs.getInt("total_tokens").takeIf { !rs.wasNull() },
                    assistantText = rs.getString("assistant_text"),
                    userPrompt = rs.getString("user_prompt"),
                    isBookmarked = (rs.getString("flags") ?: "").contains("BOOKMARKED"),
                    hasCode = rs.getInt("has_code_block") == 1,
                    languages = rs.getString("code_languages")
                        ?.split(",")?.filter { it.isNotBlank() } ?: emptyList(),
                    topics = rs.getString("detected_topics")
                        ?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
                )
            }
        }
    }

    /**
     * Load the most recent N exchanges for a conversation (newest first, then reversed).
     *
     * Used for initial tab rendering — load the tail, then lazy-load older exchanges
     * on scroll-up.
     *
     * @param conversationId The conversation to load
     * @param limit How many recent exchanges to load
     * @return List of exchanges, oldest first (ready for display). Empty if storage not ready.
     */
    fun loadRecentExchanges(conversationId: String, limit: Int = 20): List<ConversationExchangeInfo> {
        if (!isStorageReady()) return emptyList()

        return facade.withReadableDatabase { db ->
            // Subquery gets the latest N by timestamp DESC, outer query re-sorts ASC
            db.query(
                """SELECT * FROM (
                       SELECT id, provider_id, model_id, purpose, timestamp,
                              prompt_tokens, completion_tokens, total_tokens,
                              assistant_text, user_prompt, flags, has_code_block, code_languages,
                              detected_topics
                       FROM chat_exchanges
                       WHERE conversation_id = ?
                       ORDER BY timestamp DESC
                       LIMIT ?
                   ) sub ORDER BY timestamp ASC""",
                conversationId, limit
            ) { rs ->
                ConversationExchangeInfo(
                    exchangeId = rs.getString("id"),
                    providerId = rs.getString("provider_id"),
                    modelId = rs.getString("model_id"),
                    purpose = rs.getString("purpose"),
                    timestamp = Instant.parse(rs.getString("timestamp")),
                    promptTokens = rs.getInt("prompt_tokens").takeIf { !rs.wasNull() },
                    completionTokens = rs.getInt("completion_tokens").takeIf { !rs.wasNull() },
                    totalTokens = rs.getInt("total_tokens").takeIf { !rs.wasNull() },
                    assistantText = rs.getString("assistant_text"),
                    userPrompt = rs.getString("user_prompt"),
                    isBookmarked = (rs.getString("flags") ?: "").contains("BOOKMARKED"),
                    hasCode = rs.getInt("has_code_block") == 1,
                    languages = rs.getString("code_languages")
                        ?.split(",")?.filter { it.isNotBlank() } ?: emptyList(),
                    topics = rs.getString("detected_topics")
                        ?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
                )
            }
        }
    }

    // ── Update ──────────────────────────────────────────────────────────

    /**
     * Update conversation metadata after a new exchange is saved.
     *
     * Called by the chat pipeline after GenericLlmProvider.chat() returns.
     * Updates: timestamp, turn count, and provider/model info.
     *
     * @param conversationId The conversation that received a new exchange
     * @param providerId The provider that handled this exchange
     * @param modelId The model used
     */
    fun onExchangeAdded(conversationId: String?, providerId: String, modelId: String) {
        if (conversationId == null || !isStorageReady()) return

        val now = Instant.now()

        facade.withDatabase { db ->
            db.execute(
                """UPDATE conversations 
                   SET updated_at = ?, turn_count = turn_count + 1,
                       provider_id = ?, model_id = ?
                   WHERE id = ?""",
                now.toString(), providerId, modelId, conversationId
            )
        }

        Dev.info(log, "conversation.exchange_added",
            "conversation_id" to conversationId,
            "provider" to providerId,
            "model" to modelId
        )
    }

    /**
     * Retroactively link an exchange to a conversation.
     *
     * Used when the conversation is created AFTER the exchange is saved
     * (because we don't modify the provider's save path).
     *
     * @param exchangeId The exchange to link
     * @param conversationId The conversation to link it to
     */
    fun linkExchange(exchangeId: String, conversationId: String) {
        if (!isStorageReady()) return

        facade.withDatabase { db ->
            db.execute(
                "UPDATE chat_exchanges SET conversation_id = ? WHERE id = ?",
                conversationId, exchangeId
            )
        }

        Dev.info(log, "conversation.exchange_linked",
            "exchange_id" to exchangeId,
            "conversation_id" to conversationId
        )
    }

    /**
     * Update the conversation title.
     *
     * Can be called by:
     * - Auto-generation (on first exchange)
     * - User edit (double-click tab title or Library detail view)
     */
    fun updateTitle(conversationId: String, title: String) {
        if (!isStorageReady()) return

        facade.withDatabase { db ->
            db.execute(
                "UPDATE conversations SET title = ? WHERE id = ?",
                title, conversationId
            )
        }

        Dev.info(log, "conversation.title_updated",
            "conversation_id" to conversationId,
            "title" to title
        )
    }

    /**
     * Update the per-conversation max history tokens override.
     *
     * @param override null = inherit global, -1 = unlimited, >0 = specific cap
     */
    fun updateMaxHistoryTokensOverride(conversationId: String, override: Int?) {
        if (!isStorageReady()) return

        facade.withDatabase { db ->
            db.execute(
                "UPDATE conversations SET max_history_tokens_override = ? WHERE id = ?",
                override, conversationId
            )
        }

        Dev.info(log, "conversation.history_override_updated",
            "conversation_id" to conversationId,
            "override" to (override?.toString() ?: "inherit_global")
        )
    }

    /**
     * Soft-close (archive) a conversation.
     *
     * The conversation and its exchanges remain in the database.
     * Archived conversations don't appear in the default Library list
     * but can be shown with activeOnly=false.
     */
    fun archiveConversation(conversationId: String) {
        if (!isStorageReady()) return

        facade.withDatabase { db ->
            db.execute(
                "UPDATE conversations SET is_active = 0 WHERE id = ?",
                conversationId
            )
        }

        Dev.info(log, "conversation.archived", "conversation_id" to conversationId)
    }

    // ── History Building ────────────────────────────────────────────────

    /**
     * Build the conversation history for sending to an AI provider.
     *
     * Phase A: Simple verbatim — sends the last N turns as-is.
     * Phase B: Will add smart compression (summarize older turns).
     *
     * @param conversationId The conversation to build history for
     * @param verbatimWindow How many recent turns to include verbatim
     * @return List of ConversationTurns ready for the provider, or empty if no history
     */
    fun buildHistory(conversationId: String?, verbatimWindow: Int = 5): List<ConversationTurn> {
        if (conversationId == null || !isStorageReady()) return emptyList()

        val exchanges = loadExchanges(conversationId)
        if (exchanges.isEmpty()) return emptyList()

        // Phase A: take the last N exchanges and convert to turns
        val recentExchanges = exchanges.takeLast(verbatimWindow)

        return recentExchanges.flatMap { info ->
            listOf(
                ConversationTurn(TurnRole.USER, info.userPrompt ?: ""),
                ConversationTurn(TurnRole.ASSISTANT, info.assistantText ?: "")
            )
        }
    }

    // ── Private helpers ─────────────────────────────────────────────────

    private fun mapConversation(rs: java.sql.ResultSet): Conversation {
        val overrideRaw = rs.getInt("max_history_tokens_override")
        val override = if (rs.wasNull()) null else overrideRaw

        return Conversation(
            id = rs.getString("id"),
            projectId = rs.getString("project_id"),
            title = rs.getString("title"),
            createdAt = Instant.parse(rs.getString("created_at")),
            updatedAt = Instant.parse(rs.getString("updated_at")),
            providerId = rs.getString("provider_id"),
            modelId = rs.getString("model_id"),
            turnCount = rs.getInt("turn_count"),
            isActive = rs.getInt("is_active") == 1,
            maxHistoryTokensOverride = override
        )
    }

    companion object {
        fun getInstance(project: Project): ConversationManager =
            project.getService(ConversationManager::class.java)
    }
}

/**
 * Lightweight exchange info for conversation display.
 *
 * Contains the metadata needed for:
 * - Library conversation detail view (display each turn with tags/badges)
 * - Tab rendering (show message content)
 * - History building (get user prompt + assistant response)
 *
 * Does NOT contain the full raw JSON response — use LocalStorageFacade.getExchange()
 * for that when needed.
 */
data class ConversationExchangeInfo(
    val exchangeId: String,
    val providerId: String,
    val modelId: String,
    val purpose: String,
    val timestamp: Instant,
    val promptTokens: Int?,
    val completionTokens: Int?,
    val totalTokens: Int?,
    val userPrompt: String?,
    val assistantText: String?,
    val isBookmarked: Boolean,
    val hasCode: Boolean,
    val languages: List<String>,
    val topics: List<String>
) {
    val effectiveTotalTokens: Int?
        get() = totalTokens
            ?: if (promptTokens != null && completionTokens != null) promptTokens + completionTokens
            else null
}