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
 * ## PLACEHOLDER: Max Tabs Config
 *
 * DEFAULT_MAX_TABS is hardcoded to 5 here and in TabBar.tsx.
 * The configurable range (2–20) is NOT YET IMPLEMENTED — it lives in
 * the General settings config page (Tools → YMM Assistant → General).
 * When implemented, the backend should send maxTabs in the TAB_STATE
 * event so the frontend cap stays in sync with the setting.
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
 * PLACEHOLDER: This will come from the General settings config page
 * (Tools → YMM Assistant → General → "Maximum open tabs", range 2–20)
 * once that page is implemented. For now hardcoded here and in TabBar.tsx.
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
     * Context bypass mode for this tab — stored in dial perspective.
     *
     * Dial semantics (user perspective):
     * - "FULL":      Full context gathering (context ON). Default.
     * - "OFF":       No context gathering (context OFF).
     * - "SELECTIVE": Per-component control (Pro tier).
     *
     * Translated to backend bypass perspective by dialToBackendBypass()
     * before inclusion in SEND_MESSAGE:
     *   "FULL" → null (no bypass, context runs)
     *   "OFF"  → "FULL" (full bypass, no context)
     *   "SELECTIVE" → "SELECTIVE"
     *
     * Persisted in open_tabs.bypass_mode via SAVE_TAB_STATE. Default "FULL".
     *
     * @see ContextDialStrip — React component that reads/writes this
     * @see dialToBackendBypass — translates for SEND_MESSAGE
     * @see ContextAssembler.assemble — backend checks bypassMode
     */
    bypassMode: "OFF" | "FULL" | "SELECTIVE";
    /**
     * Lever position when bypassMode = "SELECTIVE".
     * 0 = Minimal (open file only), 1 = Partial (no ProjectStructure), 2 = Full.
     * Persisted in open_tabs.selective_level. Default 2.
     *
     * @see ContextLever — React component that reads/writes this
     * @see ContextAssembler.buildDetectorsForLevel — backend uses this value
     */
    selectiveLevel: number;
}

/** R4: Lightweight tab descriptor for the TabBar component. */
export interface TabInfo {
    id: string;
    title: string;
    isActive: boolean;
    hasMessages: boolean;
    isThinking: boolean;
    /**
     * PLACEHOLDER: Context window usage percentage (0–100).
     *
     * Used by TabBar to render the context indicator chip:
     * - null / < 75% → no chip
     * - >= 75%       → amber chip
     * - >= 90%       → red chip
     *
     * To implement:
     * 1. Add modelContextLimit to MetricsData (from provider config)
     * 2. Compute: (metrics.totalTokens / modelContextLimit) * 100
     * 3. Populate contextUsagePct below in the tabs derived state
     * 4. Remove this PLACEHOLDER comment
     */
    contextUsagePct: number | null; // PLACEHOLDER: always null until implemented
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

