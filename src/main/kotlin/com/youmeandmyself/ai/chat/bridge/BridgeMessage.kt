package com.youmeandmyself.ai.chat.bridge

import com.youmeandmyself.dev.Dev
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Kotlin mirror of the TypeScript bridge message contract.
 *
 * ## Structure
 *
 * - [Command]: Messages from the frontend (React) to the backend (Kotlin).
 *   Deserialized from JSON strings received via JCEF's query handler.
 *
 * - [Event]: Messages from the backend (Kotlin) to the frontend (React).
 *   Serialized to JSON and sent via `executeJavaScript("window.__ymm_bridgeReceive(...)")`.
 *
 * ## Sync with TypeScript
 *
 * Every type here has a mirror in `src/bridge/types.ts`. When adding a new
 * message type:
 * 1. Add it here (Kotlin sealed class)
 * 2. Add it in types.ts (TypeScript interface + union member)
 * 3. Handle it in BridgeDispatcher.kt (Kotlin routing)
 * 4. Handle it in the React component (event subscription)
 *
 * ## R5 Changes
 *
 * Added code block bookmarking: BookmarkCodeBlock command, BookmarkResultEvent.
 * Added cross-panel navigation: OpenConversation command, OpenConversationResultEvent.
 *
 * ## Per-Tab Provider Changes
 *
 * - SendMessage: gains providerId so the backend knows which provider to use
 *   for this specific tab's conversation, not the global selection.
 * - TabStateDto: gains providerId so per-tab provider survives IDE restart.
 * - SwitchTabProvider: new command for when user changes provider on a specific tab.
 */
object BridgeMessage {

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    /** Logger for bridge message parsing. */
    private val log = Dev.logger(BridgeMessage::class.java)

    // ═══════════════════════════════════════════════════════════════════
    //  COMMANDS (Frontend → Backend)
    // ═══════════════════════════════════════════════════════════════════

    @Serializable
    sealed class Command {
        abstract val type: String
    }

    /**
     * Send a chat message.
     *
     * conversationId: groups this message with its conversation in storage.
     * providerId: the profile ID to use for this tab's conversation.
     *   If null, falls back to the globally selected chat provider.
     *   Set by the frontend from the per-tab provider selection.
     */
    @Serializable
    @SerialName("SEND_MESSAGE")
    data class SendMessage(
        override val type: String = "SEND_MESSAGE",
        val text: String,
        val conversationId: String? = null,
        val providerId: String? = null
    ) : Command()

    @Serializable
    @SerialName("CONFIRM_CORRECTION")
    data class ConfirmCorrection(
        override val type: String = "CONFIRM_CORRECTION"
    ) : Command()

    @Serializable
    @SerialName("REQUEST_CORRECTION")
    data class RequestCorrection(
        override val type: String = "REQUEST_CORRECTION"
    ) : Command()

    @Serializable
    @SerialName("CLEAR_CHAT")
    data class ClearChat(
        override val type: String = "CLEAR_CHAT"
    ) : Command()

    @Serializable
    @SerialName("NEW_CONVERSATION")
    data class NewConversation(
        override val type: String = "NEW_CONVERSATION"
    ) : Command()

    /**
     * Switch the global chat provider selection.
     *
     * This updates AiProfilesState.selectedChatProfileId — the fallback
     * provider used when a tab has no per-tab provider set.
     * For per-tab provider changes, use SwitchTabProvider instead.
     */
    @Serializable
    @SerialName("SWITCH_PROVIDER")
    data class SwitchProvider(
        override val type: String = "SWITCH_PROVIDER",
        val providerId: String
    ) : Command()

    @Serializable
    @SerialName("REQUEST_PROVIDERS")
    data class RequestProviders(
        override val type: String = "REQUEST_PROVIDERS"
    ) : Command()

    // ── Per-Tab Provider Command ──────────────────────────────────────

    /**
     * Switch the provider for a specific tab.
     *
     * Unlike SwitchProvider (which updates the global selection),
     * this updates only the provider associated with the given tab.
     * The selection is persisted in open_tabs.provider_id so it
     * survives IDE restarts.
     *
     * Backend: BridgeDispatcher.handleSwitchTabProvider()
     * → Updates TabStateService for the specific tab
     * → Does NOT affect AiProfilesState.selectedChatProfileId
     */
    @Serializable
    @SerialName("SWITCH_TAB_PROVIDER")
    data class SwitchTabProvider(
        override val type: String = "SWITCH_TAB_PROVIDER",
        val tabId: String,
        val providerId: String
    ) : Command()

