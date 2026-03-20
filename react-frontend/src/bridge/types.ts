/**
 * TypeScript mirror of the Kotlin bridge message contract (BridgeMessage.kt).
 *
 * ## Structure
 *
 * - {@link BridgeCommand}: Messages from the frontend (React) to the backend (Kotlin).
 *   Serialized to JSON and sent via the transport layer.
 *
 * - {@link BridgeEvent}: Messages from the backend (Kotlin) to the frontend (React).
 *   Deserialized from JSON received via the transport layer.
 *
 * ## Sync with Kotlin
 *
 * Every type here has a mirror in `BridgeMessage.kt`. When adding a new
 * message type:
 * 1. Add it in BridgeMessage.kt (Kotlin sealed class)
 * 2. Add it here (TypeScript interface + union member)
 * 3. Handle it in BridgeDispatcher.kt (Kotlin routing)
 * 4. Handle it in the React component (event subscription)
 *
 * ## Serialization Format
 *
 * The `type` field is the discriminator. JSON format is identical on both
 * sides — no translation layer needed. Kotlin uses kotlinx.serialization
 * with matching @SerialName annotations.
 *
 * ## R4 Changes
 *
 * Added tab management commands (SWITCH_TAB, CLOSE_TAB, SAVE_TAB_STATE,
 * REQUEST_TAB_STATE) and events (TAB_STATE). Added TOGGLE_STAR command
 * and STAR_UPDATED event for exchange-level starring. Added LOAD_CONVERSATION
 * command and CONVERSATION_HISTORY event for restoring tab content.
 *
 * ## Per-Tab Provider Changes
 *
 * - SendMessageCommand: gains providerId so the backend uses the tab's
 *   selected provider rather than the global selection.
 * - TabStateDto: gains providerId so per-tab provider survives IDE restart.
 * - SWITCH_TAB_PROVIDER: new command for changing provider on a specific tab
 *   without affecting the global AiProfilesState selection.
 *
 * @see BridgeMessage.kt — Kotlin counterpart
 * @see transport.ts — Wire layer that sends/receives these messages
 */

// ═══════════════════════════════════════════════════════════════════════
//  COMMAND TYPES (Frontend → Backend)
// ═══════════════════════════════════════════════════════════════════════

/**
 * All possible command type discriminators.
 *
 * Each value corresponds to a @SerialName in BridgeMessage.kt.
 * Used as the `type` field in every command JSON payload.
 */
