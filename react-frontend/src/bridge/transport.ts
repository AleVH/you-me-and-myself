/**
 * Bridge transport layer — handles the wire protocol between React and Kotlin.
 *
 * ## Two Modes
 *
 * ### JCEF Mode (Production)
 *
 * When running inside the IntelliJ plugin, JcefBridgeTransport.kt injects
 * `window.__ymm_cefQuery` as a global function. This transport detects it
 * and uses it to send commands. Events arrive via `window.__ymm_bridgeReceive`,
 * which Kotlin calls via `browser.executeJavaScript()`.
 *
 * ### Mock Mode (Development)
 *
 * When running via `npm run dev` in a browser, `window.__ymm_cefQuery` doesn't
 * exist. The transport falls back to a mock that simulates backend responses.
 * This allows full UI development without running the IntelliJ plugin.
 *
 * ## Protocol
 *
 * Commands (React → Kotlin):
 *   React serializes a BridgeCommand to JSON → calls window.__ymm_cefQuery(json)
 *   → JCEF's cefQuery handler picks it up → JcefBridgeTransport.handleIncomingCommand()
 *   → BridgeMessage.parseCommand() → BridgeDispatcher.dispatch()
 *
 * Events (Kotlin → React):
 *   BridgeDispatcher.emit() → BridgeMessage.serializeEvent() → JcefBridgeTransport.sendEventToFrontend()
 *   → browser.executeJavaScript("window.__ymm_bridgeReceive('...')") → this transport's listener
 *
 * ## R4 Changes
 *
 * Added mock handlers for: SWITCH_TAB, CLOSE_TAB, SAVE_TAB_STATE,
 * REQUEST_TAB_STATE, LOAD_CONVERSATION, TOGGLE_STAR.
 * Mock tab state simulates persistence via an in-memory store.
 *
 * @see JcefBridgeTransport.kt — Kotlin counterpart
 * @see types.ts — Message type definitions
 */

import type { BridgeCommand, BridgeEvent, EventType } from "./types";

// ═══════════════════════════════════════════════════════════════════════
//  GLOBAL DECLARATIONS
// ═══════════════════════════════════════════════════════════════════════

/**
 * Extend the Window interface with the JCEF bridge globals.
 *
 * - __ymm_cefQuery: Injected by JcefBridgeTransport.injectQueryFunction().
 *   Wraps JCEF's auto-generated cefQuery function with a stable name.
 *
 * - __ymm_bridgeReceive: Registered by this transport module. Called by
 *   JcefBridgeTransport.sendEventToFrontend() to deliver events.
 */
declare global {
    interface Window {
        __ymm_cefQuery?: (json: string) => void;
        __ymm_bridgeReceive?: (json: string) => void;
    }
}

// ═══════════════════════════════════════════════════════════════════════
//  EVENT LISTENER REGISTRY
// ═══════════════════════════════════════════════════════════════════════

/**
 * Callback type for event listeners.
 *
 * Listeners receive the fully typed event object (already deserialized
 * from JSON by the transport layer). They don't need to parse JSON.
 */
type EventListener = (event: BridgeEvent) => void;

/**
 * Map of event type → list of listener callbacks.
 *
 * Listeners are registered per event type for efficient routing.
 * A listener for "CHAT_RESULT" won't be called for "SHOW_THINKING".
 */
const listeners: Map<string, EventListener[]> = new Map();

/**
 * Wildcard listeners that receive ALL events regardless of type.
 *
 * Useful for logging, debugging, or components that need to react
 * to every event (like a status indicator).
 */
const wildcardListeners: EventListener[] = [];

// ═══════════════════════════════════════════════════════════════════════
//  PUBLIC API
// ═══════════════════════════════════════════════════════════════════════

/**
 * Send a command to the Kotlin backend.
 *
 * In JCEF mode, serializes the command to JSON and calls `window.__ymm_cefQuery`.
 * In dev mode, routes to the mock handler for simulated responses.
 *
 * @param command The typed command to send. Must match a BridgeCommand variant.
 */
export function sendCommand(command: BridgeCommand): void {
    const json = JSON.stringify(command);

    if (typeof window.__ymm_cefQuery === "function") {
        // Production mode — send via JCEF query handler
        window.__ymm_cefQuery(json);
    } else {
        // Dev mode — route to mock handler
        console.log("[YMM Bridge Mock] Command:", command.type, command);
        handleMockCommand(command);
    }
}

/**
 * Subscribe to a specific event type from the Kotlin backend.
 *
 * @param eventType The event type to listen for (e.g., EventType.CHAT_RESULT)
 * @param listener Callback invoked when an event of this type arrives
 * @returns Unsubscribe function — call it to remove the listener
 */
export function onEvent(
    eventType: EventType | string,
    listener: EventListener,
): () => void {
    const existing = listeners.get(eventType) ?? [];
    existing.push(listener);
    listeners.set(eventType, existing);

    // Return unsubscribe function for React useEffect cleanup
    return () => {
        const arr = listeners.get(eventType);
        if (arr) {
            const idx = arr.indexOf(listener);
            if (idx !== -1) arr.splice(idx, 1);
        }
    };
}

