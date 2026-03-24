/**
 * React hook for bridge communication.
 *
 * ## R5 Changes
 *
 * - Added bookmarkCodeBlock() command for code block bookmark ribbon
 * - Exposed scrollPosition from active tab for scroll restore
 * - Added BOOKMARK_RESULT event handler for bookmark confirmation
 *
 * ## R4 Restructure: Per-Tab State
 *
 * Before R4, state was flat: one messages[], one isThinking, one metrics.
 * Now each tab has its own isolated state via TabData. The hook exposes the
 * active tab's state for rendering and provides tab management functions.
 *
 * State structure:
 *   tabs: Map<tabId, TabData>    — per-tab conversation data
 *   tabOrder: string[]           — tab IDs in display order (left to right)
 *   activeTabId: string          — currently visible tab
 *
 * Components don't need to know about the map — they get the active tab's
 * messages, metrics, etc. directly from the hook return value.
 *
 * ## Tab Lifecycle
 *
 * 1. On startup: REQUEST_TAB_STATE → backend returns saved tabs (if keep_tabs)
 * 2. If no saved tabs (or keep_tabs=false): create one fresh "New Chat" tab
 * 3. "+" button in TabBar: creates a new tab, switches to it
 * 4. Tab click: switches active tab, saves state to backend
 * 5. Tab close: removes tab, switches to neighbor. Last tab → new empty tab.
 * 6. On meaningful changes: SAVE_TAB_STATE sent to backend for persistence
 *
 * ## Per-Tab Provider
 *
 * Each tab has its own providerId (string | null). When null, the backend
 * falls back to AiProfilesState.selectedChatProfileId (global selection).
 * The active tab's providerId is included in every SEND_MESSAGE command so
 * the backend always uses the correct provider without additional lookup.
 * Provider changes are sent immediately via SWITCH_TAB_PROVIDER and are
 * also persisted in the next SAVE_TAB_STATE cycle.
 *
 * ## PLACEHOLDER: keep_tabs Preference
 *
 * On startup, REQUEST_TAB_STATE is always sent. The backend decides
 * whether to return saved tabs based on the keep_tabs setting.
 * The keep_tabs toggle UI is NOT YET IMPLEMENTED — it lives in the
 * General settings config page (Tools → YMM Assistant → General).
 * Backend default: keep_tabs = true.
 *
 * ## Max Tabs Config
 *
 * The backend sends maxTabs in the TAB_STATE event (read from General
 * settings → "Maximum open tabs", range 2–20). DEFAULT_MAX_TABS = 5
 * is kept as a fallback for defensive parsing.
 *
 * @see transport.ts — Wire layer that this hook wraps
 * @see types.ts — Event and command type definitions
 */

import { useState, useEffect, useCallback, useRef } from "react";
import { sendCommand, onEvent, isJcefMode } from "../bridge/transport";
import {
    EventType,
    CommandType,
    type BridgeEvent,
    type ChatResultEvent,
    type UpdateMetricsEvent,
    type SystemMessageEvent,
    type ProvidersListEvent,
    type TabStateEvent,
    type ConversationHistoryEvent,
    type StarUpdatedEvent,
    type BookmarkResultEvent,
    type OpenConversationResultEvent,
    type DevOutputEvent,
    type ContextSettingsEvent,
    type TabStateDto, ProviderInfoDto,
} from "../bridge/types";
import { createAccumulator, accumulate, contextFillPercent } from "../metrics";
import type {TabMetricsState, MetricsSnapshot} from "../metrics";
import { log } from "../utils/log";

// ═══════════════════════════════════════════════════════════════════════
//  CONSTANTS
// ═══════════════════════════════════════════════════════════════════════

/**
 * Default maximum simultaneous open tabs.
 *
 * Fallback used until the backend sends the configured value via
 * TAB_STATE event (General settings → "Maximum open tabs", range 2–20).
 */
const DEFAULT_MAX_TABS = 5;

// ═══════════════════════════════════════════════════════════════════════
//  LOCAL STATE TYPES
// ═══════════════════════════════════════════════════════════════════════

/**
 * A single message displayed in the chat.
 * R4: Added isStarred field for exchange-level favouriting.
 */
export interface ChatMessage {
    id: string;
    role: "user" | "assistant" | "system";
    content: string;
    timestamp: string;
    isError: boolean;
    exchangeId: string | null;
    correctionAvailable: boolean;
    /** R4: Whether this exchange is starred (favourite). Only meaningful for assistant messages. */
    isStarred: boolean;
    /**
     * Block 5C: Human-readable manifest of what context was attached.
     * e.g., "[Context: 5 files (3 raw, 2 summaries), ~12400 chars]"
     * Null for user messages, system messages, and exchanges with no context.
     * Phase 1 (launch): rendered as compact text in ContextBadgeTray.
     * Phase 2 (post-launch): replaced by structured contextFiles list.
     */
    contextSummary: string | null;
    /**
     * Block 5C: How long context assembly took in milliseconds.
     * Null when no context was gathered.
     */
    contextTimeMs: number | null;
}

/**
 * Per-tab metrics state: last exchange snapshot + session accumulator.
 *
 * Always initialized (never null) — starts with null lastExchange
 * and an empty accumulator. Replaces the old `metrics: MetricsData | null`
 * which only stored the latest exchange and lost session totals.
 *
 * @see TabMetricsState in metrics/types.ts
 */
export interface TabData {
    id: string;
    title: string;
    conversationId: string | null;
    messages: ChatMessage[];
    isThinking: boolean;
    metricsState: TabMetricsState;
    scrollPosition: number;
    /** False for restored tabs that haven't fetched messages yet. */
    historyLoaded: boolean;
    /** Per-tab AI provider. Null = use global selection. */
    providerId: string | null;
    /**
     * Set of message IDs whose assistant responses are collapsed.
     * Persisted per-tab so collapse state survives tab switching.
     * Ephemeral — not saved to backend (resets on IDE restart).
     */
    collapsedIds: Set<string>;
    /**
     * Per-tab summary toggle. Simple ON/OFF, no middle ground.
     *
     * - true:  Messages in this tab get summarisation (if global summary is ON).
     * - false: No summarisation for this tab.
     *
     * Gated by globalSummaryEnabled: if global is OFF, this is irrelevant.
     * Sent with SEND_MESSAGE so the backend can skip summary enrichment.
     * Default: true (summaries enabled when globally allowed).
     */
    summaryEnabled: boolean;
    /**
     * Per-tab context mode (user perspective, NOT backend bypass perspective).
     *
     * - "OFF":    No context for this tab (per-tab override).
     * - "ON":     Context gathering with default reach.
     * - "CUSTOM": Context on + lever controls reach (Pro tier).
     *
     * Translated to backend bypass perspective by dialToBackendBypass()
     * before inclusion in SEND_MESSAGE:
     *   "OFF"    → "FULL"      (full bypass — no context)
     *   "ON"     → null        (no bypass — context runs with defaults)
     *   "CUSTOM" → "SELECTIVE" (per-component bypass)
     *
     * Persisted in open_tabs.bypass_mode via SAVE_TAB_STATE. Default "ON".
     *
     * @see ContextDialStrip — React component that reads/writes this
     * @see dialToBackendBypass — translates for SEND_MESSAGE
     * @see ContextAssembler.assemble — backend checks bypassMode
     */
    bypassMode: "OFF" | "ON" | "CUSTOM";
    /**
     * Lever position when bypassMode = "CUSTOM".
     * 0 = Minimal (open file only), 1 = Partial (no ProjectStructure), 2 = Full.
     * Persisted in open_tabs.selective_level. Default 2.
     *
     * @see ContextLever — React component that reads/writes this
     * @see ContextAssembler.buildDetectorsForLevel — backend uses this value
     */
    selectiveLevel: number;
    /**
     * Force Context scope from the control strip button.
     *
     * null = no force (normal heuristic-driven path).
     * "method" = force the method at cursor into context.
     * "class" = force the class at cursor into context.
     *
     * Complements automatic context — does NOT override it.
     * If the forced element is already in automatic context, no duplication.
     * Resets to null after each Send.
     */
    forceContextScope: "method" | "class" | null;
}

