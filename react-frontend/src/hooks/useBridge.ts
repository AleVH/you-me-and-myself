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
    type ProviderInfoDto,
    type TabStateEvent,
    type ConversationHistoryEvent,
    type StarUpdatedEvent,
    type BookmarkResultEvent,
    type OpenConversationResultEvent,
    type TabStateDto,
} from "../bridge/types";

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

/** Token usage metrics for the latest exchange in a tab. */
export interface MetricsData {
    model: string | null;
    promptTokens: number | null;
    completionTokens: number | null;
    totalTokens: number | null;
    estimatedCost: string | null;
}

/**
 * R4: Per-tab conversation state.
 * Each open tab has its own messages, metrics, thinking state, and conversation.
 */
export interface TabData {
    id: string;
    title: string;
    conversationId: string | null;
    messages: ChatMessage[];
    isThinking: boolean;
    metrics: MetricsData | null;
    scrollPosition: number;
    /** False for restored tabs that haven't fetched messages yet. */
    historyLoaded: boolean;
}

/** R4: Lightweight tab descriptor for the TabBar component. */
export interface TabInfo {
    id: string;
    title: string;
    isActive: boolean;
    hasMessages: boolean;
    isThinking: boolean;
}

/**
 * Return type of the useBridge hook.
 * Active tab's state is exposed directly for backward compatibility.
 */
