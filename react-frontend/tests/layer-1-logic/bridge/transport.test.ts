/**
 * ─── transport.test.ts ─────────────────────────────────────────────
 *
 * WHAT THIS TESTS:
 *   Behind the scenes: the wire protocol between React and Kotlin.
 *   No UI — this is infrastructure. But if it breaks, nothing works.
 *
 *   Specifically:
 *   - Event listener registration (onEvent, onAnyEvent)
 *   - Listener unsubscription (cleanup for React useEffect)
 *   - Event routing (type-specific vs wildcard)
 *   - Error resilience (bad JSON, throwing listeners)
 *   - Command dispatch (JCEF vs mock mode detection)
 *   - Mock handler behavior (dev mode simulated responses)
 *
 * LAYER: 1 — Unit/Integration Tests (module-level state, no DOM rendering)
 *
 * NOTE ON IMPORTS:
 *   transport.ts registers window.__ymm_bridgeReceive on import and
 *   holds module-level state (listeners Map, wildcardListeners array).
 *   We use vi.resetModules() + dynamic import to get a fresh module
 *   for each test, avoiding cross-test pollution.
 *
 * @see transport.ts — the source file these tests validate
 * @see types.ts — BridgeCommand and BridgeEvent type definitions
 */

import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import type { BridgeEvent } from "../../../src/bridge/types";

// ═══════════════════════════════════════════════════════════════════════
//  FRESH MODULE IMPORT HELPER
// ═══════════════════════════════════════════════════════════════════════

/**
 * Dynamically import a fresh copy of the transport module.
 *
 * Each call returns a new module with clean listener state.
 * This prevents test A's listeners from leaking into test B.
 */
async function freshTransport() {
    // Reset the module registry so the next import creates a new instance
    vi.resetModules();
    // Dynamic import gets a fresh module
    return await import("../../../src/bridge/transport");
}

// ═══════════════════════════════════════════════════════════════════════
//  TEST SETUP
// ═══════════════════════════════════════════════════════════════════════