/** R4: Lightweight tab descriptor for the TabBar component. */
export interface TabInfo {
    id: string;
    title: string;
    isActive: boolean;
    hasMessages: boolean;
    isThinking: boolean;
    /**
     * Context window usage percentage (0–100+).
     *
     * Computed from lastExchange metrics via contextFillPercent().
     * Used by TabBar to render the context indicator chip:
     * - null / < 75% → no chip (hidden)
     * - >= 75%       → amber chip ("getting full")
     * - >= 90%       → red chip ("approaching limit")
     *
     * Null until the tab's first exchange provides token data.
     */
    contextUsagePct: number | null;
    /** Per-tab AI provider. Null = using global selection. Shown in TabBar dropdown. */
    providerId: string | null;
}

/**
 * Return type of the useBridge hook.
 * Active tab's state is exposed directly for backward compatibility.
 */
export interface BridgeState {
    // Active tab state
    messages: ChatMessage[];
    isThinking: boolean;
    /** Per-tab metrics: last exchange + session totals. */
    metricsState: TabMetricsState;

    // Global state
    providers: ProviderInfoDto[];
    selectedProviderId: string | null;
    isProduction: boolean;

    // Tab state
    tabs: TabInfo[];
    activeTabId: string;
    isScrolledUp: boolean;

    // Scroll position for restore
    scrollPosition: number;
    // Set of collapsed assistant message IDs for the active tab.
    collapsedIds: Set<string>;

    // Commands (existing)
    sendMessage: (text: string) => void;
    switchProvider: (providerId: string) => void;
    clearChat: () => void;
    newConversation: () => void;
    confirmCorrection: () => void;
    requestCorrection: () => void;
    requestProviders: () => void;

    // Tab commands
    switchTab: (tabId: string) => void;
    closeTab: (tabId: string) => void;
    toggleStar: (exchangeId: string) => void;
    setScrolledUp: (isUp: boolean) => void;
    saveScrollPosition: (position: number) => void;

    /** Toggle collapse state of a single assistant message. */
    toggleCollapse: (messageId: string) => void;
    /** Collapse all assistant messages in the active tab. */
    collapseAll: () => void;
    /** Expand all collapsed messages in the active tab. */
    expandAll: () => void;

    /**
     * Rename a tab title.
     *
     * PLACEHOLDER: Backend command RENAME_TAB not yet implemented.
     * Calling this logs a warning and does nothing until:
     * 1. RENAME_TAB added to CommandType in types.ts
     * 2. Handled in BridgeDispatcher.kt (UPDATE conversations SET title)
     * 3. sendCommand call below uncommented
     */
    renameTab: (tabId: string, newTitle: string) => void;

    /**
     * Switch the AI provider for a specific tab.
     *
     * Updates the tab's local providerId immediately, sends SWITCH_TAB_PROVIDER
     * to the backend for immediate DB persistence, and includes the new
     * providerId in the next SAVE_TAB_STATE for full state sync.
     *
     * Does NOT affect the global selectedProviderId — use switchProvider()
     * for that. Per-tab and global selections are independent.
     *
     * Pass null to revert the tab to the global provider selection.
     */
    switchTabProvider: (tabId: string, providerId: string | null) => void;

    // R5: Bookmark command
    bookmarkCodeBlock: (exchangeId: string, blockIndex: number) => void;

    // Block 5: Per-tab summary toggle
    /**
     * Active tab's summary toggle. Simple ON/OFF per tab.
     * Gated by globalSummaryEnabled — if global is OFF, this is irrelevant.
     */
    summaryEnabled: boolean;
    /**
     * Toggle summaries for the active tab.
     */
    setSummaryEnabled: (enabled: boolean) => void;

    // Block 5: Context mode + settings
    /**
     * Active tab's context mode (user perspective: OFF/ON/CUSTOM).
     * Read by ContextDialStrip to show the current state.
     */
    bypassMode: "OFF" | "ON" | "CUSTOM";
    /**
     * Update the active tab's context mode.
     * Called by ContextDialStrip when the user clicks the ContextDial.
     */
    setBypassMode: (mode: "OFF" | "ON" | "CUSTOM") => void;
    /**
     * Active tab's selective level (0-2).
     * Read by ContextLever to show the current position.
     */
    selectiveLevel: number;
    /**
     * Update the active tab's selective level.
     * Called by ContextLever when the user drags the handle.
     */
    setSelectiveLevel: (level: number) => void;
    /**
     * Whether context gathering is globally enabled (from ContextSettingsState).
     * When false, the ContextDial is greyed out and clicks are rejected.
     */
    /** Maximum simultaneous tabs (from General settings, range 2–20). */
    maxTabs: number;
    globalContextEnabled: boolean;
    /**
     * Whether summarisation is globally enabled (from SummaryConfigService).
     * When false, per-tab summary toggles are hidden/disabled.
     * Summary is independent from context gathering.
     */
    globalSummaryEnabled: boolean;
    /**
     * Default dial position for new tabs from the backend settings.
     * Applied to newly created tabs from defaultBypassMode in ContextSettingsEvent.
     */
    defaultBypassMode: "OFF" | "ON" | "CUSTOM";

    // ── Force Context ────────────────────────────────────────────
    /** Current force context scope for the active tab. */
    forceContextScope: "method" | "class" | null;
    /** Set the force context scope (cycles: null → method → class → null). */
    setForceContextScope: (scope: "method" | "class" | null) => void;
    /** Ghost badge data from RESOLVE_FORCE_CONTEXT_RESULT. Null = no ghost badge. */
    ghostBadge: { elementName: string; elementScope: string; estimatedTokens: number } | null;
    /** Navigate to a source file/element in the IDE editor. */
    navigateToSource: (filePath: string | null, elementSignature: string | null) => void;
}

// ═══════════════════════════════════════════════════════════════════════
//  HELPERS
// ═══════════════════════════════════════════════════════════════════════

const TAB_TITLE_MAX_LENGTH = 24;

function generateTabId(): string {
    return `tab-${Date.now()}-${Math.random().toString(36).slice(2, 6)}`;
}