/**
 * Subscribe to ALL events regardless of type.
 *
 * @param listener Callback invoked for every event
 * @returns Unsubscribe function
 */
export function onAnyEvent(listener: EventListener): () => void {
    wildcardListeners.push(listener);
    return () => {
        const idx = wildcardListeners.indexOf(listener);
        if (idx !== -1) wildcardListeners.splice(idx, 1);
    };
}

/**
 * Check if the transport is running in JCEF (production) mode.
 *
 * Components can use this to show dev-mode indicators or hide
 * features that only work in the real plugin environment.
 */
export function isJcefMode(): boolean {
    return typeof window.__ymm_cefQuery === "function";
}

// ═══════════════════════════════════════════════════════════════════════
//  EVENT RECEIVER (called by Kotlin via executeJavaScript)
// ═══════════════════════════════════════════════════════════════════════

/**
 * Process an incoming event from the Kotlin backend.
 *
 * This function is registered as `window.__ymm_bridgeReceive` and called
 * by JcefBridgeTransport.sendEventToFrontend() via executeJavaScript.
 *
 * It parses the JSON, routes to type-specific listeners, then to wildcard
 * listeners. Parse errors are logged but never thrown (resilience).
 *
 * @param json Raw JSON string from the Kotlin backend
 */
function receiveEvent(json: string): void {
    try {
        const event = JSON.parse(json) as BridgeEvent;

        // Route to type-specific listeners
        const typeListeners = listeners.get(event.type);
        if (typeListeners) {
            for (const listener of typeListeners) {
                try {
                    listener(event);
                } catch (err) {
                    console.error(`[YMM Bridge] Listener error for ${event.type}:`, err);
                }
            }
        }

        // Route to wildcard listeners
        for (const listener of wildcardListeners) {
            try {
                listener(event);
            } catch (err) {
                console.error("[YMM Bridge] Wildcard listener error:", err);
            }
        }
    } catch (err) {
        console.error("[YMM Bridge] Failed to parse event JSON:", err, json);
    }
}

// Register the global receiver function.
// Kotlin's JcefBridgeTransport calls: window.__ymm_bridgeReceive('...')
window.__ymm_bridgeReceive = receiveEvent;

// ═══════════════════════════════════════════════════════════════════════
//  MOCK HANDLER (Dev Mode Only)
// ═══════════════════════════════════════════════════════════════════════

/**
 * In-memory mock store for dev mode tab state persistence.
 *
 * Simulates the SQLite open_tabs table so tab save/restore works
 * during development without the IntelliJ plugin running.
 */
const mockTabStore: {
    tabs: Array<{
        id: string;
        conversationId: string | null;
        title: string;
        tabOrder: number;
        isActive: boolean;
        scrollPosition: number;
    }>;
    keepTabs: boolean;
} = {
    tabs: [],
    keepTabs: true,
};

/**
 * In-memory mock store for exchange star state.
 * Maps exchangeId → isStarred. Simulates the backend star storage.
 */
const mockStarStore: Map<string, boolean> = new Map();

/**
 * Simulate backend responses for development without the IntelliJ plugin.
 *
 * Each command type gets a realistic mock response after a short delay
 * to simulate network latency. This lets you develop the full UI cycle
 * (send message → thinking → response → metrics) in a browser.
 *
 * R4: Added handlers for tab management, conversation history, and starring.
 *
 * @param command The command that would have been sent to Kotlin
 */