describe("transport — event listener registry", () => {
    // Clean up window globals after each test to avoid pollution
    afterEach(() => {
        delete window.__ymm_cefQuery;
        delete window.__ymm_bridgeReceive;
    });

    it("onEvent registers a listener that receives matching events", async () => {
        // BEHIND THE SCENES: when Kotlin sends a CHAT_RESULT event,
        // only listeners registered for CHAT_RESULT should fire.
        const transport = await freshTransport();
        const listener = vi.fn();

        transport.onEvent("CHAT_RESULT", listener);

        // Simulate Kotlin sending an event through the global receiver
        const event: BridgeEvent = {
            type: "CHAT_RESULT",
            displayText: "Hello!",
            isError: false,
            exchangeId: "test-123",
            conversationId: "conv-1",
            correctionAvailable: false,
            parseStrategy: "DIRECT",
            confidence: "HIGH",
            providerId: "p1",
            modelId: "gpt-4o",
            contextSummary: null,
            contextTimeMs: null,
            tokenUsage: null,
        };
        window.__ymm_bridgeReceive!(JSON.stringify(event));

        expect(listener).toHaveBeenCalledTimes(1);
        expect(listener).toHaveBeenCalledWith(expect.objectContaining({ type: "CHAT_RESULT" }));
    });

    it("onEvent does NOT fire listeners registered for different event types", async () => {
        // BEHIND THE SCENES: a listener for SHOW_THINKING must not fire
        // when a CHAT_RESULT event arrives. Type routing is strict.
        const transport = await freshTransport();
        const thinkingListener = vi.fn();

        transport.onEvent("SHOW_THINKING", thinkingListener);

        // Send a CHAT_RESULT — the SHOW_THINKING listener should NOT fire
        const event: BridgeEvent = {
            type: "CHAT_RESULT",
            displayText: "Hello!",
            isError: false,
            exchangeId: "test-123",
            conversationId: "conv-1",
            correctionAvailable: false,
            parseStrategy: "DIRECT",
            confidence: "HIGH",
            providerId: null,
            modelId: null,
            contextSummary: null,
            contextTimeMs: null,
            tokenUsage: null,
        };
        window.__ymm_bridgeReceive!(JSON.stringify(event));

        expect(thinkingListener).not.toHaveBeenCalled();
    });

    it("onEvent returns an unsubscribe function that removes the listener", async () => {
        // BEHIND THE SCENES: React useEffect cleanup calls unsubscribe.
        // If this doesn't work, we get memory leaks and ghost listeners
        // that fire after a component has unmounted.
        const transport = await freshTransport();
        const listener = vi.fn();

        const unsubscribe = transport.onEvent("SYSTEM_MESSAGE", listener);

        // Fire once — listener should be called
        window.__ymm_bridgeReceive!(JSON.stringify({
            type: "SYSTEM_MESSAGE",
            content: "first",
            level: "INFO",
        }));
        expect(listener).toHaveBeenCalledTimes(1);

        // Unsubscribe
        unsubscribe();

        // Fire again — listener should NOT be called
        window.__ymm_bridgeReceive!(JSON.stringify({
            type: "SYSTEM_MESSAGE",
            content: "second",
            level: "INFO",
        }));
        expect(listener).toHaveBeenCalledTimes(1); // still 1, not 2
    });

    it("onAnyEvent receives ALL event types (wildcard)", async () => {
        // BEHIND THE SCENES: the logging system uses wildcard listeners
        // to capture everything. This verifies the wildcard mechanism.
        const transport = await freshTransport();
        const wildcardListener = vi.fn();

        transport.onAnyEvent(wildcardListener);

        // Send two different event types
        window.__ymm_bridgeReceive!(JSON.stringify({ type: "SHOW_THINKING" }));
        window.__ymm_bridgeReceive!(JSON.stringify({
            type: "SYSTEM_MESSAGE",
            content: "test",
            level: "INFO",
        }));

        expect(wildcardListener).toHaveBeenCalledTimes(2);
        expect(wildcardListener).toHaveBeenNthCalledWith(1, expect.objectContaining({ type: "SHOW_THINKING" }));
        expect(wildcardListener).toHaveBeenNthCalledWith(2, expect.objectContaining({ type: "SYSTEM_MESSAGE" }));
    });

    it("onAnyEvent unsubscribe works correctly", async () => {
        const transport = await freshTransport();
        const listener = vi.fn();

        const unsub = transport.onAnyEvent(listener);

        window.__ymm_bridgeReceive!(JSON.stringify({ type: "SHOW_THINKING" }));
        expect(listener).toHaveBeenCalledTimes(1);

        unsub();

        window.__ymm_bridgeReceive!(JSON.stringify({ type: "HIDE_THINKING" }));
        expect(listener).toHaveBeenCalledTimes(1); // still 1
    });

    it("multiple listeners for the same event type all receive it", async () => {
        // BEHIND THE SCENES: multiple React components may subscribe to
        // the same event type (e.g., MetricsBar and useBridge both listen
        // for UPDATE_METRICS). All must receive the event.
        const transport = await freshTransport();
        const listener1 = vi.fn();
        const listener2 = vi.fn();

        transport.onEvent("SHOW_THINKING", listener1);
        transport.onEvent("SHOW_THINKING", listener2);

        window.__ymm_bridgeReceive!(JSON.stringify({ type: "SHOW_THINKING" }));

        expect(listener1).toHaveBeenCalledTimes(1);
        expect(listener2).toHaveBeenCalledTimes(1);
    });
});

// ═══════════════════════════════════════════════════════════════════════
//  ERROR RESILIENCE
// ═══════════════════════════════════════════════════════════════════════