/**
 * Create a tab with sensible defaults. Used for both new empty tabs
 * and loading existing conversations.
 *
 * - New tab: createTab() → generates fresh tabId + conversationId
 * - Load conversation: createTab({ conversationId, title, messages, ... })
 *
 * conversationId is ALWAYS set — new tabs generate a UUID, loaded
 * conversations provide their existing one. This guarantees every
 * exchange written to JSONL has a non-null conversationId.
 *
 * providerId defaults to null (use global selection) for new tabs.
 * Restored tabs pass their persisted providerId from TabStateDto.
 */
function createTab(data?: Partial<TabData>): TabData {
    const conversationId = data?.conversationId ?? crypto.randomUUID();
    const tab: TabData = {
        id: data?.id ?? generateTabId(),
        title: data?.title ?? "New Chat",
        conversationId,
        messages: data?.messages ?? [],
        isThinking: data?.isThinking ?? false,
        metricsState: data?.metricsState ?? {
            lastExchange: null,
            session: createAccumulator(),
        },
        scrollPosition: data?.scrollPosition ?? 0,
        historyLoaded: data?.historyLoaded ?? true,
        providerId: data?.providerId ?? null,
        collapsedIds: data?.collapsedIds ?? new Set(),
        // Per-tab summary toggle: true = summaries enabled (when global allows). Simple ON/OFF.
        summaryEnabled: data?.summaryEnabled ?? true,
        // User perspective: "ON" = context on (default). Translated to backend by dialToBackendBypass().
        bypassMode: data?.bypassMode ?? "ON",
        selectiveLevel: data?.selectiveLevel ?? 2,
        forceContextScope: null,
    };

    log.info("useBridge", "createTab", {
        tabId: tab.id,
        conversationId,
        source: data?.conversationId ? "loaded" : "generated",
        messages: tab.messages.length,
        providerId: tab.providerId ?? "global",
    });

    return tab;
}

function titleFromMessage(text: string): string {
    const trimmed = text.trim().replace(/\n/g, " ");
    if (trimmed.length <= TAB_TITLE_MAX_LENGTH) return trimmed;
    return trimmed.slice(0, TAB_TITLE_MAX_LENGTH - 1) + "…";
}

/**
 * Translate per-tab context mode (user perspective) to the backend bypass
 * perspective expected by ContextAssembler.assemble().
 *
 * User perspective:          "ON" = context on, "OFF" = no context, "CUSTOM" = lever
 * Backend bypass perspective: null = context runs, "FULL" = full bypass (no context)
 *
 * Translation:
 *   "ON"     → null        (no bypass — context runs with defaults)
 *   "OFF"    → "FULL"      (full bypass — no context)
 *   "CUSTOM" → "SELECTIVE" (per-component bypass — lever controls reach)
 */
function dialToBackendBypass(mode: "OFF" | "ON" | "CUSTOM"): string | null {
    switch (mode) {
        case "ON":     return null;
        case "OFF":    return "FULL";
        case "CUSTOM": return "SELECTIVE";
    }
}

// ═══════════════════════════════════════════════════════════════════════
//  HOOK
// ═══════════════════════════════════════════════════════════════════════