    // ── R4: Tab Management Commands ──────────────────────────────────

    /** User switches to a different tab. Frontend sends the tab ID. */
    @Serializable
    @SerialName("SWITCH_TAB")
    data class SwitchTab(
        override val type: String = "SWITCH_TAB",
        val tabId: String
    ) : Command()

    /** User closes a tab. Conversation is NOT deleted — accessible from Library. */
    @Serializable
    @SerialName("CLOSE_TAB")
    data class CloseTab(
        override val type: String = "CLOSE_TAB",
        val tabId: String
    ) : Command()

    /**
     * Frontend saves the full tab state for persistence.
     * Backend does a full replace in the open_tabs table.
     */
    @Serializable
    @SerialName("SAVE_TAB_STATE")
    data class SaveTabState(
        override val type: String = "SAVE_TAB_STATE",
        val tabs: List<TabStateDto>,
        val activeTabId: String
    ) : Command()

    /** Frontend requests saved tab state on startup. */
    @Serializable
    @SerialName("REQUEST_TAB_STATE")
    data class RequestTabState(
        override val type: String = "REQUEST_TAB_STATE"
    ) : Command()

    // ── R4: Conversation History Command ─────────────────────────────

    /** Frontend requests message history for a conversation (tab restore). */
    @Serializable
    @SerialName("LOAD_CONVERSATION")
    data class LoadConversation(
        override val type: String = "LOAD_CONVERSATION",
        val conversationId: String,
        val tabId: String
    ) : Command()

    // ── R4: Exchange Starring Command ────────────────────────────────

    /** User toggles star (favourite) on an assistant response. */
    @Serializable
    @SerialName("TOGGLE_STAR")
    data class ToggleStar(
        override val type: String = "TOGGLE_STAR",
        val exchangeId: String
    ) : Command()

    // ── R5: Code Block Bookmark Command ──────────────────────────────

    /**
     * User clicked the bookmark ribbon on a code block.
     * Bookmarks the entire exchange (not just the code block).
     * blockIndex is included for future per-block bookmarking.
     */
    @Serializable
    @SerialName("BOOKMARK_CODE_BLOCK")
    data class BookmarkCodeBlock(
        override val type: String = "BOOKMARK_CODE_BLOCK",
        val exchangeId: String,
        val blockIndex: Int
    ) : Command()

    // ── R5: Open Conversation Command ────────────────────────────────

    /**
     * Open a conversation in the Chat tab.
     *
     * TEMPORARY: Part of the cross-panel bridge between the vanilla HTML
     * Library and the React Chat. Remove when Library migrates to React
     * and both panels share the same BridgeDispatcher.
     *
     * Sent by CrossPanelBridge when the Library's "Continue chat" is clicked.
     * The BridgeDispatcher checks if a tab with this conversationId is already
     * open — if so, switches to it; if not, creates a new tab.
     */
    @Serializable
    @SerialName("OPEN_CONVERSATION")
    data class OpenConversation(
        override val type: String = "OPEN_CONVERSATION",
        val conversationId: String
    ) : Command()

    // ── Dev: Dev Command ─────────────────────────────────────────

    /**
     * User typed a /dev-* command in the chat input.
     *
     * The frontend intercepts /dev- prefixed messages and sends them
     * as DEV_COMMAND instead of SEND_MESSAGE. The backend routes to
     * DevCommandHandler, which processes the command and sends back
     * DEV_OUTPUT events rendered as system messages in chat.
     *
     * Only active when dev mode is enabled (-Dymm.devMode=true).
     * If dev mode is off, the backend returns a single DEV_OUTPUT
     * saying dev mode is disabled.
     */
    @Serializable
    @SerialName("DEV_COMMAND")
    data class DevCommand(
        override val type: String = "DEV_COMMAND",
        val text: String
    ) : Command()

    // ═══════════════════════════════════════════════════════════════════
    //  EVENTS (Backend → Frontend)
    // ═══════════════════════════════════════════════════════════════════

