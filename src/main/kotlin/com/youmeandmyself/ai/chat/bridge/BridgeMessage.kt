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
     * bypassMode: controls whether ContextAssembler gathers IDE context.
     *   Sent in backend bypass perspective (translated from dial perspective by dialToBackendBypass).
     *   null = normal flow (full context gathering). "FULL" = skip all context.
     *   "SELECTIVE" = Pro-tier per-component bypass.
     * selectiveLevel: only relevant when bypassMode = "SELECTIVE".
     *   0 = Minimal (open file only), 1 = Partial (no ProjectStructure),
     *   2 = Full (all 4 detectors). Null = use default (2).
     *
     * @see ContextAssembler.assemble — checks bypassMode + selectiveLevel
     * @see Feature.CONTEXT_SELECTIVE_BYPASS — tier gate for SELECTIVE mode
     */
    @Serializable
    @SerialName("SEND_MESSAGE")
    data class SendMessage(
        override val type: String = "SEND_MESSAGE",
        val text: String,
        val conversationId: String? = null,
        val providerId: String? = null,
        val bypassMode: String? = null,
        val selectiveLevel: Int? = null,
        /**
         * Per-tab summary toggle. When false, context files go to the AI
         * as full raw text (no compression). When true, the summary pipeline
         * compresses the context files into shorter representations.
         *
         * IMPORTANT: This controls SUMMARY (compression), NOT context (scope).
         * Context (bypassMode) = WHAT gets gathered from the IDE.
         * Summary (this field) = HOW COMPACT those gathered files are.
         * These are independent features — never conflate them.
         *
         * Null = use global setting (backward compat with old frontends).
         */
        val summaryEnabled: Boolean? = null
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
     * User renamed a tab via double-click inline edit.
     *
     * conversationId is nullable — a fresh tab that has never sent a message
     * has no conversation in storage yet. In that case the backend skips the
     * DB update; the title lives in local state and persists via SAVE_TAB_STATE.
     *
     * When conversationId is present, the backend updates conversations.title
     * so the rename survives IDE restarts and shows correctly in the Library.
     *
     * No event is sent back — the frontend already applied the title optimistically.
     *
     * Backend: BridgeDispatcher.handleRenameTab()
     * → ConversationManager.updateTitle(conversationId, title) when conversationId != null
     */
    @Serializable
    @SerialName("RENAME_TAB")
    data class RenameTab(
        override val type: String = "RENAME_TAB",
        val tabId: String,
        val conversationId: String? = null,
        val title: String
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
     *
     * Creates a code_bookmarks row for this specific block (per-block bookmarking).
     * blockIndex identifies which code block within the exchange (0-based).
     *
     * NOTE: The current BridgeDispatcher handler for this command bookmarks the
     * entire exchange rather than the specific code block. That needs to be updated
     * to create a code_bookmarks row via LocalStorageFacade. See Item 6 action plan.
     *
     * TODO (Phase 5 — Add to Collection UX):
     *   After bookmarking, the user can also add the snippet to a collection.
     *   The hover button on code blocks in the chat view will need a new
     *   ADD_TO_COLLECTION command (separate from this) that opens the collection
     *   picker dropdown for code snippets.
     */
    @Serializable
    @SerialName("BOOKMARK_CODE_BLOCK")
    data class BookmarkCodeBlock(
        override val type: String = "BOOKMARK_CODE_BLOCK",
        val exchangeId: String,
        val blockIndex: Int
    ) : Command()

    // ── Phase 5 stub: Add to Collection from chat view ───────────────
    // TODO (Phase 5 — Add to Collection UX):
    //   Add an ADD_TO_COLLECTION command here for the React chat view.
    //   The hover button on exchanges and code blocks will send this command.
    //   Payload: { sourceType, sourceId, collectionId }
    //   Mirrors the LibraryPanel "addToCollection" command but routed
    //   through the chat bridge so it works from within the chat view.
    //   Also add: GET_COLLECTIONS (for the dropdown) and GET_ITEM_COLLECTIONS
    //   (for checkmarks). The Library already has these; the chat bridge does not.
    //
    // @Serializable
    // @SerialName("ADD_TO_COLLECTION")
    // data class AddToCollection(
    //     override val type: String = "ADD_TO_COLLECTION",
    //     val sourceType: String,   // 'CHAT' | 'CODE_SNIPPET' | 'CONVERSATION'
    //     val sourceId: String,
    //     val collectionId: String
    // ) : Command()

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

    // ── Block 5C: Frontend Logging ──────────────────────────────

    /**
     * Log entry from the React frontend, routed to idea.log via [Dev].
     *
     * console.log is dead inside JCEF — the embedded Chromium doesn't
     * expose DevTools in production. The frontend's log.ts utility sends
     * FRONTEND_LOG commands instead, and this handler routes them to the
     * standard IDE logging system.
     *
     * Tagged as "react.{source}" in idea.log for easy filtering:
     *   react.useBridge — CHAT_RESULT received {exchangeId=abc}
     *   react.ChatApp — mounted with 3 tabs
     *
     * @see BridgeDispatcher.handleFrontendLog — routing handler
     * @see log.ts — React-side utility that sends these
     */
    @Serializable
    @SerialName("FRONTEND_LOG")
    data class FrontendLog(
        override val type: String = "FRONTEND_LOG",
        /** Severity: "INFO", "WARN", or "ERROR". */
        val level: String,
        /** The log message (may include formatted context). */
        val message: String,
        /** React module or component name (e.g., "useBridge"). */
        val source: String = "unknown"
    ) : Command()

    // ── Block 5: Context Settings Request ────────────────────────────

    /**
     * Frontend requests project-level context settings on startup.
     *
     * Sent at BRIDGE_READY (and non-JCEF dev startup). Backend reads
     * ContextSettingsState and emits ContextSettingsEvent back.
     *
     * @see BridgeDispatcher.handleRequestContextSettings — handler
     * @see ContextSettingsEvent — the response
     */
    @Serializable
    @SerialName("REQUEST_CONTEXT_SETTINGS")
    data class RequestContextSettings(
        override val type: String = "REQUEST_CONTEXT_SETTINGS"
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
        val keepTabs: Boolean,
        val maxTabs: Int = 5
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

    // ── Block 5: Context Settings Event ──────────────────────────────

    /**
     * Project-level context settings sent to the frontend on startup.
     *
     * Sent in response to RequestContextSettings. The React app uses
     * this to:
     * - Set the global kill-switch state (globalContextEnabled) which
     *   disables the ContextDial when false.
     * - Set the default bypassMode for new tabs (defaultBypassMode).
     * - Know if summary is globally enabled (summaryEnabled).
     *
     * @param contextEnabled Master kill-switch for context gathering.
     *   When false, all context gathering is disabled regardless of per-tab
     *   dial position. The ContextDial is visually greyed out.
     * @param defaultBypassMode Dial position for new tabs. "FULL" = full
     *   context on (default), "OFF" = no context, "SELECTIVE" = Pro only.
     *   Basic-tier users always receive "FULL" regardless of stored value.
     * @param summaryEnabled Global summary kill-switch from SummaryConfigService.
     *   When false, per-tab summary toggles are hidden/disabled in the UI.
     *
     * @see ContextSettingsState — stores context values
     * @see SummaryConfigService — stores summary enabled state
     * @see BridgeDispatcher.handleRequestContextSettings — sends this
     */
    @Serializable
    data class ContextSettingsEvent(
        override val type: String = "CONTEXT_SETTINGS",
        /** Global context kill-switch from ContextSettingsState.contextEnabled. */
        val contextEnabled: Boolean,
        /** Default mode for new tabs: "FULL" (context on) or "OFF". */
        val defaultBypassMode: String,
        /**
         * Global summary kill-switch from SummaryConfigService.enabled.
         * When false, per-tab summary dials are greyed out and non-interactive.
         *
         * Summary = HOW COMPACT the context files are (compression).
         * This is independent from contextEnabled (which controls WHAT is gathered).
         */
        val summaryEnabled: Boolean = true
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
     *
     * bypassMode: dial position stored in dial perspective (not backend bypass perspective).
     *   "FULL" = full context on (default). "OFF" = no context. "SELECTIVE" = Pro only.
     *   Persisted in open_tabs.bypass_mode. Default "FULL".
     *
     * selectiveLevel: the lever position when bypassMode = "SELECTIVE".
     *   0 = Minimal, 1 = Partial, 2 = Full. Persisted in open_tabs.selective_level.
     *   Ignored when bypassMode != "SELECTIVE". Default 2.
     *
     * traversalRadius: per-tab override for how far the context assembler
     *   reaches into the dependency graph from the current focus point.
     *   Null = use the project-level default from ContextSettingsState.
     *   STUB: not yet read by ContextAssembler. See ContextSettingsState for full docs.
     *
     * infrastructureVisibility: per-tab override for how high-fan-in
     *   cross-cutting classes (Auth, Logger, DB, DI, etc.) are included in context.
     *   Values: "OFF" | "BRIEF" | "DETAIL". Null = use project-level default.
     *   STUB: not yet read by ContextAssembler. See ContextSettingsState for full docs.
     */
    @Serializable
    data class TabStateDto(
        val id: String,
        val conversationId: String?,
        val title: String,
        val tabOrder: Int,
        val isActive: Boolean,
        val scrollPosition: Int,
        val providerId: String? = null,
        /** Dial position: "FULL" = context on (default), "OFF" = no context, "SELECTIVE" = Pro. */
        val bypassMode: String? = "FULL",
        /** Lever position when SELECTIVE: 0=Minimal, 1=Partial, 2=Full. Default 2. */
        val selectiveLevel: Int? = 2,
        /**
         * Per-tab summary toggle. True = files compressed before sending,
         * false = raw files sent as-is. Null = default (true).
         *
         * IMPORTANT: This controls SUMMARY (compression), NOT context (scope).
         * Context = WHAT gets gathered. Summary = HOW COMPACT those files are.
         * These are independent features — see ContextDialStrip.tsx for details.
         */
        val summaryEnabled: Boolean? = null,
        // STUB — per-tab context controls (not yet wired to ContextAssembler)
        val traversalRadius: Int? = null,
        val infrastructureVisibility: String? = null
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
                "RENAME_TAB" -> json.decodeFromString<RenameTab>(jsonString)
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
                // Block 5C: Frontend logging
                "FRONTEND_LOG" -> json.decodeFromString<FrontendLog>(jsonString)
                // Block 5: Context settings request
                "REQUEST_CONTEXT_SETTINGS" -> json.decodeFromString<RequestContextSettings>(jsonString)
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
            // Block 5: Context settings
            is ContextSettingsEvent -> json.encodeToString(ContextSettingsEvent.serializer(), event)
        }
    }
}

private val kotlinx.serialization.json.JsonElement.jsonObject
    get() = this as kotlinx.serialization.json.JsonObject