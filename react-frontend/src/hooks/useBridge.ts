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
    type TabStateDto, ProviderInfoDto,
} from "../bridge/types";
import type { TabMetricsState, MetricsSnapshot } from "../metrics";
import { createAccumulator, accumulate } from "../metrics";

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

    // R4: Tab state
    tabs: TabInfo[];
    activeTabId: string;
    isScrolledUp: boolean;

    // R5: Scroll position for restore
    scrollPosition: number;

    // Commands (existing)
    sendMessage: (text: string) => void;
    switchProvider: (providerId: string) => void;
    clearChat: () => void;
    newConversation: () => void;
    confirmCorrection: () => void;
    requestCorrection: () => void;
    requestProviders: () => void;

    // R4: Tab commands
    switchTab: (tabId: string) => void;
    closeTab: (tabId: string) => void;
    toggleStar: (exchangeId: string) => void;
    setScrolledUp: (isUp: boolean) => void;
    saveScrollPosition: (position: number) => void;

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
    };

    console.log(
        `[YMM] createTab: tabId=${tab.id}, conversationId=${conversationId}, ` +
        `source=${data?.conversationId ? "loaded" : "generated"}, ` +
        `messages=${tab.messages.length}, providerId=${tab.providerId ?? "global"}`
    );

    return tab;
}