    @Serializable
    sealed class Event {
        abstract val type: String
    }

    @Serializable
    data class ChatResultEvent(
        override val type: String = "CHAT_RESULT",
        val displayText: String,
        val isError: Boolean,
        val exchangeId: String?,
        val conversationId: String?,
        val correctionAvailable: Boolean,
        val parseStrategy: String,
        val confidence: String,
        val providerId: String?,
        val modelId: String?,
        val contextSummary: String?,
        val contextTimeMs: Long?,
        val tokenUsage: TokenUsageDto?
    ) : Event()

    @Serializable
    data class TokenUsageDto(
        val promptTokens: Int?,
        val completionTokens: Int?,
        val totalTokens: Int?
    )

    @Serializable
    data class ShowThinkingEvent(
        override val type: String = "SHOW_THINKING"
    ) : Event()

    @Serializable
    data class HideThinkingEvent(
        override val type: String = "HIDE_THINKING"
    ) : Event()

    /**
     * Token usage metrics update for the active chat tab.
     *
     * Fired after every AI call (chat or summary) when token data is
     * available. The React MetricsBar consumes this to update:
     * - Last exchange display (P/C/T counts)
     * - Session accumulator (running totals)
     * - Context fill bar (totalTokens / contextWindowSize)
     *
     * ## Metrics Module Boundary
     *
     * This event carries raw token counts only — never cost.
     * Cost conversion is the Pricing Module's responsibility (post-launch).
     * If a field is null, the corresponding UI element is simply hidden.
     *
     * ## Fields Added in Metrics Module (Block 2)
     *
     * - contextWindowSize: powers the color-coded fill bar
     * - responseTimeMs: recorded for future response time comparison UI
     * - purpose: enables filtering (e.g., "show only chat token usage")
     *
     * @see MetricsBar.tsx — React component that renders this data
     * @see accumulator.ts — Pure functions that aggregate snapshots
     */
    @Serializable
    data class UpdateMetricsEvent(
        override val type: String = "UPDATE_METRICS",

        /** Model identifier (e.g., "gpt-4o", "gemini-2.5-flash"). Null if unknown. */
        val model: String?,

        /** Input tokens sent to the model. Null if the provider didn't report this. */
        val promptTokens: Int?,

        /** Output tokens generated by the model. Null if the provider didn't report this. */
        val completionTokens: Int?,

        /** Provider-reported total tokens. Null if the provider didn't report this. */
        val totalTokens: Int?,

        /**
         * Model's maximum context window in tokens.
         * Resolved from AiProfile.contextWindowSize (user override) or
         * DefaultContextWindows.lookup() (built-in default).
         * Null if unknown — fill bar is hidden.
         */
        val contextWindowSize: Int? = null,

        /**
         * Wall-clock time for the AI call in milliseconds.
         * Measured in ChatOrchestrator from before provider.chat() to after.
         * Null if timing wasn't captured.
         */
        val responseTimeMs: Long? = null,

        /**
         * Exchange purpose: "CHAT", "FILE_SUMMARY", "METHOD_SUMMARY", etc.
         * Enables the frontend to filter or label metrics by purpose.
         * Null for legacy callers that don't pass purpose.
         */
        val purpose: String? = null
    ) : Event()

    @Serializable
    data class SystemMessageEvent(
        override val type: String = "SYSTEM_MESSAGE",
        val content: String,
        val level: String = "INFO"
    ) : Event()

    @Serializable
    data class CorrectionCandidatesEvent(
        override val type: String = "CORRECTION_CANDIDATES",
        val candidates: List<CorrectionCandidateDto>
    ) : Event()

    @Serializable
    data class CorrectionCandidateDto(
        val text: String,
        val path: String,
        val confidence: String
    )

    @Serializable
    data class ProvidersListEvent(
        override val type: String = "PROVIDERS_LIST",
        val providers: List<ProviderInfoDto>,
        val selectedId: String?
    ) : Event()

