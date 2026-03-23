/**
 * ─── accumulator.test.ts ───────────────────────────────────────────
 *
 * WHAT THIS TESTS:
 *   Behind the scenes: the pure math that powers the MetricsBar.
 *   These functions take numbers in and return numbers/strings out.
 *   No UI, no DOM, no React — just logic.
 *
 * LAYER: 1 — Unit Tests (pure functions)
 *
 * WHY THESE MATTER:
 *   The MetricsBar displays token counts, fill percentages, and color
 *   thresholds based on these functions. If the math is wrong, the
 *   user sees incorrect data — and with AI token costs, that matters.
 *
 * @see accumulator.ts — the source file these tests validate
 * @see MetricsBar.tsx — the component that calls these functions
 */

import { describe, it, expect } from "vitest";
import {
    createAccumulator,
    accumulate,
    formatTokenCount,
    contextFillPercent,
    fillBarColor,
} from "../../../src/metrics/accumulator";
import type { MetricsSnapshot } from "../../../src/metrics/types";

// ═══════════════════════════════════════════════════════════════════════
//  HELPER: reusable snapshot factory
// ═══════════════════════════════════════════════════════════════════════

/**
 * Create a MetricsSnapshot with sensible defaults.
 * Override only what each test needs — keeps test code lean.
 */
function makeSnapshot(overrides: Partial<MetricsSnapshot> = {}): MetricsSnapshot {
    return {
        model: "gpt-4o",
        promptTokens: 100,
        completionTokens: 200,
        totalTokens: 300,
        contextWindowSize: 128000,
        responseTimeMs: 1500,
        ...overrides,
    };
}

// ═══════════════════════════════════════════════════════════════════════
//  createAccumulator()
// ═══════════════════════════════════════════════════════════════════════

describe("createAccumulator", () => {
    it("returns an accumulator with all counters at zero", () => {
        // BEHIND THE SCENES: when a new tab is created, its metrics
        // start from scratch. This verifies the starting state.
        const acc = createAccumulator();

        expect(acc.totalPromptTokens).toBe(0);
        expect(acc.totalCompletionTokens).toBe(0);
        expect(acc.totalTokens).toBe(0);
        expect(acc.exchangeCount).toBe(0);
        expect(acc.byModel).toEqual({});
    });
});

// ═══════════════════════════════════════════════════════════════════════
//  accumulate()
// ═══════════════════════════════════════════════════════════════════════