export const CommandType = {
    /** User submits a chat message. @see BridgeMessage.SendMessage */
    SEND_MESSAGE: "SEND_MESSAGE",

    /** User confirms the heuristic parse was correct. @see BridgeMessage.ConfirmCorrection */
    CONFIRM_CORRECTION: "CONFIRM_CORRECTION",

    /** User wants to pick a different extraction. @see BridgeMessage.RequestCorrection */
    REQUEST_CORRECTION: "REQUEST_CORRECTION",

    /** User clears the current chat display. @see BridgeMessage.ClearChat */
    CLEAR_CHAT: "CLEAR_CHAT",

    /** User starts a new conversation. @see BridgeMessage.NewConversation */
    NEW_CONVERSATION: "NEW_CONVERSATION",

    /**
     * User selects a different global AI provider.
     * Updates AiProfilesState.selectedChatProfileId — the fallback for
     * tabs with no per-tab provider set. @see BridgeMessage.SwitchProvider
     */
    SWITCH_PROVIDER: "SWITCH_PROVIDER",

    /** Frontend requests the list of available providers. @see BridgeMessage.RequestProviders */
    REQUEST_PROVIDERS: "REQUEST_PROVIDERS",

    // ── Per-Tab Provider ─────────────────────────────────────────────

    /**
     * User switches the provider for a specific tab.
     *
     * Unlike SWITCH_PROVIDER (which updates the global selection),
     * this updates only the provider associated with the given tab.
     * The selection is persisted in open_tabs.provider_id.
     *
     * @see BridgeMessage.SwitchTabProvider
     * @see BridgeDispatcher.handleSwitchTabProvider
     */
    SWITCH_TAB_PROVIDER: "SWITCH_TAB_PROVIDER",

    // ── R4: Tab Management ───────────────────────────────────────────

    /**
     * User switches to a different tab.
     * Sent when the user clicks a tab in the tab bar. The backend updates
     * the active tab in open_tabs and may need to load conversation history.
     * @see BridgeMessage.SwitchTab
     */
    SWITCH_TAB: "SWITCH_TAB",

    /**
     * User closes a tab.
     * Backend removes the tab from open_tabs. If it was the last tab,
     * the frontend creates a fresh empty one locally.
     * @see BridgeMessage.CloseTab
     */
    CLOSE_TAB: "CLOSE_TAB",

    /**
     * User renamed a tab via double-click inline edit.
     * Backend updates conversations.title when conversationId is present.
     * No event sent back — frontend already applied the rename optimistically.
     * @see BridgeMessage.RenameTab
     */
    RENAME_TAB: "RENAME_TAB",

    /**
     * Frontend persists current tab state to the backend.
     * Sent on tab switch, new message, or periodic save. Includes tab order,
     * active tab, scroll positions, titles, and per-tab provider IDs.
     * Backend writes to open_tabs table.
     * @see BridgeMessage.SaveTabState
     */
    SAVE_TAB_STATE: "SAVE_TAB_STATE",

    /**
     * Frontend requests the saved tab state on startup.
     * Backend reads open_tabs table and returns TAB_STATE event.
     * Only relevant when keep_tabs setting is true.
     * @see BridgeMessage.RequestTabState
     */
    REQUEST_TAB_STATE: "REQUEST_TAB_STATE",

    // ── R4: Conversation History ─────────────────────────────────────

    /**
     * Frontend requests the message history for a conversation.
     * Sent when restoring a tab that has a conversationId but no messages
     * loaded in memory (e.g., after IDE restart with keep_tabs enabled).
     * @see BridgeMessage.LoadConversation
     */
    LOAD_CONVERSATION: "LOAD_CONVERSATION",

    // ── R4: Exchange Starring ────────────────────────────────────────

    /**
     * User toggles the star (favourite) on an assistant response.
     * The star is at the exchange level — entire response, not individual
     * code blocks (that's the bookmark, which is R5).
     * @see BridgeMessage.ToggleStar
     */
    TOGGLE_STAR: "TOGGLE_STAR",
    BOOKMARK_CODE_BLOCK: "BOOKMARK_CODE_BLOCK",
    OPEN_CONVERSATION: "OPEN_CONVERSATION",

    // ── Dev Commands ──────────────────────────────────────────────
    /** User typed a /dev-* command. @see BridgeMessage.DevCommand */
    DEV_COMMAND: "DEV_COMMAND",

    // ── Block 5C: Frontend Logging ──────────────────────────────
    /**
     * Frontend log entry routed to the IDE's idea.log via Dev.info/warn/error.
     *
     * Replaces dead console.log calls inside JCEF. The backend tags these
     * as "react.{source}" so they're easily identifiable in idea.log.
     * In Vite dev mode, log.ts falls back to native console methods instead
     * of sending this command.
     *
     * @see log.ts — frontend utility that sends these
     * @see BridgeMessage.FrontendLog — Kotlin command class
     * @see BridgeDispatcher.handleFrontendLog — Kotlin handler
     */
    FRONTEND_LOG: "FRONTEND_LOG",

    // ── Block 5: Context Settings ────────────────────────────────
    /**
     * Frontend requests project-level context settings on startup.
     * Backend reads ContextSettingsState and emits CONTEXT_SETTINGS event.
     *
     * @see BridgeMessage.RequestContextSettings — Kotlin command class
     * @see BridgeDispatcher.handleRequestContextSettings — Kotlin handler
     */
    REQUEST_CONTEXT_SETTINGS: "REQUEST_CONTEXT_SETTINGS",
} as const;

export type CommandType = (typeof CommandType)[keyof typeof CommandType];

// ── Individual Command Interfaces ────────────────────────────────────

/**
 * User submits a chat message. Mirrors BridgeMessage.SendMessage.
 *
 * providerId: the profile ID for the active tab's per-tab provider selection.
 * Null means the backend should use the globally selected chat provider
 * (AiProfilesState.selectedChatProfileId). Set by useBridge.sendMessage()
 * from the active tab's TabData.providerId.
 */