function handleMockCommand(command: BridgeCommand): void {
    switch (command.type) {
        case "SEND_MESSAGE":
            // Simulate the full response cycle
            console.log(`[YMM Mock] SEND_MESSAGE received: conversationId=${command.conversationId}`);
            simulateEvent({ type: "SHOW_THINKING" }, 50);
            simulateEvent(
                {
                    type: "CHAT_RESULT",
                    displayText: `**Mock response** to: "${command.text}"\n\nThis is a simulated response from the dev mock transport. In production, this would come from the AI provider via ChatOrchestrator.\n\n\`\`\`kotlin\nfun hello() = println("Hello from mock!")\n\`\`\``,
                    isError: false,
                    exchangeId: `mock-${Date.now()}`,
                    conversationId: command.conversationId ?? "mock-conv-fallback",
                    correctionAvailable: false,
                    parseStrategy: "MOCK",
                    confidence: "HIGH",
                    providerId: "mock-provider",
                    modelId: "mock-model",
                    contextSummary: null,
                    contextTimeMs: null,
                    tokenUsage: { promptTokens: 42, completionTokens: 128, totalTokens: 170 },
                },
                800,
            );
            simulateEvent(
                {
                    type: "UPDATE_METRICS",
                    model: "mock-model",
                    promptTokens: 42,
                    completionTokens: 128,
                    totalTokens: 170,
                    estimatedCost: "$0.0003",
                },
                850,
            );
            break;

        case "REQUEST_PROVIDERS":
            simulateEvent(
                {
                    type: "PROVIDERS_LIST",
                    providers: [
                        { id: "mock-1", label: "Mock GPT-4", protocol: "OPENAI" },
                        { id: "mock-2", label: "Mock Gemini", protocol: "GEMINI" },
                        { id: "mock-3", label: "Mock DeepSeek", protocol: "OPENAI" },
                    ],
                    selectedId: "mock-1",
                },
                100,
            );
            break;

        case "CLEAR_CHAT":
        case "NEW_CONVERSATION":
            simulateEvent({ type: "CONVERSATION_CLEARED" }, 50);
            break;

        case "SWITCH_PROVIDER":
            simulateEvent(
                {
                    type: "SYSTEM_MESSAGE",
                    content: `Switched to provider: ${command.providerId}`,
                    level: "INFO",
                },
                100,
            );
            break;

        case "CONFIRM_CORRECTION":
            simulateEvent(
                {
                    type: "SYSTEM_MESSAGE",
                    content:
                        "✓ Format confirmed. Future responses from this provider will be parsed automatically.",
                    level: "INFO",
                },
                100,
            );
            break;

        case "REQUEST_CORRECTION":
            simulateEvent(
                {
                    type: "CORRECTION_CANDIDATES",
                    candidates: [
                        {
                            text: "Alternative extraction 1",
                            path: "choices[0].message.content",
                            confidence: "HIGH",
                        },
                        {
                            text: "Alternative extraction 2",
                            path: "result.text",
                            confidence: "MEDIUM",
                        },
                    ],
                },
                200,
            );
            break;

        // ── R4: Tab Management Mock Handlers ─────────────────────────

        case "SWITCH_TAB":
            // In production, backend updates active tab in open_tabs and
            // potentially switches the orchestrator's conversation.
            // Mock just acknowledges.
            console.log("[YMM Mock] Switch tab:", command.tabId);
            break;

        case "CLOSE_TAB":
            // In production, backend removes from open_tabs.
            // Mock removes from the in-memory store.
            mockTabStore.tabs = mockTabStore.tabs.filter(
                (t) => t.id !== command.tabId,
            );
            console.log("[YMM Mock] Closed tab:", command.tabId);
            break;

        case "SAVE_TAB_STATE":
            // In production, backend writes to open_tabs SQLite table.
            // Mock saves to in-memory store.
            mockTabStore.tabs = command.tabs.map((t) => ({ ...t }));
            console.log(
                "[YMM Mock] Saved tab state:",
                command.tabs.length,
                "tabs, active:",
                command.activeTabId,
            );
            break;

        case "REQUEST_TAB_STATE":
            // In production, backend reads open_tabs + keep_tabs setting.
            // Mock returns whatever was saved (or empty on first run).
            simulateEvent(
                {
                    type: "TAB_STATE",
                    tabs: mockTabStore.tabs,
                    keepTabs: mockTabStore.keepTabs,
                },
                100,
            );
            break;

        // ── R4: Conversation History Mock Handler ────────────────────

        case "LOAD_CONVERSATION":
            // In production, backend reads JSONL/SQLite for the conversation.
            // Mock returns a few fake messages so UI development works.
            simulateEvent(
                {
                    type: "CONVERSATION_HISTORY",
                    tabId: command.tabId,
                    conversationId: command.conversationId,
                    messages: [
                        {
                            role: "user",
                            content: "This is a restored message from history.",
                            timestamp: new Date(Date.now() - 60000).toISOString(),
                            exchangeId: null,
                            isStarred: false,
                        },
                        {
                            role: "assistant",
                            content:
                                "**Mock history response.** This was loaded from saved conversation data.\n\n```js\nconsole.log('restored!');\n```",
                            timestamp: new Date(Date.now() - 55000).toISOString(),
                            exchangeId: `mock-hist-${Date.now()}`,
                            isStarred: false,
                        },
                    ],
                },
                200,
            );
            break;

        // ── R4: Exchange Starring Mock Handler ───────────────────────

        case "TOGGLE_STAR": {
            // In production, backend toggles star in storage.
            // Mock toggles in-memory.
            const currentState = mockStarStore.get(command.exchangeId) ?? false;
            const newState = !currentState;
            mockStarStore.set(command.exchangeId, newState);
            simulateEvent(
                {
                    type: "STAR_UPDATED",
                    exchangeId: command.exchangeId,
                    isStarred: newState,
                },
                100,
            );
            break;
        }
    }
}

/**
 * Send a mock event after a delay to simulate async backend responses.
 *
 * @param event The event to simulate
 * @param delayMs Delay in milliseconds before the event fires
 */
function simulateEvent(event: BridgeEvent, delayMs: number): void {
    setTimeout(() => {
        receiveEvent(JSON.stringify(event));
    }, delayMs);
}