describe("accumulate", () => {
    it("adds one snapshot's tokens to the running totals", () => {
        // BEHIND THE SCENES: after each AI response, the metrics
        // from that exchange are added to the session totals.
        // This is what makes the "Session: 1.2k tokens" display work.
        const acc = createAccumulator();
        const snapshot = makeSnapshot();

        const result = accumulate(acc, snapshot);

        expect(result.totalPromptTokens).toBe(100);
        expect(result.totalCompletionTokens).toBe(200);
        expect(result.totalTokens).toBe(300);
        expect(result.exchangeCount).toBe(1);
    });

    it("accumulates multiple exchanges correctly", () => {
        // BEHIND THE SCENES: session totals grow with each exchange.
        // After 3 exchanges, the counts should be the sum of all 3.
        let acc = createAccumulator();

        acc = accumulate(acc, makeSnapshot({ promptTokens: 100, completionTokens: 200, totalTokens: 300 }));
        acc = accumulate(acc, makeSnapshot({ promptTokens: 150, completionTokens: 250, totalTokens: 400 }));
        acc = accumulate(acc, makeSnapshot({ promptTokens: 50, completionTokens: 100, totalTokens: 150 }));

        expect(acc.totalPromptTokens).toBe(300);
        expect(acc.totalCompletionTokens).toBe(550);
        expect(acc.totalTokens).toBe(850);
        expect(acc.exchangeCount).toBe(3);
    });

    it("handles null token values by skipping them (not crashing)", () => {
        // BEHIND THE SCENES: some AI providers don't report all token
        // fields. The accumulator must handle this gracefully — null
        // values are skipped, non-null values are still added.
        const acc = createAccumulator();
        const snapshot = makeSnapshot({
            promptTokens: null,
            completionTokens: 200,
            totalTokens: null,
        });

        const result = accumulate(acc, snapshot);

        expect(result.totalPromptTokens).toBe(0);     // null was skipped
        expect(result.totalCompletionTokens).toBe(200);
        expect(result.totalTokens).toBe(0);            // null was skipped
        expect(result.exchangeCount).toBe(1);           // always increments
    });

    it("groups exchanges by model in the byModel breakdown", () => {
        // BEHIND THE SCENES: the accumulator tracks per-model subtotals
        // so the future Metrics Tab can show a model comparison chart.
        let acc = createAccumulator();

        acc = accumulate(acc, makeSnapshot({ model: "gpt-4o", totalTokens: 300 }));
        acc = accumulate(acc, makeSnapshot({ model: "gemini-2.5-flash", totalTokens: 200 }));
        acc = accumulate(acc, makeSnapshot({ model: "gpt-4o", totalTokens: 400 }));

        expect(acc.byModel["gpt-4o"].exchangeCount).toBe(2);
        expect(acc.byModel["gpt-4o"].totalTokens).toBe(700);
        expect(acc.byModel["gemini-2.5-flash"].exchangeCount).toBe(1);
        expect(acc.byModel["gemini-2.5-flash"].totalTokens).toBe(200);
    });

    it('groups exchanges with null model under "__unknown__"', () => {
        // BEHIND THE SCENES: if the provider doesn't report a model name,
        // we still count the tokens — they go under a special key so
        // they're not silently lost.
        const acc = createAccumulator();
        const snapshot = makeSnapshot({ model: null, totalTokens: 300 });

        const result = accumulate(acc, snapshot);

        expect(result.byModel["__unknown__"]).toBeDefined();
        expect(result.byModel["__unknown__"].exchangeCount).toBe(1);
        expect(result.byModel["__unknown__"].totalTokens).toBe(300);
    });

    it("returns a NEW object (immutable — safe for React state)", () => {
        // BEHIND THE SCENES: React needs immutable state updates.
        // If accumulate() mutated the original object, React wouldn't
        // detect the change and the MetricsBar wouldn't re-render.
        const acc = createAccumulator();
        const snapshot = makeSnapshot();

        const result = accumulate(acc, snapshot);

        // Different object reference — React's === check will detect the change
        expect(result).not.toBe(acc);
    });

    it("does not mutate the original accumulator", () => {
        // BEHIND THE SCENES: double-checking immutability — the original
        // accumulator should be untouched after accumulate() returns.
        const acc = createAccumulator();
        const snapshot = makeSnapshot({ totalTokens: 500 });

        accumulate(acc, snapshot);

        // Original should still be at zero
        expect(acc.totalTokens).toBe(0);
        expect(acc.exchangeCount).toBe(0);
    });
});

// ═══════════════════════════════════════════════════════════════════════
//  formatTokenCount()
// ═══════════════════════════════════════════════════════════════════════

describe("formatTokenCount", () => {
    // VISIBLE TO THE USER: this is what appears in the MetricsBar.
    // "1.2k" instead of "1234" — the formatting the user actually reads.

    it('returns "–" for null (token data unavailable)', () => {
        expect(formatTokenCount(null)).toBe("–");
    });

    it('returns "0" for zero', () => {
        expect(formatTokenCount(0)).toBe("0");
    });

    it("returns raw number for values under 1000", () => {
        expect(formatTokenCount(1)).toBe("1");
        expect(formatTokenCount(42)).toBe("42");
        expect(formatTokenCount(847)).toBe("847");
        expect(formatTokenCount(999)).toBe("999");
    });

    it("formats thousands with one decimal (< 100k)", () => {
        expect(formatTokenCount(1000)).toBe("1.0k");
        expect(formatTokenCount(1234)).toBe("1.2k");
        expect(formatTokenCount(12345)).toBe("12.3k");
        expect(formatTokenCount(99999)).toBe("100.0k");
    });

    it("drops decimals for >= 100k (space is tight in the bar)", () => {
        expect(formatTokenCount(100000)).toBe("100k");
        expect(formatTokenCount(123456)).toBe("123k");
        expect(formatTokenCount(999999)).toBe("1000k");
    });

    it("formats millions with one decimal", () => {
        expect(formatTokenCount(1000000)).toBe("1.0M");
        expect(formatTokenCount(1234567)).toBe("1.2M");
        expect(formatTokenCount(12345678)).toBe("12.3M");
    });

    it("handles negative values (edge case, shouldn't happen in practice)", () => {
        // BEHIND THE SCENES: negative tokens shouldn't occur, but the
        // function should handle them without crashing.
        expect(formatTokenCount(-500)).toBe("-500");
        expect(formatTokenCount(-1500)).toBe("-1.5k");
    });
});