export interface SendMessageCommand {
    type: typeof CommandType.SEND_MESSAGE;
    text: string;
    conversationId: string | null;
    providerId: string | null;
    /**
     * Context bypass mode for this message.
     *
     * Controls whether the backend's ContextAssembler gathers IDE context
     * before sending the prompt to the AI provider.
     *
     * - null / "OFF": Normal flow — full context gathering runs.
     * - "FULL": Skip all context gathering (steps 1-4). System prompt and
     *   conversation history still flow (handled by orchestrator, not assembler).
     * - "SELECTIVE": Pro-tier per-component bypass. Currently a STUB — treated
     *   as OFF until Phase C wires the ContextLever component.
     *
     * @see ContextAssembler.assemble — checks this field for early return
     * @see Feature.CONTEXT_SELECTIVE_BYPASS — tier gate for SELECTIVE mode
     */
    bypassMode: string | null;
    /**
     * Lever position when bypassMode = "SELECTIVE".
     * 0 = Minimal, 1 = Partial, 2 = Full. Null when not in SELECTIVE mode.
     * Threaded to ContextAssembler.assemble() via ChatOrchestrator.send().
     */
    selectiveLevel: number | null;
}

/** User confirms heuristic was correct. Mirrors BridgeMessage.ConfirmCorrection. */
export interface ConfirmCorrectionCommand {
    type: typeof CommandType.CONFIRM_CORRECTION;
}

/** User wants a different extraction. Mirrors BridgeMessage.RequestCorrection. */
export interface RequestCorrectionCommand {
    type: typeof CommandType.REQUEST_CORRECTION;
}

/** User clears the chat display. Mirrors BridgeMessage.ClearChat. */
export interface ClearChatCommand {
    type: typeof CommandType.CLEAR_CHAT;
}

/** User starts a new conversation. Mirrors BridgeMessage.NewConversation. */
export interface NewConversationCommand {
    type: typeof CommandType.NEW_CONVERSATION;
}

/**
 * User selects a different global AI provider. Mirrors BridgeMessage.SwitchProvider.
 *
 * Updates the global fallback provider (AiProfilesState.selectedChatProfileId).
 * Does not affect per-tab provider selections. For per-tab changes use
 * SwitchTabProviderCommand instead.
 */
export interface SwitchProviderCommand {
    type: typeof CommandType.SWITCH_PROVIDER;
    providerId: string;
}

/** Frontend requests provider list. Mirrors BridgeMessage.RequestProviders. */
export interface RequestProvidersCommand {
    type: typeof CommandType.REQUEST_PROVIDERS;
}

/**
 * User switches the provider for a specific tab. Mirrors BridgeMessage.SwitchTabProvider.
 *
 * Updates only the provider_id for the given tab in open_tabs. Does NOT
 * affect AiProfilesState.selectedChatProfileId (the global selection).
 * The next SEND_MESSAGE from this tab will use the new provider.
 */
export interface SwitchTabProviderCommand {
    type: typeof CommandType.SWITCH_TAB_PROVIDER;
    tabId: string;
    providerId: string;
}

/**
 * User switches to a different tab. Mirrors BridgeMessage.SwitchTab.
 *
 * The tabId is the frontend tab identifier. If the tab has a conversationId,
 * the backend may update the orchestrator's active conversation.
 */
export interface SwitchTabCommand {
    type: typeof CommandType.SWITCH_TAB;
    tabId: string;
}

/**
 * User closes a tab. Mirrors BridgeMessage.CloseTab.
 *
 * Backend removes from open_tabs. The conversation itself is NOT deleted —
 * it remains accessible from the Library.
 */
export interface CloseTabCommand {
    type: typeof CommandType.CLOSE_TAB;
    tabId: string;
}

/**
 * User renamed a tab via double-click inline edit. Mirrors BridgeMessage.RenameTab.
 *
 * conversationId is null for fresh tabs that have never sent a message — the
 * backend skips the DB update in that case. When present, the backend updates
 * conversations.title so the rename is durable across IDE restarts.
 *
 * No event is sent back — the frontend already applied the rename optimistically
 * and the new title will persist via the next SAVE_TAB_STATE command.
 */
export interface RenameTabCommand {
    type: typeof CommandType.RENAME_TAB;
    tabId: string;
    conversationId: string | null;
    title: string;
}

/**
 * Frontend saves the full tab state to the backend. Mirrors BridgeMessage.SaveTabState.
 *
 * Sent periodically and on meaningful state changes (new message, tab switch,
 * tab close, provider change). The backend writes this to the open_tabs SQLite table.
 *
 * The tabs array contains the complete current state — backend does a full
 * replace (delete all + insert) rather than incremental updates. Simpler
 * and avoids sync issues.
 */