    // Block 5: Context bypass + settings
    /**
     * Active tab's context bypass mode (dial perspective).
     * Read by ContextDialStrip to show the current state.
     */
    bypassMode: "OFF" | "FULL" | "SELECTIVE";
    /**
     * Update the active tab's bypass mode.
     * Called by ContextDialStrip when the user clicks the ContextDial.
     */
    setBypassMode: (mode: "OFF" | "FULL" | "SELECTIVE") => void;
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
    globalContextEnabled: boolean;
    /**
     * Default dial position for new tabs from the backend settings.
     * Applied to newly created tabs from defaultBypassMode in ContextSettingsEvent.
     */
    defaultBypassMode: "OFF" | "FULL" | "SELECTIVE";
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
        // Dial perspective: "FULL" = context on (default). Translated to backend by dialToBackendBypass().
        bypassMode: data?.bypassMode ?? "FULL",
        selectiveLevel: data?.selectiveLevel ?? 2,
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
 * Translate a dial-perspective bypass mode to the backend bypass perspective
 * expected by ContextAssembler.assemble().
 *
 * Dial perspective (user):   "FULL" = context on, "OFF" = no context
 * Backend bypass perspective: null = context runs, "FULL" = full bypass (no context)
 *
 * Translation:
 *   "FULL"      → null       (no bypass = context runs)
 *   "OFF"       → "FULL"     (full bypass = no context)
 *   "SELECTIVE" → "SELECTIVE" (per-component)
 */
function dialToBackendBypass(mode: "OFF" | "FULL" | "SELECTIVE"): string | null {
    switch (mode) {
        case "FULL":      return null;
        case "OFF":       return "FULL";
        case "SELECTIVE": return "SELECTIVE";
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

    // Block 5: Context settings — received from backend at startup via CONTEXT_SETTINGS event.
    // globalContextEnabled: master kill-switch. When false, ContextDial is greyed out.
    // defaultBypassMode: dial position applied to newly created tabs.
    const [globalContextEnabled, setGlobalContextEnabled] = useState<boolean>(true);
    const [defaultBypassMode, setDefaultBypassMode] = useState<"OFF" | "FULL" | "SELECTIVE">("FULL");

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
    const activeBypassMode = activeTab?.bypassMode ?? "FULL";
    const activeSelectiveLevel = activeTab?.selectiveLevel ?? 2;

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
                // PLACEHOLDER: contextUsagePct not yet computed// Context fill percentage for TabBar indicator chip.
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
                if (tabStateInitialized.current) return;
                tabStateInitialized.current = true;

                if (e.tabs.length === 0) return;

                const newMap = new Map<string, TabData>();
                const newOrder: string[] = [];
                let newActiveId = "";

                const sorted = [...e.tabs].sort((a, b) => a.tabOrder - b.tabOrder);
                for (const dto of sorted) {
                    // Validate restored bypassMode (guard against unexpected DB values)
                    const validModes: Array<"OFF" | "FULL" | "SELECTIVE"> = ["OFF", "FULL", "SELECTIVE"];
                    const restoredBypassMode: "OFF" | "FULL" | "SELECTIVE" =
                        validModes.includes(dto.bypassMode as "OFF" | "FULL" | "SELECTIVE")
                            ? dto.bypassMode as "OFF" | "FULL" | "SELECTIVE"
                            : "FULL";

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

                    // No existing tab — create one
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

        // Block 5: CONTEXT_SETTINGS — apply project-level context settings
        unsubscribers.push(
            onEvent(EventType.CONTEXT_SETTINGS, (event: BridgeEvent) => {
                const e = event as ContextSettingsEvent;
                log.info("useBridge", "CONTEXT_SETTINGS received", {
                    contextEnabled: e.contextEnabled,
                    defaultBypassMode: e.defaultBypassMode,
                });

                setGlobalContextEnabled(e.contextEnabled);

                // Validate and apply defaultBypassMode (guard against unexpected values)
                const validModes: Array<"OFF" | "FULL" | "SELECTIVE"> = ["OFF", "FULL", "SELECTIVE"];
                const mode = validModes.includes(e.defaultBypassMode as "OFF" | "FULL" | "SELECTIVE")
                    ? e.defaultBypassMode as "OFF" | "FULL" | "SELECTIVE"
                    : "FULL";
                setDefaultBypassMode(mode);
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

            setTabMap((prev) => {
                const tab = prev.get(targetId);
                if (!tab) return prev;
                convId = tab.conversationId;
                tabProviderId = tab.providerId;
                // Translate dial-perspective bypassMode to backend bypass perspective.
                // "FULL" (context on) → null (no bypass), "OFF" → "FULL" (full bypass).
                tabBypassMode = dialToBackendBypass(tab.bypassMode);
                // Only include selectiveLevel when SELECTIVE mode is active
                tabSelectiveLevel = tab.bypassMode === "SELECTIVE" ? tab.selectiveLevel : null;

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
                next.set(targetId, { ...tab, messages: [...tab.messages, newMsg], title });
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
     * Enforces DEFAULT_MAX_TABS cap — logs a warning and returns early
     * if the limit is reached. The TabBar "+" button is disabled at the
     * same threshold, so this is a safety net for programmatic calls.
     *
     * PLACEHOLDER: maxTabs will come from General settings config when
     * that page is implemented. For now DEFAULT_MAX_TABS = 5.
     */
    const newConversation = useCallback(() => {
        if (tabOrder.length >= DEFAULT_MAX_TABS) {
            log.warn("useBridge", "newConversation blocked: max tabs reached", {
                maxTabs: DEFAULT_MAX_TABS,
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
    }, [tabOrder.length, persistTabState]);

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
     * Update the active tab's context bypass mode (dial perspective).
     *
     * Called by ContextDialStrip when the user clicks the ContextDial.
     * The new mode is stored in TabData.bypassMode (persisted via SAVE_TAB_STATE)
     * and included in the next SEND_MESSAGE command via dialToBackendBypass().
     *
     * @see ContextDialStrip — calls this on dial click
     * @see dialToBackendBypass — translates to backend perspective in sendMessage
     */
    const setBypassMode = useCallback((mode: "OFF" | "FULL" | "SELECTIVE") => {
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
     * and included in the next SEND_MESSAGE when bypassMode = "SELECTIVE".
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
        bypassMode: activeBypassMode,
        setBypassMode,
        selectiveLevel: activeSelectiveLevel,
        setSelectiveLevel,
        globalContextEnabled,
        defaultBypassMode,
    };
}