    /**
     * Provider info sent to the frontend for the provider dropdown and
     * metrics context.
     *
     * Enhanced in Metrics Module (Block 2) with model and contextWindowSize
     * so the MetricsBar can show model name and compute the context fill
     * bar percentage without waiting for the first AI response.
     *
     * @see BridgeDispatcher.handleRequestProviders — populates these
     * @see ProviderSelector.tsx — renders the dropdown
     * @see MetricsBar.tsx — uses model and contextWindowSize
     */
    @Serializable
    data class ProviderInfoDto(
        /** AI profile ID (AiProfile.id). */
        val id: String,

        /** User-friendly profile label (e.g., "My Gemini"). */
        val label: String,

        /** API protocol: "OPENAI_COMPAT", "GEMINI", "CUSTOM". */
        val protocol: String,

        /**
         * Model identifier from the profile (e.g., "gpt-4o").
         * Null if the profile doesn't have a model configured.
         */
        val model: String? = null,

        /**
         * Model's maximum context window in tokens.
         * Resolved from AiProfile.contextWindowSize or DefaultContextWindows.
         * Null if unknown.
         */
        val contextWindowSize: Int? = null
    )

    @Serializable
    data class ConversationClearedEvent(
        override val type: String = "CONVERSATION_CLEARED"
    ) : Event()

    // ── R4: Tab Events ───────────────────────────────────────────────

    /**
     * Saved tab state sent to frontend on startup.
     * If keepTabs is false or no tabs were saved, tabs list is empty.
     */
    @Serializable
    data class TabStateEvent(
        override val type: String = "TAB_STATE",
        val tabs: List<TabStateDto>,
        val keepTabs: Boolean
    ) : Event()

    /**
     * Conversation history for a restored tab.
     * Sent in response to LoadConversation.
     */
    @Serializable
    data class ConversationHistoryEvent(
        override val type: String = "CONVERSATION_HISTORY",
        val tabId: String,
        val conversationId: String,
        val messages: List<HistoryMessageDto>
    ) : Event()

    /** Star state confirmation after ToggleStar. */
    @Serializable
    data class StarUpdatedEvent(
        override val type: String = "STAR_UPDATED",
        val exchangeId: String,
        val isStarred: Boolean
    ) : Event()

    // ── R5: Bookmark Events ──────────────────────────────────────────

    /** Bookmark result confirmation after BookmarkCodeBlock. */
    @Serializable
    data class BookmarkResultEvent(
        override val type: String = "BOOKMARK_RESULT",
        val exchangeId: String,
        val success: Boolean,
        val error: String? = null
    ) : Event()

    /**
     * Confirmation that a conversation was opened in a tab.
     *
     * TEMPORARY: Part of the cross-panel bridge. Remove when Library
     * migrates to React.
     */
    @Serializable
    data class OpenConversationResultEvent(
        override val type: String = "OPEN_CONVERSATION_RESULT",
        val conversationId: String,
        val tabId: String,
        val title: String = "New Chat"
    ) : Event()

    // ── Dev: Dev Output Event ────────────────────────────────────

    /**
     * Output from a dev command, rendered as a system message in chat.
     *
     * Sent by BridgeDispatcher after DevCommandHandler processes a
     * /dev-* command. The frontend renders this the same as
     * SystemMessageEvent but with "DEV" level styling.
     */
    @Serializable
    data class DevOutputEvent(
        override val type: String = "DEV_OUTPUT",
        val content: String
    ) : Event()

    // ═══════════════════════════════════════════════════════════════════
    //  SHARED DTOs
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Persisted tab state.
     *
     * providerId: the AI profile selected for this specific tab.
     *   Null means "use the global chat provider selection".
     *   Persisted in open_tabs.provider_id so per-tab provider
     *   survives IDE restarts.
     */
    @Serializable
    data class TabStateDto(
        val id: String,
        val conversationId: String?,
        val title: String,
        val tabOrder: Int,
        val isActive: Boolean,
        val scrollPosition: Int,
        val providerId: String? = null
    )

    @Serializable
    data class HistoryMessageDto(
        val role: String,
        val content: String,
        val timestamp: String,
        val exchangeId: String?,
        val isStarred: Boolean,
        val promptTokens: Int? = null,
        val completionTokens: Int? = null,
        val totalTokens: Int? = null,
        val model: String? = null
    )

    // ═══════════════════════════════════════════════════════════════════
    //  DESERIALIZATION
    // ═══════════════════════════════════════════════════════════════════