export interface SaveTabStateCommand {
    type: typeof CommandType.SAVE_TAB_STATE;
    tabs: TabStateDto[];
    activeTabId: string;
}

/**
 * Frontend requests saved tab state on startup. Mirrors BridgeMessage.RequestTabState.
 *
 * Backend checks keep_tabs setting. If true, reads open_tabs and sends
 * TAB_STATE event. If false, sends empty TAB_STATE so the frontend
 * creates a single fresh tab.
 */
export interface RequestTabStateCommand {
    type: typeof CommandType.REQUEST_TAB_STATE;
}

/**
 * Frontend requests conversation history. Mirrors BridgeMessage.LoadConversation.
 *
 * Used when restoring tabs after restart — the tab knows its conversationId
 * but has no messages in memory. Backend reads from JSONL/SQLite and sends
 * back CONVERSATION_HISTORY with the full message list.
 */
export interface LoadConversationCommand {
    type: typeof CommandType.LOAD_CONVERSATION;
    conversationId: string;
    tabId: string;
}

/**
 * User toggles star on an exchange. Mirrors BridgeMessage.ToggleStar.
 *
 * exchangeId identifies the specific assistant response. The backend
 * toggles the star state in storage and sends back STAR_UPDATED to
 * confirm the new state.
 */
export interface ToggleStarCommand {
    type: typeof CommandType.TOGGLE_STAR;
    exchangeId: string;
}

/** Bookmark an exchange via a code block ribbon click. */
export interface BookmarkCodeBlockCommand {
    type: typeof CommandType.BOOKMARK_CODE_BLOCK;
    exchangeId: string;
    blockIndex: number;
}

/**
 * Open a conversation in the Chat tab from the Library.
 *
 * TEMPORARY: This command is part of the cross-panel bridge between
 * the vanilla HTML Library and the React Chat. Remove when Library
 * migrates to React and both panels share the same BridgeDispatcher.
 */
export interface OpenConversationCommand {
    type: typeof CommandType.OPEN_CONVERSATION;
    conversationId: string;
}

/**
 * User typed a /dev-* command. Mirrors BridgeMessage.DevCommand.
 *
 * Frontend intercepts /dev- prefixed input and sends as DEV_COMMAND
 * instead of SEND_MESSAGE. Only functional when dev mode is enabled.
 */
export interface DevCommandCommand {
    type: typeof CommandType.DEV_COMMAND;
    text: string;
}

/**
 * Frontend log entry. Mirrors BridgeMessage.FrontendLog.
 *
 * Sent by log.ts to route React-side logs to idea.log via the
 * Dev logging system. The backend tags these as "react.{source}".
 *
 * @see log.ts — sends these commands
 */
export interface FrontendLogCommand {
    type: typeof CommandType.FRONTEND_LOG;
    /** Log severity: "INFO", "WARN", or "ERROR". */
    level: string;
    /** Human-readable log message (may include formatted context data). */
    message: string;
    /** The React module or component that produced this log (e.g., "useBridge"). */
    source: string;
}

/**
 * Frontend requests project-level context settings. Mirrors BridgeMessage.RequestContextSettings.
 *
 * Sent at BRIDGE_READY (and non-JCEF dev startup). Backend reads ContextSettingsState
 * and emits CONTEXT_SETTINGS event with contextEnabled + defaultBypassMode.
 */
export interface RequestContextSettingsCommand {
    type: typeof CommandType.REQUEST_CONTEXT_SETTINGS;
}

/**
 * Union of all command types.
 *
 * The transport layer serializes this to JSON before sending.
 * TypeScript's discriminated union on `type` gives exhaustive matching.
 */
export type BridgeCommand =
    | SendMessageCommand
    | ConfirmCorrectionCommand
    | RequestCorrectionCommand
    | ClearChatCommand
    | NewConversationCommand
    | SwitchProviderCommand
    | RequestProvidersCommand
    | SwitchTabProviderCommand
    | SwitchTabCommand
    | CloseTabCommand
    | RenameTabCommand
    | SaveTabStateCommand
    | RequestTabStateCommand
    | LoadConversationCommand
    | ToggleStarCommand
    | BookmarkCodeBlockCommand
    | OpenConversationCommand
    | DevCommandCommand
    | FrontendLogCommand
    | RequestContextSettingsCommand;