function titleFromMessage(text: string): string {
    const trimmed = text.trim().replace(/\n/g, " ");
    if (trimmed.length <= TAB_TITLE_MAX_LENGTH) return trimmed;
    return trimmed.slice(0, TAB_TITLE_MAX_LENGTH - 1) + "…";
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
                // PLACEHOLDER: contextUsagePct not yet computed.
                // Will be: (tab.metrics?.totalTokens ?? 0) / modelContextLimit * 100
                // Requires modelContextLimit from provider config.
                contextUsagePct: null as number | null,
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
        console.log("[YMM] useBridge useEffect registered — build 20260305b");
        const unsubscribers: Array<() => void> = [];

        // CHAT_RESULT → active tab
        unsubscribers.push(
            onEvent(EventType.CHAT_RESULT, (event: BridgeEvent) => {
                const e = event as ChatResultEvent;
                const targetId = activeTabIdRef.current;

                setTabMap((prev) => {
                    const tab = prev.get(targetId);
                    if (!tab) return prev;

                    if (e.conversationId && tab.conversationId && e.conversationId !== tab.conversationId) {
                        console.warn(
                            `[YMM] conversationId mismatch! tab=${tab.conversationId}, backend=${e.conversationId}, tabId=${targetId}`
                        );
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
                    };

                    const next = new Map(prev);
                    next.set(targetId, {
                        ...tab,
                        messages: [...tab.messages, newMsg],
                        isThinking: false,
                        conversationId: tab.conversationId ?? e.conversationId,
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
                    // Build snapshot from the bridge event.
                    // contextWindowSize and responseTimeMs are NOT yet sent by
                    // Kotlin — null until the backend is enhanced.
                    const snapshot: MetricsSnapshot = {
                        model: e.model,
                        promptTokens: e.promptTokens,
                        completionTokens: e.completionTokens,
                        totalTokens: e.totalTokens,
                        contextWindowSize: null,  // NOT YET: Kotlin enhancement needed
                        responseTimeMs: null,      // NOT YET: Kotlin enhancement needed
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

        // BRIDGE_READY — request providers and tab state
        unsubscribers.push(
            onEvent(EventType.BRIDGE_READY, () => {
                sendCommand({ type: CommandType.REQUEST_PROVIDERS });
                sendCommand({ type: CommandType.REQUEST_TAB_STATE });
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

                console.log(`[YMM] OPEN_CONVERSATION_RESULT: conversationId=${e.conversationId}, tabId=${e.tabId}`);

                // Debounce: ignore if we processed this conversationId within the last 500ms
                const now = Date.now();
                const lastOpened = recentlyOpenedConversations.get(e.conversationId);
                if (lastOpened && now - lastOpened < 500) {
                    console.log(`[YMM] Ignoring duplicate OPEN_CONVERSATION_RESULT for ${e.conversationId} (within 500ms)`);
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
                            console.log(`[YMM] OPEN_CONVERSATION_RESULT: existing tab found: ${id}`);
                            setActiveTabId(id);
                            setIsScrolledUp(false);
                            return prev; // No tabMap change — just switch
                        }
                    }

                    // No existing tab — create one
                    console.log(`[YMM] OPEN_CONVERSATION_RESULT: creating new tab: ${e.tabId}`);
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
                    console.log(`[YMM] Bookmarked exchange ${e.exchangeId}`);
                } else {
                    console.warn(`[YMM] Bookmark failed for exchange ${e.exchangeId}: ${e.error}`);
                }
            }),
        );

        if (!isJcefMode()) {
            sendCommand({ type: CommandType.REQUEST_PROVIDERS });
            sendCommand({ type: CommandType.REQUEST_TAB_STATE });
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
            const targetId = activeTabIdRef.current;
            let convId: string | null = null;
            let tabProviderId: string | null = null;

            setTabMap((prev) => {
                const tab = prev.get(targetId);
                if (!tab) return prev;
                convId = tab.conversationId;
                tabProviderId = tab.providerId;

                const newMsg: ChatMessage = {
                    id: `msg-${Date.now()}-${++idCounter.current}`,
                    role: "user",
                    content: text,
                    timestamp: new Date().toISOString(),
                    isError: false,
                    exchangeId: null,
                    correctionAvailable: false,
                    isStarred: false,
                };

                const title = tab.messages.length === 0 ? titleFromMessage(text) : tab.title;

                const next = new Map(prev);
                next.set(targetId, { ...tab, messages: [...tab.messages, newMsg], title });
                return next;
            });

            console.log(
                `[YMM] sendMessage: conversationId=${convId}, tabId=${targetId}, ` +
                `providerId=${tabProviderId ?? "global"}`
            );

            sendCommand({
                type: CommandType.SEND_MESSAGE,
                text,
                conversationId: convId,
                providerId: tabProviderId,
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
            console.warn(
                `[YMM] newConversation blocked: already at max tabs (${DEFAULT_MAX_TABS}). ` +
                `Close a tab first. Max configurable in Settings → YMM Assistant → General (not yet implemented).`
            );
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
     * Rename a tab title (updates both local state and persists).
     *
     * PLACEHOLDER: RENAME_TAB backend command not yet implemented.
     * Currently updates local state only — title survives tab switches
     * and IDE restarts (included in TabStateDto via SAVE_TAB_STATE).
     *
     * Full backend wiring needed:
     * 1. Add RENAME_TAB to CommandType in types.ts
     * 2. Handle in BridgeDispatcher.kt → UPDATE conversations SET title = ?
     * 3. Uncomment sendCommand(RENAME_TAB) below
     */
    const renameTab = useCallback(
        (tabId: string, newTitle: string) => {
            const trimmed = newTitle.trim();
            if (!trimmed) return;

            updateTab(tabId, (tab) => ({ ...tab, title: trimmed }));

            // PLACEHOLDER: sendCommand({ type: CommandType.RENAME_TAB, tabId, title: trimmed });
            console.warn(
                `[YMM] renameTab: local title updated to "${trimmed}" for tab ${tabId}. ` +
                `RENAME_TAB backend command not yet implemented — add to CommandType and handle in BridgeDispatcher.kt.`
            );

            persistTabState();
        },
        [updateTab, persistTabState],
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

            console.log(
                `[YMM] switchTabProvider: tabId=${tabId}, ` +
                `providerId=${providerId ?? "global (reverted)"}`
            );

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
        bookmarkCodeBlock,
    };
}