// ═══════════════════════════════════════════════════════════════════════
//  contextFillPercent()
// ═══════════════════════════════════════════════════════════════════════

describe("contextFillPercent", () => {
    // VISIBLE TO THE USER: powers the fill bar width in the MetricsBar.
    // The user sees a colored bar that fills up as they use more of
    // the model's context window.

    it("returns null when totalTokens is null", () => {
        expect(contextFillPercent(null, 128000)).toBeNull();
    });

    it("returns null when windowSize is null", () => {
        expect(contextFillPercent(1000, null)).toBeNull();
    });

    it("returns null when windowSize is zero", () => {
        expect(contextFillPercent(1000, 0)).toBeNull();
    });

    it("returns null when windowSize is negative", () => {
        expect(contextFillPercent(1000, -1)).toBeNull();
    });

    it("calculates correct percentage for typical values", () => {
        expect(contextFillPercent(64000, 128000)).toBe(50);
        expect(contextFillPercent(96000, 128000)).toBe(75);
        expect(contextFillPercent(128000, 128000)).toBe(100);
    });

    it("can exceed 100% (some providers report more than window size)", () => {
        // BEHIND THE SCENES: this happens when a provider reports token
        // counts that exceed the configured context window. We don't
        // clamp — we let the UI show > 100% as a warning.
        const result = contextFillPercent(150000, 128000);
        expect(result).not.toBeNull();
        expect(result!).toBeGreaterThan(100);
    });

    it("handles very small percentages", () => {
        const result = contextFillPercent(1, 128000);
        expect(result).not.toBeNull();
        expect(result!).toBeGreaterThan(0);
        expect(result!).toBeLessThan(1);
    });
});

// ═══════════════════════════════════════════════════════════════════════
//  fillBarColor()
// ═══════════════════════════════════════════════════════════════════════

describe("fillBarColor", () => {
    // VISIBLE TO THE USER: the fill bar changes color as the context
    // window fills up. Grey → Green → Amber → Red. The color gives
    // an at-a-glance health indicator.

    it('returns "muted" for null (no data = bar hidden)', () => {
        expect(fillBarColor(null)).toBe("muted");
    });

    it('returns "muted" (grey) for < 50% — context is plentiful', () => {
        expect(fillBarColor(0)).toBe("muted");
        expect(fillBarColor(25)).toBe("muted");
        expect(fillBarColor(49)).toBe("muted");
        expect(fillBarColor(49.9)).toBe("muted");
    });

    it('returns "green" for 50-74% — healthy usage', () => {
        expect(fillBarColor(50)).toBe("green");
        expect(fillBarColor(60)).toBe("green");
        expect(fillBarColor(74)).toBe("green");
        expect(fillBarColor(74.9)).toBe("green");
    });

    it('returns "amber" for 75-89% — approaching the limit', () => {
        expect(fillBarColor(75)).toBe("amber");
        expect(fillBarColor(80)).toBe("amber");
        expect(fillBarColor(89)).toBe("amber");
        expect(fillBarColor(89.9)).toBe("amber");
    });

    it('returns "red" for >= 90% — near capacity, user should be aware', () => {
        expect(fillBarColor(90)).toBe("red");
        expect(fillBarColor(95)).toBe("red");
        expect(fillBarColor(100)).toBe("red");
        expect(fillBarColor(150)).toBe("red"); // over 100% still red
    });
});