// ═══════════════════════════════════════════════════════════════════════
//  EVENT TYPES (Backend → Frontend)
// ═══════════════════════════════════════════════════════════════════════

/**
 * All possible event type discriminators.
 *
 * Each value corresponds to a serialized `type` field from BridgeMessage.kt.
 * Used to discriminate the incoming JSON in event handlers.
 */
export const EventType = {
    /** Main AI response. @see BridgeMessage.ChatResultEvent */
    CHAT_RESULT: "CHAT_RESULT",

    /** Show the "Thinking..." indicator. @see BridgeMessage.ShowThinkingEvent */
    SHOW_THINKING: "SHOW_THINKING",

    /** Hide the "Thinking..." indicator. @see BridgeMessage.HideThinkingEvent */
    HIDE_THINKING: "HIDE_THINKING",

    /** Token usage metrics update. @see BridgeMessage.UpdateMetricsEvent */
    UPDATE_METRICS: "UPDATE_METRICS",

    /** System notification (not AI). @see BridgeMessage.SystemMessageEvent */
    SYSTEM_MESSAGE: "SYSTEM_MESSAGE",

    /** Correction candidates for user pick. @see BridgeMessage.CorrectionCandidatesEvent */
    CORRECTION_CANDIDATES: "CORRECTION_CANDIDATES",

    /** List of available AI providers. @see BridgeMessage.ProvidersListEvent */
    PROVIDERS_LIST: "PROVIDERS_LIST",

    /** Conversation was cleared/reset. @see BridgeMessage.ConversationClearedEvent */
    CONVERSATION_CLEARED: "CONVERSATION_CLEARED",

    /** Bridge is ready — JCEF query function injected. @see JcefBridgeTransport */
    BRIDGE_READY: "BRIDGE_READY",

    // ── R4: Tab Events ───────────────────────────────────────────────

    /**
     * Saved tab state from the backend.
     * Sent in response to REQUEST_TAB_STATE. Contains the list of tabs
     * that were open when the IDE last closed, plus the keep_tabs setting.
     * @see BridgeMessage.TabStateEvent
     */
    TAB_STATE: "TAB_STATE",

    /**
     * Conversation history for a restored tab.
     * Sent in response to LOAD_CONVERSATION. Contains all messages for
     * a conversation, allowing the frontend to populate a tab.
     * @see BridgeMessage.ConversationHistoryEvent
     */
    CONVERSATION_HISTORY: "CONVERSATION_HISTORY",

    /**
     * Star state confirmation after a TOGGLE_STAR command.
     * Sent back so the frontend can update the star icon state.
     * @see BridgeMessage.StarUpdatedEvent
     */
    STAR_UPDATED: "STAR_UPDATED",
    BOOKMARK_RESULT: "BOOKMARK_RESULT",
    OPEN_CONVERSATION_RESULT: "OPEN_CONVERSATION_RESULT",

    // ── Dev Events ───────────────────────────────────────────────
    /** Dev command output, rendered as system message. */
    DEV_OUTPUT: "DEV_OUTPUT",

    // ── Block 5: Context Settings ────────────────────────────────
    /**
     * Project-level context settings from the backend.
     * Sent in response to REQUEST_CONTEXT_SETTINGS on startup.
     * @see BridgeMessage.ContextSettingsEvent — Kotlin event class
     */
    CONTEXT_SETTINGS: "CONTEXT_SETTINGS",
} as const;

export type EventType = (typeof EventType)[keyof typeof EventType];

// ── Individual Event Interfaces ──────────────────────────────────────

/** Token usage breakdown. Mirrors BridgeMessage.TokenUsageDto. */
export interface TokenUsageDto {
    promptTokens: number | null;
    completionTokens: number | null;
    totalTokens: number | null;
}

/**
 * Main AI response event. Mirrors BridgeMessage.ChatResultEvent.
 *
 * This is the richest event — carries the display text, metadata about
 * how it was parsed, token usage, and whether correction is available.
 */
export interface ChatResultEvent {
    type: typeof EventType.CHAT_RESULT;
    displayText: string;
    isError: boolean;
    exchangeId: string | null;
    conversationId: string | null;
    correctionAvailable: boolean;
    parseStrategy: string;
    confidence: string;
    providerId: string | null;
    modelId: string | null;
    contextSummary: string | null;
    contextTimeMs: number | null;
    tokenUsage: TokenUsageDto | null;
}