describe("transport — error resilience", () => {
    afterEach(() => {
        delete window.__ymm_cefQuery;
        delete window.__ymm_bridgeReceive;
    });

    it("handles malformed JSON without crashing", async () => {
        // BEHIND THE SCENES: if Kotlin sends garbage (shouldn't happen,
        // but resilience matters), the transport logs an error and
        // continues. No listener is called, no exception propagates.
        const transport = await freshTransport();
        const listener = vi.fn();
        transport.onAnyEvent(listener);

        // Suppress the expected console.error from the transport
        const consoleSpy = vi.spyOn(console, "error").mockImplementation(() => {});

        // This should NOT throw — it should catch internally
        expect(() => {
            window.__ymm_bridgeReceive!("not valid json {{{");
        }).not.toThrow();

        // No listener should have been called
        expect(listener).not.toHaveBeenCalled();

        consoleSpy.mockRestore();
    });

    it("a throwing listener does NOT prevent other listeners from running", async () => {
        // BEHIND THE SCENES: if one listener throws, other listeners
        // for the same event type still get called. This is critical
        // for resilience — one buggy component shouldn't break the
        // entire event system.
        const transport = await freshTransport();

        const badListener = vi.fn(() => {
            throw new Error("I'm a broken listener");
        });
        const goodListener = vi.fn();

        transport.onEvent("SHOW_THINKING", badListener);
        transport.onEvent("SHOW_THINKING", goodListener);

        // Suppress the expected console.error from the transport
        const consoleSpy = vi.spyOn(console, "error").mockImplementation(() => {});

        window.__ymm_bridgeReceive!(JSON.stringify({ type: "SHOW_THINKING" }));

        // Bad listener was called (and threw)
        expect(badListener).toHaveBeenCalledTimes(1);
        // Good listener was ALSO called (not blocked by the throw)
        expect(goodListener).toHaveBeenCalledTimes(1);

        consoleSpy.mockRestore();
    });

    it("a throwing wildcard listener does NOT prevent other wildcard listeners from running", async () => {
        const transport = await freshTransport();

        const badWildcard = vi.fn(() => { throw new Error("boom"); });
        const goodWildcard = vi.fn();

        transport.onAnyEvent(badWildcard);
        transport.onAnyEvent(goodWildcard);

        const consoleSpy = vi.spyOn(console, "error").mockImplementation(() => {});

        window.__ymm_bridgeReceive!(JSON.stringify({ type: "SHOW_THINKING" }));

        expect(badWildcard).toHaveBeenCalledTimes(1);
        expect(goodWildcard).toHaveBeenCalledTimes(1);

        consoleSpy.mockRestore();
    });
});

// ═══════════════════════════════════════════════════════════════════════
//  COMMAND DISPATCH: JCEF vs MOCK MODE
// ═══════════════════════════════════════════════════════════════════════

describe("transport — sendCommand", () => {
    afterEach(() => {
        delete window.__ymm_cefQuery;
        delete window.__ymm_bridgeReceive;
    });

    it("calls window.__ymm_cefQuery when it exists (JCEF mode)", async () => {
        // BEHIND THE SCENES: in production, commands go through the
        // real JCEF bridge. We verify detection and routing.
        const mockCefQuery = vi.fn();
        window.__ymm_cefQuery = mockCefQuery;

        const transport = await freshTransport();

        transport.sendCommand({ type: "REQUEST_PROVIDERS" });

        expect(mockCefQuery).toHaveBeenCalledTimes(1);
        // Verify it serialized the command to JSON
        const calledWith = mockCefQuery.mock.calls[0][0];
        expect(JSON.parse(calledWith)).toEqual({ type: "REQUEST_PROVIDERS" });
    });

    it("uses mock handler when __ymm_cefQuery is absent (dev mode)", async () => {
        // BEHIND THE SCENES: in Vite dev mode, commands go to the mock
        // handler. We verify it doesn't crash and routes correctly.
        // (We don't test specific mock responses here — that's below.)
        delete window.__ymm_cefQuery;

        const consoleSpy = vi.spyOn(console, "log").mockImplementation(() => {});
        const transport = await freshTransport();

        // Should NOT throw
        expect(() => {
            transport.sendCommand({ type: "REQUEST_PROVIDERS" });
        }).not.toThrow();

        consoleSpy.mockRestore();
    });

    it("isJcefMode() returns true when __ymm_cefQuery exists", async () => {
        window.__ymm_cefQuery = vi.fn();
        const transport = await freshTransport();
        expect(transport.isJcefMode()).toBe(true);
    });

    it("isJcefMode() returns false when __ymm_cefQuery is absent", async () => {
        delete window.__ymm_cefQuery;
        const transport = await freshTransport();
        expect(transport.isJcefMode()).toBe(false);
    });
});

// ═══════════════════════════════════════════════════════════════════════
//  MOCK HANDLER: dev mode simulated responses
// ═══════════════════════════════════════════════════════════════════════