export function useBridge(): BridgeState {
    // ── Per-Tab State ────────────────────────────────────────────────

    const [tabMap, setTabMap] = useState<Map<string, TabData>>(() => {
        const initial = createTab();
        return new Map([[initial.id, initial]]);
    });

    const [tabOrder, setTabOrder] = useState<string[]>(() =>
        Array.from(tabMap.keys()),
    );

    const [activeTabId, setActiveTabId] = useState<string>(() =>
        Array.from(tabMap.keys())[0] ?? "",
    );

    const [isScrolledUp, setIsScrolledUp] = useState(false);

    // ── Global State ─────────────────────────────────────────────────

    const [providers, setProviders] = useState<ProviderInfoDto[]>([]);
    const [selectedProviderId, setSelectedProviderId] = useState<string | null>(null);

    // Block 5: Context & summary settings — received from backend via CONTEXT_SETTINGS event.
    // globalContextEnabled: context kill-switch. When false, ContextDial is greyed out.
    // globalSummaryEnabled: summary kill-switch. When false, per-tab summary toggle is hidden.
    // defaultBypassMode: dial position applied to newly created tabs.
    // Context and summary are INDEPENDENT features with INDEPENDENT toggles.
    const [globalContextEnabled, setGlobalContextEnabled] = useState<boolean>(true);
    const [globalSummaryEnabled, setGlobalSummaryEnabled] = useState<boolean>(true);
    const [defaultBypassMode, setDefaultBypassMode] = useState<"OFF" | "ON" | "CUSTOM">("ON");
    // Max tabs — synced from backend via TAB_STATE event; fallback to DEFAULT_MAX_TABS.
    const [maxTabs, setMaxTabs] = useState<number>(DEFAULT_MAX_TABS);

    const idCounter = useRef(0);
    const tabStateInitialized = useRef(false);
    const saveTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

    /**
     * Ref that always holds the latest activeTabId.
     * Event handlers close over this ref instead of the state value,
     * so they always route to the correct tab even if activeTabId
     * changed between when the event was queued and when it fires.
     */
    const activeTabIdRef = useRef(activeTabId);
    activeTabIdRef.current = activeTabId;

    // Ref for maxTabs — event handlers inside useEffect close over this
    // so they always see the latest value from TAB_STATE.
    const maxTabsRef = useRef(maxTabs);
    maxTabsRef.current = maxTabs;

    const nextId = useCallback(() => {
        idCounter.current += 1;
        return `msg-${Date.now()}-${idCounter.current}`;
    }, []);

    // ── Derived State ────────────────────────────────────────────────

    const activeTab = tabMap.get(activeTabId);
    const activeMessages = activeTab?.messages ?? [];
    const activeIsThinking = activeTab?.isThinking ?? false;
    const activeMetricsState: TabMetricsState = activeTab?.metricsState ?? {
        lastExchange: null,
        session: createAccumulator(),
    };
    const activeScrollPosition = activeTab?.scrollPosition ?? 0;
    const activeCollapsedIds = activeTab?.collapsedIds ?? new Set<string>();
    const activeSummaryEnabled = activeTab?.summaryEnabled ?? true;
    const activeBypassMode = activeTab?.bypassMode ?? "ON";
    const activeSelectiveLevel = activeTab?.selectiveLevel ?? 2;
    const activeForceContextScope = activeTab?.forceContextScope ?? null;

    const tabs= tabOrder
        .map((id) => {
            const tab = tabMap.get(id);
            if (!tab) return null;
            return {
                id: tab.id,
                title: tab.title,
                isActive: tab.id === activeTabId,
                hasMessages: tab.messages.length > 0,
                isThinking: tab.isThinking,
                // Context fill percentage for TabBar indicator chip.
                // Uses the last exchange's token count and context window.
                // Null if no data → chip is hidden.
                contextUsagePct: contextFillPercent(
                    tab.metricsState.lastExchange?.totalTokens ?? null,
                    tab.metricsState.lastExchange?.contextWindowSize ?? null
                ),
                providerId: tab.providerId,
            };
        })
        .filter((t): t is TabInfo => t !== null);

    // ── Tab Persistence (debounced) ──────────────────────────────────

    const persistTabState = useCallback(() => {
        if (saveTimerRef.current) clearTimeout(saveTimerRef.current);

        saveTimerRef.current = setTimeout(() => {
            // Read tabMap via closure — tabOrder and activeTabId are stable
            // within the debounce window. tabMap is captured at call time.
            setTabMap((prev) => {
                const tabDtos: TabStateDto[] = tabOrder.map((id, index) => {
                    const tab = prev.get(id);
                    return {
                        id,
                        conversationId: tab?.conversationId ?? null,
                        title: tab?.title ?? "New Chat",
                        tabOrder: index,
                        isActive: id === activeTabId,
                        scrollPosition: tab?.scrollPosition ?? 0,
                        providerId: tab?.providerId ?? null,
                        bypassMode: tab?.bypassMode ?? "FULL",
                        selectiveLevel: tab?.selectiveLevel ?? 2,
                    };
                });

                sendCommand({
                    type: CommandType.SAVE_TAB_STATE,
                    tabs: tabDtos,
                    activeTabId,
                });

                return prev; // No state change — just reading
            });
        }, 500);
    }, [tabOrder, activeTabId]);

    // ── Helper: Update a specific tab ────────────────────────────────

    const updateTab = useCallback(
        (tabId: string, updater: (tab: TabData) => TabData) => {
            setTabMap((prev) => {
                const existing = prev.get(tabId);
                if (!existing) return prev;
                const next = new Map(prev);
                next.set(tabId, updater(existing));
                return next;
            });
        },
        [],
    );

    // ── Event Subscriptions ──────────────────────────────────────────

    useEffect(() => {
        log.info("useBridge", "useEffect registered — build 20260316a");
        const unsubscribers: Array<() => void> = [];

        // CHAT_RESULT → active tab
        unsubscribers.push(
            onEvent(EventType.CHAT_RESULT, (event: BridgeEvent) => {
                const e = event as ChatResultEvent;
                const targetId = activeTabIdRef.current;

                setTabMap((prev) => {
                    const tab = prev.get(targetId);
                    if (!tab) return prev;

                    // Always trust the backend's conversationId — it is the authoritative
                    // SQLite record ID. The frontend generates a temporary UUID when the
                    // tab is first created, but the backend assigns the real ID on the
                    // first CHAT_RESULT. Syncing here ensures RENAME_TAB and other
                    // commands target the correct DB record.
                    if (e.conversationId && tab.conversationId && e.conversationId !== tab.conversationId) {
                        log.info("useBridge", "syncing conversationId from backend", {
                            frontend: tab.conversationId,
                            backend: e.conversationId,
                            tabId: targetId,
                        });
                    }

                    const newMsg: ChatMessage = {
                        id: `msg-${Date.now()}-${++idCounter.current}`,
                        role: "assistant",
                        content: e.displayText,
                        timestamp: new Date().toISOString(),
                        isError: e.isError,
                        exchangeId: e.exchangeId,
                        correctionAvailable: e.correctionAvailable,
                        isStarred: false,
                        // Block 5C: capture context metadata from backend
                        contextSummary: e.contextSummary ?? null,
                        contextTimeMs: e.contextTimeMs ?? null,
                    };

                    const next = new Map(prev);
                    next.set(targetId, {
                        ...tab,
                        messages: [...tab.messages, newMsg],
                        isThinking: false,
                        // Backend's conversationId is authoritative — use it when present.
                        conversationId: e.conversationId ?? tab.conversationId,
                    });
                    return next;
                });
            }),
        );

        // SHOW_THINKING → active tab
        unsubscribers.push(
            onEvent(EventType.SHOW_THINKING, () => {
                const targetId = activeTabIdRef.current;
                updateTab(targetId, (tab) => ({ ...tab, isThinking: true }));
            }),
        );

        // HIDE_THINKING → active tab
        unsubscribers.push(
            onEvent(EventType.HIDE_THINKING, () => {
                const targetId = activeTabIdRef.current;
                updateTab(targetId, (tab) => ({ ...tab, isThinking: false }));
            }),
        );

        // UPDATE_METRICS → active tab (snapshot + accumulate)
        unsubscribers.push(
            onEvent(EventType.UPDATE_METRICS, (event: BridgeEvent) => {
                const e = event as UpdateMetricsEvent;
                const targetId = activeTabIdRef.current;

                updateTab(targetId, (tab) => {
                    // Build snapshot from the enhanced bridge event.
                    // All fields now come from the Kotlin MetricsService
                    // via the enhanced UpdateMetricsEvent.
                    const snapshot: MetricsSnapshot = {
                        model: e.model,
                        promptTokens: e.promptTokens,
                        completionTokens: e.completionTokens,
                        totalTokens: e.totalTokens,
                        contextWindowSize: e.contextWindowSize ?? null,
                        responseTimeMs: e.responseTimeMs ?? null,
                    };

                    return {
                        ...tab,
                        metricsState: {
                            lastExchange: snapshot,
                            session: accumulate(tab.metricsState.session, snapshot),
                        },
                    };
                });
            }),
        );

        // SYSTEM_MESSAGE → active tab
        unsubscribers.push(
            onEvent(EventType.SYSTEM_MESSAGE, (event: BridgeEvent) => {
                const e = event as SystemMessageEvent;
                const targetId = activeTabIdRef.current;

                setTabMap((prev) => {
                    const tab = prev.get(targetId);
                    if (!tab) return prev;

                    const newMsg: ChatMessage = {
                        id: `msg-${Date.now()}-${++idCounter.current}`,
                        role: "system",
                        content: e.content,
                        timestamp: new Date().toISOString(),
                        isError: e.level === "ERROR",
                        exchangeId: null,
                        correctionAvailable: false,
                        isStarred: false,
                        contextSummary: null,  // system messages carry no context
                        contextTimeMs: null,
                    };

                    const next = new Map(prev);
                    next.set(targetId, {
                        ...tab,
                        messages: [...tab.messages, newMsg],
                    });
                    return next;
                });
            }),
        );

        // PROVIDERS_LIST — global, not per-tab
        unsubscribers.push(
            onEvent(EventType.PROVIDERS_LIST, (event: BridgeEvent) => {
                const e = event as ProvidersListEvent;
                setProviders(e.providers);
                setSelectedProviderId(e.selectedId);
            }),
        );

        // CONVERSATION_CLEARED → active tab
        unsubscribers.push(
            onEvent(EventType.CONVERSATION_CLEARED, () => {
                const targetId = activeTabIdRef.current;
                updateTab(targetId, (tab) => ({
                    ...tab,
                    messages: [],
                    isThinking: false,
                    metricsState: {
                        lastExchange: null,
                        session: createAccumulator(),
                    },
                }));
            }),
        );

        // BRIDGE_READY — request providers, tab state, and context settings
        unsubscribers.push(
            onEvent(EventType.BRIDGE_READY, () => {
                sendCommand({ type: CommandType.REQUEST_PROVIDERS });
                sendCommand({ type: CommandType.REQUEST_TAB_STATE });
                sendCommand({ type: CommandType.REQUEST_CONTEXT_SETTINGS });
            }),
        );

        // TAB_STATE — restore saved tabs on startup
        unsubscribers.push(
            onEvent(EventType.TAB_STATE, (event: BridgeEvent) => {
                const e = event as TabStateEvent;

                // Always sync max tabs — settings can change at any time via
                // General Settings → Apply, which re-emits TAB_STATE.
                setMaxTabs(e.maxTabs ?? DEFAULT_MAX_TABS);

                // Tab restoration only runs once on startup — subsequent
                // TAB_STATE events are settings-only refreshes.
                if (tabStateInitialized.current) return;
                tabStateInitialized.current = true;

                if (e.tabs.length === 0) return;

                const newMap = new Map<string, TabData>();
                const newOrder: string[] = [];
                let newActiveId = "";

                const sorted = [...e.tabs].sort((a, b) => a.tabOrder - b.tabOrder);
                for (const dto of sorted) {
                    // Validate restored bypassMode (guard against unexpected DB values).
                    // Backend stores the old "FULL"/"OFF"/"SELECTIVE" values — translate to new names.
                    const backendToFrontend: Record<string, "OFF" | "ON" | "CUSTOM"> = {
                        "FULL": "ON", "OFF": "OFF", "SELECTIVE": "CUSTOM",
                        "ON": "ON", "CUSTOM": "CUSTOM",  // also accept new names
                    };
                    const restoredBypassMode: "OFF" | "ON" | "CUSTOM" =
                        backendToFrontend[dto.bypassMode ?? "ON"] ?? "ON";

                    newMap.set(dto.id, {
                        id: dto.id,
                        title: dto.title,
                        conversationId: dto.conversationId,
                        messages: [],
                        isThinking: false,
                        metricsState: {
                            lastExchange: null,
                            session: createAccumulator(),
                        },
                        scrollPosition: dto.scrollPosition,
                        historyLoaded: dto.conversationId === null,
                        providerId: dto.providerId ?? null,
                        collapsedIds: new Set(),
                        bypassMode: restoredBypassMode,
                        selectiveLevel: dto.selectiveLevel ?? 2,
                        summaryEnabled: dto.summaryEnabled ?? true, // default ON for restored tabs
                        forceContextScope: null,
                    });
                    newOrder.push(dto.id);
                    if (dto.isActive) newActiveId = dto.id;
                }

                if (!newActiveId && newOrder.length > 0) newActiveId = newOrder[0];

                setTabMap(newMap);
                setTabOrder(newOrder);
                setActiveTabId(newActiveId);

                const activeData = newMap.get(newActiveId);
                if (activeData && !activeData.historyLoaded && activeData.conversationId) {
                    sendCommand({
                        type: CommandType.LOAD_CONVERSATION,
                        conversationId: activeData.conversationId,
                        tabId: newActiveId,
                    });
                }
            }),
        );

        // OPEN_CONVERSATION_RESULT — Library "Continue chat"
        //
        // Debounce guard: prevents double-processing when the backend
        // sends the same event twice in quick succession (e.g. double-click).
        // Uses a short time window (500ms) rather than a permanent Set,
        // so re-opening the same conversation later always works.
        const recentlyOpenedConversations = new Map<string, number>();
        unsubscribers.push(
            onEvent(EventType.OPEN_CONVERSATION_RESULT, (event: BridgeEvent) => {
                const e = event as OpenConversationResultEvent;

                log.info("useBridge", "OPEN_CONVERSATION_RESULT", { conversationId: e.conversationId, tabId: e.tabId });

                // Debounce: ignore if we processed this conversationId within the last 500ms
                const now = Date.now();
                const lastOpened = recentlyOpenedConversations.get(e.conversationId);
                if (lastOpened && now - lastOpened < 500) {
                    log.info("useBridge", "Ignoring duplicate OPEN_CONVERSATION_RESULT (within 500ms)", { conversationId: e.conversationId });
                    return;
                }
                recentlyOpenedConversations.set(e.conversationId, now);

                // Atomic check-and-mutate: all decisions inside the updater
                // so we always work with the latest state.
                setTabMap((prev) => {
                    // Check if a tab with this conversationId already exists
                    for (const [id] of prev) {
                        const tab = prev.get(id);
                        if (tab && tab.conversationId === e.conversationId) {
                            log.info("useBridge", "OPEN_CONVERSATION_RESULT: existing tab found", { tabId: id });
                            setActiveTabId(id);
                            setIsScrolledUp(false);
                            return prev; // No tabMap change — just switch
                        }
                    }

                    // No existing tab — check max tabs cap before creating
                    if (prev.size >= maxTabsRef.current) {
                        log.warn("useBridge", "OPEN_CONVERSATION_RESULT blocked: tab limit reached", {
                            maxTabs: maxTabsRef.current,
                            currentTabs: prev.size,
                            conversationId: e.conversationId,
                        });
                        // Inject a system-level message into the active tab to notify the user
                        const activeId = activeTabIdRef.current;
                        const activeTab = prev.get(activeId);
                        if (activeTab) {
                            const next = new Map(prev);
                            next.set(activeId, {
                                ...activeTab,
                                messages: [
                                    ...activeTab.messages,
                                    {
                                        id: `msg-${Date.now()}-${++idCounter.current}`,
                                        role: "system" as const,
                                        content: `Tab limit reached (${prev.size}/${maxTabsRef.current}). Close a tab to open this conversation.`,
                                        timestamp: new Date().toISOString(),
                                        isError: true,
                                        exchangeId: null,
                                        correctionAvailable: false,
                                        isStarred: false,
                                        contextSummary: null,
                                        contextTimeMs: null,
                                    },
                                ],
                            });
                            return next;
                        }
                        return prev; // Fallback — no active tab to show message in
                    }

                    // Tab cap OK — create the new tab
                    log.info("useBridge", "OPEN_CONVERSATION_RESULT: creating new tab", { tabId: e.tabId });
                    const newTab = createTab({
                        id: e.tabId,
                        conversationId: e.conversationId,
                        title: (e as any).title ?? "New Chat",
                        historyLoaded: false,
                    });

                    const next = new Map(prev);
                    next.set(e.tabId, newTab);

                    setTabOrder((prevOrder) => [...prevOrder, e.tabId]);
                    setActiveTabId(e.tabId);
                    setIsScrolledUp(false);

                    return next;
                });
            }),
        );

        // CONVERSATION_HISTORY — populate a restored tab
        unsubscribers.push(
            onEvent(EventType.CONVERSATION_HISTORY, (event: BridgeEvent) => {
                const e = event as ConversationHistoryEvent;

                setTabMap((prev) => {
                    const tab = prev.get(e.tabId);
                    if (!tab) return prev;

                    const messages: ChatMessage[] = e.messages.map((msg, idx) => ({
                        id: `hist-${e.tabId}-${idx}`,
                        role: msg.role,
                        content: msg.content,
                        timestamp: msg.timestamp,
                        isError: false,
                        exchangeId: msg.exchangeId,
                        correctionAvailable: false,
                        isStarred: msg.isStarred,
                        // Historical messages don't carry context metadata
                        // (HistoryMessageDto doesn't include it). Phase 2: add
                        // context info to HistoryMessageDto if historical context
                        // display is needed.
                        contextSummary: null,
                        contextTimeMs: null,
                    }));

                    // Seed the session accumulator from historical token data.
                    // Each assistant message in the history carries its token usage
                    // (from chat_exchanges in SQLite). We accumulate them the same
                    // way live UPDATE_METRICS events are accumulated — same function,
                    // same data shape. lastExchange stays null until the user sends
                    // a new message in this tab.
                    let seededSession = tab.metricsState.session;
                    for (const msg of e.messages) {
                        if (msg.totalTokens !== null || msg.promptTokens !== null) {
                            seededSession = accumulate(seededSession, {
                                model: msg.model ?? null,
                                promptTokens: msg.promptTokens ?? null,
                                completionTokens: msg.completionTokens ?? null,
                                totalTokens: msg.totalTokens ?? null,
                                contextWindowSize: null,
                                responseTimeMs: null,
                            });
                        }
                    }

                    const next = new Map(prev);
                    next.set(e.tabId, {
                        ...tab,
                        messages,
                        historyLoaded: true,
                        metricsState: {
                            lastExchange: null,
                            session: seededSession,
                        },
                    });
                    return next;
                });
            }),
        );

        // STAR_UPDATED — update star state across all tabs
        unsubscribers.push(
            onEvent(EventType.STAR_UPDATED, (event: BridgeEvent) => {
                const e = event as StarUpdatedEvent;

                setTabMap((prev) => {
                    const next = new Map(prev);
                    let changed = false;

                    for (const [tabId, tab] of prev) {
                        const updated = tab.messages.map((msg) => {
                            if (msg.exchangeId === e.exchangeId) {
                                changed = true;
                                return { ...msg, isStarred: e.isStarred };
                            }
                            return msg;
                        });
                        if (changed) next.set(tabId, { ...tab, messages: updated });
                    }

                    return changed ? next : prev;
                });
            }),
        );

        // R5: BOOKMARK_RESULT
        unsubscribers.push(
            onEvent(EventType.BOOKMARK_RESULT, (event: BridgeEvent) => {
                const e = event as BookmarkResultEvent;
                if (e.success) {
                    log.info("useBridge", "Bookmarked exchange", { exchangeId: e.exchangeId });
                } else {
                    log.warn("useBridge", "Bookmark failed", { exchangeId: e.exchangeId, error: e.error });
                }
            }),
        );

        // Block 5: CONTEXT_SETTINGS — apply project-level context AND summary settings.
        // Context and summary are INDEPENDENT features. This event carries both.
        unsubscribers.push(
            onEvent(EventType.CONTEXT_SETTINGS, (event: BridgeEvent) => {
                const e = event as ContextSettingsEvent;
                log.info("useBridge", "CONTEXT_SETTINGS received", {
                    contextEnabled: e.contextEnabled,
                    summaryEnabled: e.summaryEnabled,
                    defaultBypassMode: e.defaultBypassMode,
                });

                // Apply global context kill-switch
                setGlobalContextEnabled(e.contextEnabled);

                // Apply global summary kill-switch (independent from context)
                setGlobalSummaryEnabled(e.summaryEnabled ?? true);

                // Translate backend bypass mode to user-facing mode name.
                // Backend sends "FULL" (context on) or "OFF" or "SELECTIVE" — translate to ON/OFF/CUSTOM.
                const backendToFrontend: Record<string, "OFF" | "ON" | "CUSTOM"> = {
                    "FULL": "ON", "OFF": "OFF", "SELECTIVE": "CUSTOM",
                    "ON": "ON", "CUSTOM": "CUSTOM",  // also accept new names
                };
                const mode = backendToFrontend[e.defaultBypassMode] ?? "ON";
                setDefaultBypassMode(mode);
            }),
        );

        // RESOLVE_FORCE_CONTEXT_RESULT → update ghost badge state
        unsubscribers.push(
            onEvent(EventType.RESOLVE_FORCE_CONTEXT_RESULT, (event: BridgeEvent) => {
                const e = event as import("../bridge/types").ResolveForceContextResultEvent;
                log.info("useBridge", "RESOLVE_FORCE_CONTEXT_RESULT", {
                    alreadyIncluded: e.alreadyIncluded,
                    elementName: e.elementName,
                    elementScope: e.elementScope,
                });

                if (e.alreadyIncluded || !e.elementName) {
                    // Element is already part of automatic context — no ghost badge
                    setGhostBadge(null);
                } else {
                    // Element NOT in automatic context — show ghost badge
                    setGhostBadge({
                        elementName: e.elementName,
                        elementScope: e.elementScope ?? "file",
                        estimatedTokens: e.estimatedTokens ?? 0,
                    });
                }
            }),
        );

        // DEV_OUTPUT → active tab (same as SYSTEM_MESSAGE)
        unsubscribers.push(
            onEvent(EventType.DEV_OUTPUT, (event: BridgeEvent) => {
                const e = event as DevOutputEvent;
                const targetId = activeTabIdRef.current;

                setTabMap((prev) => {
                    const tab = prev.get(targetId);
                    if (!tab) return prev;

                    const newMsg: ChatMessage = {
                        id: `msg-${Date.now()}-${++idCounter.current}`,
                        role: "system",
                        content: e.content,
                        timestamp: new Date().toISOString(),
                        isError: false,
                        exchangeId: null,
                        correctionAvailable: false,
                        isStarred: false,
                        contextSummary: null,  // dev output carries no context
                        contextTimeMs: null,
                    };

                    const next = new Map(prev);
                    next.set(targetId, {
                        ...tab,
                        messages: [...tab.messages, newMsg],
                    });
                    return next;
                });
            }),
        );

        if (!isJcefMode()) {
            sendCommand({ type: CommandType.REQUEST_PROVIDERS });
            sendCommand({ type: CommandType.REQUEST_TAB_STATE });
            sendCommand({ type: CommandType.REQUEST_CONTEXT_SETTINGS });
        }

        return () => {
            for (const unsub of unsubscribers) unsub();
        };
    }, [nextId, updateTab]);

    // ── Command Functions ────────────────────────────────────────────

    /**
     * Send a chat message using the active tab's provider.
     *
     * Reads providerId from within setTabMap so we always get the current
     * tab state without needing tabMap in the dependency array.
     */
    const sendMessage = useCallback(
        (text: string) => {
            // ── Dev command intercept ────────────────────────────
            // /dev-* commands route to DevCommandHandler on the backend,
            // not to the AI provider. No user message bubble, no thinking
            // indicator — output comes back as DEV_OUTPUT events.
            if (text.toLowerCase().startsWith("/dev")) {
                sendCommand({
                    type: CommandType.DEV_COMMAND,
                    text,
                });
                return;
            }

            const targetId = activeTabIdRef.current;
            let convId: string | null = null;
            let tabProviderId: string | null = null;
            let tabBypassMode: string | null = null;
            let tabSelectiveLevel: number | null = null;
            let tabSummaryEnabled: boolean = true;
            let tabForceContextScope: "method" | "class" | null = null;

            setTabMap((prev) => {
                const tab = prev.get(targetId);
                if (!tab) return prev;
                convId = tab.conversationId;
                tabProviderId = tab.providerId;
                // Translate dial-perspective bypassMode to backend bypass perspective.
                // Translate user-facing mode to backend bypass perspective:
                // "ON" → null (no bypass), "OFF" → "FULL" (full bypass), "CUSTOM" → "SELECTIVE".
                tabBypassMode = dialToBackendBypass(tab.bypassMode);
                // Only include selectiveLevel when CUSTOM mode is active (lever controls reach)
                tabSelectiveLevel = tab.bypassMode === "CUSTOM" ? tab.selectiveLevel : null;
                // Per-tab summary toggle (independent from context mode)
                tabSummaryEnabled = tab.summaryEnabled;
                // Per-tab force context scope (resets to null after send)
                tabForceContextScope = tab.forceContextScope;

                const newMsg: ChatMessage = {
                    id: `msg-${Date.now()}-${++idCounter.current}`,
                    role: "user",
                    content: text,
                    timestamp: new Date().toISOString(),
                    isError: false,
                    exchangeId: null,
                    correctionAvailable: false,
                    isStarred: false,
                    contextSummary: null,  // user messages don't carry context
                    contextTimeMs: null,
                };

                const title = tab.messages.length === 0 ? titleFromMessage(text) : tab.title;

                const next = new Map(prev);
                // Reset forceContextScope after send — it's per-request, not persistent
                next.set(targetId, { ...tab, messages: [...tab.messages, newMsg], title, forceContextScope: null });
                return next;
            });

            log.info("useBridge", "sendMessage", {
                conversationId: convId,
                tabId: targetId,
                providerId: tabProviderId ?? "global",
                bypassMode: tabBypassMode ?? "null (full context)",
                selectiveLevel: tabSelectiveLevel,
            });

            sendCommand({
                type: CommandType.SEND_MESSAGE,
                text,
                conversationId: convId,
                providerId: tabProviderId,
                bypassMode: tabBypassMode,
                selectiveLevel: tabSelectiveLevel,
                summaryEnabled: tabSummaryEnabled,
                forceContextScope: tabForceContextScope,
            });
            persistTabState();
        },
        [persistTabState],
    );

    /**
     * Switch the global chat provider selection.
     *
     * Updates AiProfilesState.selectedChatProfileId on the backend —
     * the fallback provider for tabs with no per-tab provider set.
     * Does NOT affect per-tab provider selections.
     */
    const switchProvider = useCallback((providerId: string) => {
        setSelectedProviderId(providerId);
        sendCommand({ type: CommandType.SWITCH_PROVIDER, providerId });
    }, []);

    const clearChat = useCallback(() => {
        sendCommand({ type: CommandType.CLEAR_CHAT });
    }, []);

    /**
     * Create a new conversation tab.
     *
     * Enforces the maxTabs cap (synced from General settings via TAB_STATE).
     * Logs a warning and returns early if the limit is reached. The TabBar
     * "+" button is disabled at the same threshold, so this is a safety net
     * for programmatic calls.
     */
    const newConversation = useCallback(() => {
        if (tabOrder.length >= maxTabs) {
            log.warn("useBridge", "newConversation blocked: max tabs reached", {
                maxTabs,
                currentTabs: tabOrder.length,
            });
            return;
        }

        const newTab = createTab();

        setTabMap((prev) => {
            const next = new Map(prev);
            next.set(newTab.id, newTab);
            return next;
        });
        setTabOrder((prev) => [...prev, newTab.id]);
        setActiveTabId(newTab.id);
        setIsScrolledUp(false);

        sendCommand({ type: CommandType.NEW_CONVERSATION });
        persistTabState();
    }, [tabOrder.length, maxTabs, persistTabState]);

    const switchTab = useCallback(
        (tabId: string) => {
            if (tabId === activeTabIdRef.current) return;

            setActiveTabId(tabId);
            setIsScrolledUp(false);

            setTabMap((prev) => {
                const tab = prev.get(tabId);
                if (tab && !tab.historyLoaded && tab.conversationId) {
                    sendCommand({
                        type: CommandType.LOAD_CONVERSATION,
                        conversationId: tab.conversationId,
                        tabId,
                    });
                }
                return prev;
            });

            sendCommand({ type: CommandType.SWITCH_TAB, tabId });
            persistTabState();
        },
        [persistTabState],
    );

    const closeTab = useCallback(
        (tabId: string) => {
            setTabOrder((prevOrder) => {
                const idx = prevOrder.indexOf(tabId);
                if (idx === -1) return prevOrder;

                const newOrder = prevOrder.filter((id) => id !== tabId);

                if (newOrder.length === 0) {
                    const freshTab = createTab();
                    setTabMap((prev) => {
                        const next = new Map(prev);
                        next.delete(tabId);
                        next.set(freshTab.id, freshTab);
                        return next;
                    });
                    setActiveTabId(freshTab.id);
                    setIsScrolledUp(false);
                    sendCommand({ type: CommandType.NEW_CONVERSATION });
                    return [freshTab.id];
                }

                setTabMap((prev) => {
                    const next = new Map(prev);
                    next.delete(tabId);
                    return next;
                });

                if (tabId === activeTabIdRef.current) {
                    const newActiveIdx = Math.min(idx, newOrder.length - 1);
                    const newActiveId = newOrder[newActiveIdx];
                    setActiveTabId(newActiveId);
                    setIsScrolledUp(false);
                    sendCommand({ type: CommandType.SWITCH_TAB, tabId: newActiveId });
                }

                return newOrder;
            });

            sendCommand({ type: CommandType.CLOSE_TAB, tabId });
            persistTabState();
        },
        [persistTabState],
    );

    /**
     * Rename a tab title.
     *
     * Sends RENAME_TAB to the backend so the title is written to
     * conversations.title in SQLite (making the rename durable in the Library).
     * Also updates local state immediately and persists via SAVE_TAB_STATE.
     *
     * conversationId may be null for fresh tabs that have never sent a message;
     * the backend skips the DB update in that case.
     */
    const renameTab = useCallback(
        (tabId: string, newTitle: string) => {
            const trimmed = newTitle.trim();
            if (!trimmed) return;

            const conversationId = tabMap.get(tabId)?.conversationId ?? null;
            sendCommand({
                type: CommandType.RENAME_TAB,
                tabId,
                conversationId,
                title: trimmed,
            });

            updateTab(tabId, (tab) => ({ ...tab, title: trimmed }));
            persistTabState();
        },
        [tabMap, updateTab, persistTabState, sendCommand],
    );

    /**
     * Switch the AI provider for a specific tab.
     *
     * Updates local tab state immediately so the UI reflects the change
     * without waiting for a round-trip. Sends SWITCH_TAB_PROVIDER to
     * the backend for immediate DB persistence (open_tabs.provider_id),
     * independent of the SAVE_TAB_STATE cycle.
     *
     * providerId null = revert to global provider selection.
     */
    const switchTabProvider = useCallback(
        (tabId: string, providerId: string | null) => {
            updateTab(tabId, (tab) => ({ ...tab, providerId }));

            if (providerId !== null) {
                // Only send SWITCH_TAB_PROVIDER when setting a specific provider.
                // Null (revert to global) is handled naturally by the next
                // SAVE_TAB_STATE which writes null to open_tabs.provider_id.
                sendCommand({
                    type: CommandType.SWITCH_TAB_PROVIDER,
                    tabId,
                    providerId,
                });
            }

            log.info("useBridge", "switchTabProvider", {
                tabId,
                providerId: providerId ?? "global (reverted)",
            });

            persistTabState();
        },
        [updateTab, persistTabState],
    );

    const toggleStar = useCallback((exchangeId: string) => {
        sendCommand({ type: CommandType.TOGGLE_STAR, exchangeId });
    }, []);

    const bookmarkCodeBlock = useCallback((exchangeId: string, blockIndex: number) => {
        sendCommand({ type: CommandType.BOOKMARK_CODE_BLOCK, exchangeId, blockIndex });
    }, []);

    const confirmCorrection = useCallback(() => {
        sendCommand({ type: CommandType.CONFIRM_CORRECTION });
    }, []);

    const requestCorrection = useCallback(() => {
        sendCommand({ type: CommandType.REQUEST_CORRECTION });
    }, []);

    const requestProviders = useCallback(() => {
        sendCommand({ type: CommandType.REQUEST_PROVIDERS });
    }, []);

    const saveScrollPosition = useCallback(
        (position: number) => {
            updateTab(activeTabIdRef.current, (tab) => ({
                ...tab,
                scrollPosition: position,
            }));
        },
        [updateTab],
    );

    // ── Collapse/Expand ──────────────────────────────────────────

    const toggleCollapse = useCallback(
        (messageId: string) => {
            updateTab(activeTabIdRef.current, (tab) => {
                const next = new Set(tab.collapsedIds);
                if (next.has(messageId)) {
                    next.delete(messageId);
                } else {
                    next.add(messageId);
                }
                return { ...tab, collapsedIds: next };
            });
        },
        [updateTab],
    );

    const collapseAll = useCallback(() => {
        updateTab(activeTabIdRef.current, (tab) => {
            const ids = new Set<string>();
            for (const msg of tab.messages) {
                if (msg.role === "assistant") ids.add(msg.id);
            }
            return { ...tab, collapsedIds: ids };
        });
    }, [updateTab]);

    const expandAll = useCallback(() => {
        updateTab(activeTabIdRef.current, (tab) => ({
            ...tab,
            collapsedIds: new Set(),
        }));
    }, [updateTab]);

    /**
     * Update the active tab's context mode (user perspective: OFF/ON/CUSTOM).
     *
     * Called by ContextDialStrip when the user clicks the ContextDial.
     * The new mode is stored in TabData.bypassMode (persisted via SAVE_TAB_STATE)
     * and included in the next SEND_MESSAGE command via dialToBackendBypass().
     *
     * @see ContextDialStrip — calls this on dial click
     * @see dialToBackendBypass — translates to backend perspective in sendMessage
     */
    /**
     * Toggle per-tab summary ON/OFF.
     * Gated by globalSummaryEnabled — if global is OFF, this has no effect.
     */
    const setSummaryEnabled = useCallback((enabled: boolean) => {
        updateTab(activeTabIdRef.current, (tab) => ({
            ...tab,
            summaryEnabled: enabled,
        }));
        persistTabState();
    }, [updateTab, persistTabState]);

    const setBypassMode = useCallback((mode: "OFF" | "ON" | "CUSTOM") => {
        updateTab(activeTabIdRef.current, (tab) => ({
            ...tab,
            bypassMode: mode,
        }));
        persistTabState();
    }, [updateTab, persistTabState]);

    /**
     * Update the active tab's selective level.
     *
     * Called by ContextLever when the user drags the handle to a new snap position.
     * The level is stored in TabData.selectiveLevel (persisted via SAVE_TAB_STATE)
     * and included in the next SEND_MESSAGE when bypassMode = "CUSTOM".
     *
     * @see ContextLever — calls this on handle drag end
     * @see sendMessage — reads selectiveLevel from TabData
     */
    const setSelectiveLevel = useCallback((level: number) => {
        updateTab(activeTabIdRef.current, (tab) => ({
            ...tab,
            selectiveLevel: level,
        }));
        persistTabState();
    }, [updateTab, persistTabState]);

    // ── Force Context ────────────────────────────────────────────────

    /** Ghost badge state from RESOLVE_FORCE_CONTEXT_RESULT */
    const [ghostBadge, setGhostBadge] = useState<{
        elementName: string;
        elementScope: string;
        estimatedTokens: number;
    } | null>(null);

    /**
     * Set the force context scope for the active tab.
     * Cycles: null → "method" → "class" → null.
     * Sends RESOLVE_FORCE_CONTEXT to backend to check if ghost badge is needed.
     * Ghost badge clears when scope resets to null.
     */
    const setForceContextScope = useCallback((scope: "method" | "class" | null) => {
        updateTab(activeTabIdRef.current, (tab) => ({
            ...tab,
            forceContextScope: scope,
        }));

        if (scope === null) {
            // Clear ghost badge when force scope is reset
            setGhostBadge(null);
        } else {
            // Ask backend if this element is already in automatic context
            sendCommand({
                type: CommandType.RESOLVE_FORCE_CONTEXT,
                scope,
            });
        }
    }, [updateTab, sendCommand]);

    // ── Navigate to source ────────────────────────────────────────
    // Sends a command to the backend to open a file and position the
    // cursor at a specific element. Used when clicking badges.
    const navigateToSource = useCallback((filePath: string | null, elementSignature: string | null) => {
        sendCommand({
            type: CommandType.NAVIGATE_TO_SOURCE,
            filePath,
            elementSignature,
        });
    }, [sendCommand]);

    return {
        messages: activeMessages,
        isThinking: activeIsThinking,
        metricsState: activeMetricsState,
        providers,
        selectedProviderId,
        isProduction: isJcefMode(),
        tabs,
        activeTabId,
        isScrolledUp,
        scrollPosition: activeScrollPosition,
        sendMessage,
        switchProvider,
        clearChat,
        newConversation,
        confirmCorrection,
        requestCorrection,
        requestProviders,
        switchTab,
        closeTab,
        toggleStar,
        renameTab,
        switchTabProvider,
        setScrolledUp: setIsScrolledUp,
        saveScrollPosition,
        collapsedIds: activeCollapsedIds,
        toggleCollapse,
        collapseAll,
        expandAll,
        bookmarkCodeBlock,
        summaryEnabled: activeSummaryEnabled,
        setSummaryEnabled,
        bypassMode: activeBypassMode,
        setBypassMode,
        selectiveLevel: activeSelectiveLevel,
        setSelectiveLevel,
        maxTabs,
        globalContextEnabled,
        globalSummaryEnabled,
        defaultBypassMode,
        forceContextScope: activeForceContextScope,
        setForceContextScope,
        ghostBadge,
        navigateToSource,
    };
}