/** Show the thinking indicator. Mirrors BridgeMessage.ShowThinkingEvent. */
export interface ShowThinkingEvent {
    type: typeof EventType.SHOW_THINKING;
}

/** Hide the thinking indicator. Mirrors BridgeMessage.HideThinkingEvent. */
export interface HideThinkingEvent {
    type: typeof EventType.HIDE_THINKING;
}

/**
 * Token usage metrics update. Mirrors BridgeMessage.UpdateMetricsEvent.
 *
 * Sent after every AI call (chat or summary) when token data is available.
 * Drives the MetricsBar component: last exchange display, session
 * accumulator, and context fill bar.
 *
 * ## Metrics Module Changes (Block 2)
 *
 * - REMOVED: estimatedCost (cost is the Pricing Module's job, post-launch)
 * - ADDED: contextWindowSize (model's max context for fill bar)
 * - ADDED: responseTimeMs (wall-clock call duration for future UI)
 * - ADDED: purpose (CHAT | FILE_SUMMARY | etc. for filtering)
 *
 * All new fields are nullable — null means "not available" and the
 * corresponding UI element is simply hidden.
 */
export interface UpdateMetricsEvent {
    type: typeof EventType.UPDATE_METRICS;

    /** Model identifier (e.g., "gpt-4o", "gemini-2.5-flash"). Null if unknown. */
    model: string | null;

    /** Input tokens sent to the model. Null if the provider didn't report this. */
    promptTokens: number | null;

    /** Output tokens generated by the model. Null if the provider didn't report this. */
    completionTokens: number | null;

    /** Provider-reported total tokens. Null if the provider didn't report this. */
    totalTokens: number | null;

    /**
     * Model's maximum context window in tokens.
     * Resolved from AiProfile.contextWindowSize or DefaultContextWindows.
     * Null if unknown — fill bar is hidden.
     */
    contextWindowSize: number | null;

    /**
     * Wall-clock time for the AI call in milliseconds.
     * Null if timing wasn't captured.
     */
    responseTimeMs: number | null;

    /**
     * Exchange purpose: "CHAT", "FILE_SUMMARY", "METHOD_SUMMARY", etc.
     * Null for legacy events that don't carry purpose.
     */
    purpose: string | null;
}

/**
 * System notification. Mirrors BridgeMessage.SystemMessageEvent.
 *
 * Used for non-AI messages like context readiness, correction hints,
 * and format confirmation feedback.
 */
export interface SystemMessageEvent {
    type: typeof EventType.SYSTEM_MESSAGE;
    content: string;
    level: "INFO" | "WARN" | "ERROR";
}

/** Correction candidate. Mirrors BridgeMessage.CorrectionCandidateDto. */
export interface CorrectionCandidateDto {
    text: string;
    path: string;
    confidence: string;
}

/**
 * Correction candidates event. Mirrors BridgeMessage.CorrectionCandidatesEvent.
 *
 * Sent when the user clicks "Not right" and the dispatcher finds
 * alternative text extractions from the raw response.
 */
export interface CorrectionCandidatesEvent {
    type: typeof EventType.CORRECTION_CANDIDATES;
    candidates: CorrectionCandidateDto[];
}

/**
 * Provider info DTO. Mirrors BridgeMessage.ProviderInfoDto.
 *
 * Enhanced in Metrics Module (Block 2) with model and contextWindowSize
 * so the MetricsBar can show model name and compute the context fill
 * percentage without waiting for the first AI response.
 *
 * @see BridgeDispatcher.handleRequestProviders — populates these
 * @see ProviderSelector.tsx — renders the dropdown
 * @see MetricsBar.tsx — uses model and contextWindowSize
 */
export interface ProviderInfoDto {
    /** AI profile ID (AiProfile.id). */
    id: string;

    /** User-friendly profile label (e.g., "My Gemini"). */
    label: string;

    /** API protocol: "OPENAI_COMPAT", "GEMINI", "CUSTOM". */
    protocol: string;

    /**
     * Model identifier from the profile (e.g., "gpt-4o").
     * Null if the profile doesn't have a model configured.
     */
    model: string | null;

    /**
     * Model's maximum context window in tokens.
     * Resolved from AiProfile.contextWindowSize or DefaultContextWindows.
     * Null if unknown.
     */
    contextWindowSize: number | null;
}