describe("transport — mock handler (dev mode responses)", () => {
    afterEach(() => {
        delete window.__ymm_cefQuery;
        delete window.__ymm_bridgeReceive;
    });

    it("SEND_MESSAGE triggers SHOW_THINKING then CHAT_RESULT then UPDATE_METRICS", async () => {
        // BEHIND THE SCENES: the mock simulates the full response cycle
        // so you can develop the UI without running the IntelliJ plugin.
        // The timing is: SHOW_THINKING (50ms) → CHAT_RESULT (800ms) → UPDATE_METRICS (850ms)
        vi.useFakeTimers();
        const consoleSpy = vi.spyOn(console, "log").mockImplementation(() => {});

        const transport = await freshTransport();
        const events: string[] = [];

        transport.onAnyEvent((e) => events.push(e.type));

        transport.sendCommand({
            type: "SEND_MESSAGE",
            text: "hello",
            conversationId: "conv-1",
            providerId: null,
            bypassMode: null,
            selectiveLevel: null,
            summaryEnabled: null,
        });

        // Nothing yet — events are delayed
        expect(events).toEqual([]);

        // Advance past SHOW_THINKING (50ms)
        vi.advanceTimersByTime(51);
        expect(events).toContain("SHOW_THINKING");

        // Advance past CHAT_RESULT (800ms total)
        vi.advanceTimersByTime(800);
        expect(events).toContain("CHAT_RESULT");

        // Advance past UPDATE_METRICS (850ms total)
        vi.advanceTimersByTime(100);
        expect(events).toContain("UPDATE_METRICS");

        vi.useRealTimers();
        consoleSpy.mockRestore();
    });

    it("REQUEST_PROVIDERS returns a list of mock providers", async () => {
        // BEHIND THE SCENES: dev mode needs fake providers so the
        // ProviderSelector dropdown has data to render.
        vi.useFakeTimers();
        const consoleSpy = vi.spyOn(console, "log").mockImplementation(() => {});

        const transport = await freshTransport();
        const listener = vi.fn();

        transport.onEvent("PROVIDERS_LIST", listener);

        transport.sendCommand({ type: "REQUEST_PROVIDERS" });

        // Advance past the mock delay (100ms)
        vi.advanceTimersByTime(150);

        expect(listener).toHaveBeenCalledTimes(1);
        const event = listener.mock.calls[0][0];
        expect(event.type).toBe("PROVIDERS_LIST");
        expect(event.providers.length).toBeGreaterThan(0);
        // Every provider should have an id and label
        for (const provider of event.providers) {
            expect(provider.id).toBeTruthy();
            expect(provider.label).toBeTruthy();
        }

        vi.useRealTimers();
        consoleSpy.mockRestore();
    });

    it("CLEAR_CHAT triggers CONVERSATION_CLEARED event", async () => {
        vi.useFakeTimers();
        const consoleSpy = vi.spyOn(console, "log").mockImplementation(() => {});

        const transport = await freshTransport();
        const listener = vi.fn();

        transport.onEvent("CONVERSATION_CLEARED", listener);
        transport.sendCommand({ type: "CLEAR_CHAT" });

        vi.advanceTimersByTime(100);

        expect(listener).toHaveBeenCalledTimes(1);

        vi.useRealTimers();
        consoleSpy.mockRestore();
    });

    it("TOGGLE_STAR toggles star state and emits STAR_UPDATED", async () => {
        // BEHIND THE SCENES: star toggling needs to work in dev mode
        // for UI development. The mock tracks state in memory.
        vi.useFakeTimers();
        const consoleSpy = vi.spyOn(console, "log").mockImplementation(() => {});

        const transport = await freshTransport();
        const listener = vi.fn();

        transport.onEvent("STAR_UPDATED", listener);

        // First toggle — should star it
        transport.sendCommand({ type: "TOGGLE_STAR", exchangeId: "ex-1" });
        vi.advanceTimersByTime(150);

        expect(listener).toHaveBeenCalledTimes(1);
        expect(listener.mock.calls[0][0].isStarred).toBe(true);

        // Second toggle — should un-star it
        transport.sendCommand({ type: "TOGGLE_STAR", exchangeId: "ex-1" });
        vi.advanceTimersByTime(150);

        expect(listener).toHaveBeenCalledTimes(2);
        expect(listener.mock.calls[1][0].isStarred).toBe(false);

        vi.useRealTimers();
        consoleSpy.mockRestore();
    });
});