export interface BridgeState {
    // Active tab state
    messages: ChatMessage[];
    isThinking: boolean;
    metrics: MetricsData | null;

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
 */
function createTab(data?: Partial<TabData>): TabData {
    const conversationId = data?.conversationId ?? crypto.randomUUID();
    const tab: TabData = {
        id: data?.id ?? generateTabId(),
        title: data?.title ?? "New Chat",
        conversationId,
        messages: data?.messages ?? [],
        isThinking: data?.isThinking ?? false,
        metrics: data?.metrics ?? null,
        scrollPosition: data?.scrollPosition ?? 0,
        historyLoaded: data?.historyLoaded ?? true,
    };

    console.log(
        `[YMM] createTab: tabId=${tab.id}, conversationId=${conversationId}, ` +
        `source=${data?.conversationId ? "loaded" : "generated"}, ` +
        `messages=${tab.messages.length}`
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
    const activeMetrics = activeTab?.metrics ?? null;
    const activeScrollPosition = activeTab?.scrollPosition ?? 0;

    const tabs: TabInfo[] = tabOrder
        .map((id) => {
            const tab = tabMap.get(id);
            if (!tab) return null;
            return {
                id: tab.id,
                title: tab.title,
                isActive: tab.id === activeTabId,
                hasMessages: tab.messages.length > 0,
                isThinking: tab.isThinking,
            };
        })
        .filter((t): t is TabInfo => t !== null);

    // ── Tab Persistence (debounced) ──────────────────────────────────

    const persistTabState = useCallback(() => {
        if (saveTimerRef.current) clearTimeout(saveTimerRef.current);

        saveTimerRef.current = setTimeout(() => {
            const tabDtos: TabStateDto[] = tabOrder.map((id, index) => {
                const tab = tabMap.get(id);
                return {
                    id,
                    conversationId: tab?.conversationId ?? null,
                    title: tab?.title ?? "New Chat",
                    tabOrder: index,
                    isActive: id === activeTabId,
                    scrollPosition: tab?.scrollPosition ?? 0,
                };
            });

            sendCommand({
                type: CommandType.SAVE_TAB_STATE,
                tabs: tabDtos,
                activeTabId,
            });
        }, 500);
    }, [tabMap, tabOrder, activeTabId]);

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
        console.log("[YMM] useBridge useEffect registered — build 20260304a");
        const unsubscribers: Array<() => void> = [];

        // CHAT_RESULT → active tab
        unsubscribers.push(
            onEvent(EventType.CHAT_RESULT, (event: BridgeEvent) => {
                const e = event as ChatResultEvent;
                const targetId = activeTabIdRef.current;

                setTabMap((prev) => {
                    const tab = prev.get(targetId);
                    if (!tab) return prev;

                    // Sanity check: warn if backend returns a different conversationId
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

        // UPDATE_METRICS → active tab
        unsubscribers.push(
            onEvent(EventType.UPDATE_METRICS, (event: BridgeEvent) => {
                const e = event as UpdateMetricsEvent;
                const targetId = activeTabIdRef.current;
                updateTab(targetId, (tab) => ({
                    ...tab,
                    metrics: {
                        model: e.model,
                        promptTokens: e.promptTokens,
                        completionTokens: e.completionTokens,
                        totalTokens: e.totalTokens,
                        estimatedCost: e.estimatedCost,
                    },
                }));
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
                    metrics: null,
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

                if (e.tabs.length === 0) return; // Keep initial empty tab

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
                        metrics: null,
                        scrollPosition: dto.scrollPosition,
                        historyLoaded: dto.conversationId === null,
                    });
                    newOrder.push(dto.id);
                    if (dto.isActive) newActiveId = dto.id;
                }

                if (!newActiveId && newOrder.length > 0) newActiveId = newOrder[0];

                setTabMap(newMap);
                setTabOrder(newOrder);
                setActiveTabId(newActiveId);

                // Load history for active tab if it has a conversation
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

        // OPEN_CONVERSATION_RESULT — Library "Continue chat" opens/switches to a tab
        // Uses a local Set to debounce rapid duplicate events for the same conversation.
        const openedConversations = new Set<string>();
        unsubscribers.push(
            onEvent(EventType.OPEN_CONVERSATION_RESULT, (event: BridgeEvent) => {
                const e = event as OpenConversationResultEvent;

                console.log(`[YMM] OPEN_CONVERSATION_RESULT: conversationId=${e.conversationId}, tabId=${e.tabId}`);

                // Debounce: ignore duplicate events for same conversation
                if (openedConversations.has(e.conversationId)) {
                    console.log(`[YMM] Ignoring duplicate OPEN_CONVERSATION_RESULT for ${e.conversationId}`);
                    return;
                }
                openedConversations.add(e.conversationId);

                // Check if this conversation is already open in a tab
                let existingTabId: string | null = null;
                setTabMap((prev) => {
                    for (const [id, tab] of prev) {
                        if (tab.conversationId === e.conversationId) {
                            existingTabId = id;
                            console.log(`[YMM] Conversation already open in tab ${id}, switching`);
                            return prev;
                        }
                    }

                    // Create a new tab for this conversation
                    const newTab = createTab({
                        id: e.tabId,
                        conversationId: e.conversationId,
                        title: (e as any).title ?? "New Chat",
                        historyLoaded: false,
                    });

                    console.log(`[YMM] Created tab ${e.tabId} for conversation ${e.conversationId}`);

                    const next = new Map(prev);
                    next.set(e.tabId, newTab);
                    return next;
                });

                if (existingTabId) {
                    setActiveTabId(existingTabId);
                } else {
                    setTabOrder((prev) => [...prev, e.tabId]);
                    setActiveTabId(e.tabId);
                }

                setIsScrolledUp(false);
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

                    const next = new Map(prev);
                    next.set(e.tabId, { ...tab, messages, historyLoaded: true });
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

        // R5: BOOKMARK_RESULT — confirmation from backend (future: update icon state)
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

        // Dev mode: BRIDGE_READY never fires, request immediately
        if (!isJcefMode()) {
            sendCommand({ type: CommandType.REQUEST_PROVIDERS });
            sendCommand({ type: CommandType.REQUEST_TAB_STATE });
        }

        return () => {
            for (const unsub of unsubscribers) unsub();
        };
    }, [nextId, updateTab]);
    // Note: activeTabId deliberately NOT in deps. We use activeTabIdRef
    // so event handlers always get the latest value without re-subscribing.

    // ── Command Functions ────────────────────────────────────────────

    const sendMessage = useCallback(
        (text: string) => {
            const targetId = activeTabIdRef.current;
            let convId: string | null = null;

            setTabMap((prev) => {
                const tab = prev.get(targetId);
                if (!tab) return prev;
                convId = tab.conversationId;

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

            console.log(`[YMM] sendMessage: conversationId=${convId}, tabId=${targetId}`);

            sendCommand({ type: CommandType.SEND_MESSAGE, text, conversationId: convId });
            persistTabState();
        },
        [persistTabState],
    );

    const switchProvider = useCallback((providerId: string) => {
        setSelectedProviderId(providerId);
        sendCommand({ type: CommandType.SWITCH_PROVIDER, providerId });
    }, []);

    const clearChat = useCallback(() => {
        sendCommand({ type: CommandType.CLEAR_CHAT });
    }, []);

    const newConversation = useCallback(() => {
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
    }, [persistTabState]);

    const switchTab = useCallback(
        (tabId: string) => {
            if (tabId === activeTabIdRef.current) return;

            setActiveTabId(tabId);
            setIsScrolledUp(false);

            // Load history if needed
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
                    // Last tab — create fresh empty one
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

    const toggleStar = useCallback((exchangeId: string) => {
        sendCommand({ type: CommandType.TOGGLE_STAR, exchangeId });
    }, []);

    // R5: Bookmark a code block (bookmarks the entire exchange)
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
        metrics: activeMetrics,
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
        setScrolledUp: setIsScrolledUp,
        saveScrollPosition,
        bookmarkCodeBlock,
    };
}