/**
 * Provider list event. Mirrors BridgeMessage.ProvidersListEvent.
 *
 * Sent in response to REQUEST_PROVIDERS. Drives the provider dropdown.
 */
export interface ProvidersListEvent {
    type: typeof EventType.PROVIDERS_LIST;
    providers: ProviderInfoDto[];
    selectedId: string | null;
}

/** Conversation cleared event. Mirrors BridgeMessage.ConversationClearedEvent. */
export interface ConversationClearedEvent {
    type: typeof EventType.CONVERSATION_CLEARED;
}

/**
 * Bridge ready event. Sent by JcefBridgeTransport after injecting __ymm_cefQuery.
 *
 * This signals that the JS→Kotlin command channel is operational. React should
 * wait for this event before sending any commands (like REQUEST_PROVIDERS).
 * Without this, commands sent during mount would fall through to the mock handler
 * because __ymm_cefQuery doesn't exist yet.
 *
 * No Kotlin BridgeMessage counterpart — this is sent directly by the transport,
 * not routed through BridgeDispatcher.
 */
export interface BridgeReadyEvent {
    type: typeof EventType.BRIDGE_READY;
}

// ── R4: Tab & Star Event Interfaces ──────────────────────────────────

/**
 * DTO for a single tab's persisted state.
 *
 * Used in both SaveTabStateCommand (frontend → backend) and
 * TabStateEvent (backend → frontend). Same shape in both directions.
 *
 * providerId: the AI profile selected for this specific tab.
 * Null means "use the global chat provider selection".
 * Persisted in open_tabs.provider_id so per-tab provider survives IDE restarts.
 *
 * traversalRadius: per-tab override for context depth (hops in dependency graph).
 * Null = use project-level default from ContextSettingsState.
 * STUB: not yet read by the context system. See ContextSettingsState.kt for full docs.
 *
 * infrastructureVisibility: per-tab override for how high-fan-in cross-cutting
 * classes (Auth, Logger, DB, DI, etc.) are included in context.
 * Values: "OFF" | "BRIEF" | "DETAIL". Null = use project-level default.
 * STUB: not yet read by the context system. See ContextSettingsState.kt for full docs.
 */
export interface TabStateDto {
    /** Unique tab identifier (generated by frontend). */
    id: string;
    /** Associated conversation ID in storage, or null for fresh tabs. */
    conversationId: string | null;
    /** Tab display title (auto-generated from first message or "New Chat"). */
    title: string;
    /** Position in the tab bar (0-indexed, left to right). */
    tabOrder: number;
    /** Whether this tab is the active/visible one. */
    isActive: boolean;
    /** Scroll position in pixels for restoring exact scroll state. */
    scrollPosition: number;
    /** Per-tab AI provider profile ID. Null = use global selection. */
    providerId: string | null;
    /**
     * Dial position in dial perspective (not backend bypass perspective).
     * "FULL" = context on (default), "OFF" = no context, "SELECTIVE" = Pro.
     * Persisted in open_tabs.bypass_mode. Default "FULL".
     */
    bypassMode?: string | null;
    /**
     * Lever position when bypassMode = "SELECTIVE".
     * 0 = Minimal, 1 = Partial, 2 = Full. Default 2.
     */
    selectiveLevel?: number | null;
    /** STUB — per-tab traversal radius override. Null = project default. */
    traversalRadius?: number | null;
    /** STUB — per-tab infrastructure visibility override. Null = project default. */
    infrastructureVisibility?: "OFF" | "BRIEF" | "DETAIL" | null;
}

/**
 * Saved tab state from the backend. Mirrors BridgeMessage.TabStateEvent.
 *
 * Sent in response to REQUEST_TAB_STATE on startup. If keep_tabs is false
 * or no tabs were saved, the tabs array is empty and the frontend creates
 * a single fresh tab.
 */
export interface TabStateEvent {
    type: typeof EventType.TAB_STATE;
    /** Saved tabs from the open_tabs table, ordered by tabOrder. */
    tabs: TabStateDto[];
    /** Whether the keep_tabs setting is enabled. Drives the UI toggle. */
    keepTabs: boolean;
}

/**
 * DTO for a single message in conversation history.
 *
 * Simpler than ChatMessage — only the fields needed to reconstruct
 * the display. The backend reads from JSONL/SQLite and maps to this.
 */