    fun parseCommand(jsonString: String): Command? {
        return try {
            val element = json.parseToJsonElement(jsonString)
            val typeField = element.jsonObject["type"]?.toString()?.removeSurrounding("\"")

            when (typeField) {
                "SEND_MESSAGE" -> json.decodeFromString<SendMessage>(jsonString)
                "CONFIRM_CORRECTION" -> json.decodeFromString<ConfirmCorrection>(jsonString)
                "REQUEST_CORRECTION" -> json.decodeFromString<RequestCorrection>(jsonString)
                "CLEAR_CHAT" -> json.decodeFromString<ClearChat>(jsonString)
                "NEW_CONVERSATION" -> json.decodeFromString<NewConversation>(jsonString)
                "SWITCH_PROVIDER" -> json.decodeFromString<SwitchProvider>(jsonString)
                "REQUEST_PROVIDERS" -> json.decodeFromString<RequestProviders>(jsonString)
                // Per-tab provider
                "SWITCH_TAB_PROVIDER" -> json.decodeFromString<SwitchTabProvider>(jsonString)
                // R4: Tab management
                "SWITCH_TAB" -> json.decodeFromString<SwitchTab>(jsonString)
                "CLOSE_TAB" -> json.decodeFromString<CloseTab>(jsonString)
                "SAVE_TAB_STATE" -> json.decodeFromString<SaveTabState>(jsonString)
                "REQUEST_TAB_STATE" -> json.decodeFromString<RequestTabState>(jsonString)
                // R4: Conversation history
                "LOAD_CONVERSATION" -> json.decodeFromString<LoadConversation>(jsonString)
                // R4: Exchange starring
                "TOGGLE_STAR" -> json.decodeFromString<ToggleStar>(jsonString)
                // R5: Code block bookmark
                "BOOKMARK_CODE_BLOCK" -> json.decodeFromString<BookmarkCodeBlock>(jsonString)
                // R5: Cross-panel conversation open
                "OPEN_CONVERSATION" -> json.decodeFromString<OpenConversation>(jsonString)
                // Dev command
                "DEV_COMMAND" -> json.decodeFromString<DevCommand>(jsonString)
                else -> {
                    Dev.warn(log, "bridge.parse.unknown_command", null,
                        "type" to (typeField ?: "null")
                    )
                    null
                }
            }
        } catch (e: Exception) {
            Dev.warn(log, "bridge.parse.failed", e,
                "preview" to Dev.preview(jsonString, 120)
            )
            null
        }
    }

    fun serializeEvent(event: Event): String {
        return when (event) {
            is ChatResultEvent -> json.encodeToString(ChatResultEvent.serializer(), event)
            is ShowThinkingEvent -> json.encodeToString(ShowThinkingEvent.serializer(), event)
            is HideThinkingEvent -> json.encodeToString(HideThinkingEvent.serializer(), event)
            is UpdateMetricsEvent -> json.encodeToString(UpdateMetricsEvent.serializer(), event)
            is SystemMessageEvent -> json.encodeToString(SystemMessageEvent.serializer(), event)
            is CorrectionCandidatesEvent -> json.encodeToString(CorrectionCandidatesEvent.serializer(), event)
            is ProvidersListEvent -> json.encodeToString(ProvidersListEvent.serializer(), event)
            is ConversationClearedEvent -> json.encodeToString(ConversationClearedEvent.serializer(), event)
            // R4
            is TabStateEvent -> json.encodeToString(TabStateEvent.serializer(), event)
            is ConversationHistoryEvent -> json.encodeToString(ConversationHistoryEvent.serializer(), event)
            is StarUpdatedEvent -> json.encodeToString(StarUpdatedEvent.serializer(), event)
            // R5
            is BookmarkResultEvent -> json.encodeToString(BookmarkResultEvent.serializer(), event)
            is OpenConversationResultEvent -> json.encodeToString(OpenConversationResultEvent.serializer(), event)
            // Dev
            is DevOutputEvent -> json.encodeToString(DevOutputEvent.serializer(), event)
        }
    }
}

private val kotlinx.serialization.json.JsonElement.jsonObject
    get() = this as kotlinx.serialization.json.JsonObject