export interface HistoryMessageDto {
    /** Role: "user" or "assistant". System messages are not persisted in history. */
    role: "user" | "assistant";
    /** Message content (markdown for assistant, plain for user). */
    content: string;
    /** ISO timestamp from storage. */
    timestamp: string;
    /** Exchange ID for assistant messages (null for user messages). */
    exchangeId: string | null;
    /** Whether this exchange is starred (favourite). */
    isStarred: boolean;
    /**
     * Token usage from this exchange. Populated from chat_exchanges columns
     * (prompt_tokens, completion_tokens, total_tokens, model_id).
     * Null for user messages (only assistant exchanges have token data).
     *
     * Used by the CONVERSATION_HISTORY handler in useBridge to seed the
     * tab's metrics accumulator, so the MetricsBar shows historical usage
     * immediately when a conversation is reopened.
     */
    promptTokens: number | null;
    completionTokens: number | null;
    totalTokens: number | null;
    model: string | null;
}

/**
 * Conversation history event. Mirrors BridgeMessage.ConversationHistoryEvent.
 *
 * Sent in response to LOAD_CONVERSATION. The frontend populates the
 * target tab with these messages.
 */
export interface ConversationHistoryEvent {
    type: typeof EventType.CONVERSATION_HISTORY;
    /** Which tab requested this history (echoed from LoadConversationCommand). */
    tabId: string;
    /** The conversation ID these messages belong to. */
    conversationId: string;
    /** Messages in chronological order. */
    messages: HistoryMessageDto[];
}

/**
 * Star state confirmation. Mirrors BridgeMessage.StarUpdatedEvent.
 *
 * Sent after a TOGGLE_STAR command. The frontend updates the star icon
 * on the matching message.
 */
export interface StarUpdatedEvent {
    type: typeof EventType.STAR_UPDATED;
    /** The exchange that was starred/unstarred. */
    exchangeId: string;
    /** New star state: true = starred, false = not starred. */
    isStarred: boolean;
}

/** Bookmark result confirmation from backend. */
export interface BookmarkResultEvent {
    type: typeof EventType.BOOKMARK_RESULT;
    exchangeId: string;
    success: boolean;
    error?: string;
}

/** Confirmation that a conversation was opened in a tab. */
export interface OpenConversationResultEvent {
    type: typeof EventType.OPEN_CONVERSATION_RESULT;
    conversationId: string;
    tabId: string;
    title: string;
}

/**
 * Dev command output. Mirrors BridgeMessage.DevOutputEvent.
 * Rendered as a system message in the chat UI.
 */
export interface DevOutputEvent {
    type: typeof EventType.DEV_OUTPUT;
    content: string;
}

/**
 * Project-level context settings event. Mirrors BridgeMessage.ContextSettingsEvent.
 *
 * Sent in response to REQUEST_CONTEXT_SETTINGS on startup.
 * The React app uses this to:
 * - Set globalContextEnabled: when false, the ContextDial is greyed out.
 * - Set defaultBypassMode: dial position for new tabs ("FULL" or "OFF").
 *   Basic-tier users always receive "FULL" regardless of stored value.
 */
export interface ContextSettingsEvent {
    type: typeof EventType.CONTEXT_SETTINGS;
    /**
     * Master kill-switch from ContextSettingsState.contextEnabled.
     * When false, all context gathering is disabled and the ContextDial
     * is visually disabled (clicks rejected).
     */
    contextEnabled: boolean;
    /**
     * Default dial position for new tabs (dial perspective).
     * "FULL" = full context on (default), "OFF" = no context.
     * "SELECTIVE" only if Pro tier; Basic always receives "FULL".
     */
    defaultBypassMode: string;
}

/**
 * Union of all event types.
 *
 * The transport layer deserializes incoming JSON into one of these.
 * TypeScript's discriminated union on `type` gives exhaustive matching.
 */
export type BridgeEvent =
    | ChatResultEvent
    | ShowThinkingEvent
    | HideThinkingEvent
    | UpdateMetricsEvent
    | SystemMessageEvent
    | CorrectionCandidatesEvent
    | ProvidersListEvent
    | ConversationClearedEvent
    | BridgeReadyEvent
    | TabStateEvent
    | ConversationHistoryEvent
    | StarUpdatedEvent
    | BookmarkResultEvent
    | OpenConversationResultEvent
    | DevOutputEvent
    | ContextSettingsEvent;