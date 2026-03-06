/**
 * Metrics Module — Pure accumulator functions.
 *
 * ## Purpose
 *
 * Stateless utility functions for creating accumulators, accumulating
 * snapshots into session totals, and formatting token counts for display.
 * All functions are pure (no side effects, no state) — easy to test.
 *
 * ## Usage Flow
 *
 * 1. Tab created → `createAccumulator()` for initial empty state
 * 2. UPDATE_METRICS event → `accumulate(session, snapshot)` returns new session
 * 3. MetricsBar render → `formatTokenCount()`, `contextFillPercent()`, `fillBarColor()`
 *
 * @see types.ts — Type definitions consumed and produced by these functions
 * @see MetricsBar.tsx — Display component that calls the formatting functions
 * @see useBridge.ts — State management that calls createAccumulator/accumulate
 */

import type { MetricsSnapshot, MetricsAccumulator, ModelBreakdown } from "./types";

// ═══════════════════════════════════════════════════════════════════════
//  ACCUMULATOR LIFECYCLE
// ═══════════════════════════════════════════════════════════════════════

/**
 * Create an empty accumulator for a new tab session.
 *
 * Called once per tab creation (both new tabs and restored tabs).
 * Returns zero-state — no exchanges, no tokens, no model breakdowns.
 */
export function createAccumulator(): MetricsAccumulator {
    return {
        exchangeCount: 0,
        promptTokens: 0,
        completionTokens: 0,
        totalTokens: 0,
        byModel: {},
    };
}

/**
 * Accumulate a new exchange snapshot into the session totals.
 *
 * Returns a NEW accumulator object (immutable update) — safe for React
 * state updates without mutation. The original accumulator is not modified.
 *
 * ## Null Handling
 *
 * Token fields that are null (provider didn't report them) are treated
 * as zero for accumulation purposes. This means session totals may
 * undercount if some providers don't report usage — but that's correct
 * behavior. We can't invent data we don't have.
 *
 * ## Model Grouping
 *
 * Exchanges with a null model name are grouped under the key "unknown".
 * This shouldn't happen in practice (every provider reports a model),
 * but defensive handling prevents runtime errors.
 *
 * @param acc - Current session accumulator (not mutated)
 * @param snapshot - New exchange data to fold in
 * @returns New accumulator with updated totals
 */
export function accumulate(
    acc: MetricsAccumulator,
    snapshot: MetricsSnapshot,
): MetricsAccumulator {
    // Treat null token counts as zero for accumulation.
    // We don't fabricate data — we just don't crash on missing data.
    const prompt = snapshot.promptTokens ?? 0;
    const completion = snapshot.completionTokens ?? 0;
    const total = snapshot.totalTokens ?? 0;

    // Model key for the byModel breakdown — "unknown" for null model names.
    const modelKey = snapshot.model ?? "unknown";

    // Get existing breakdown for this model, or create a fresh one.
    const existing: ModelBreakdown = acc.byModel[modelKey] ?? {
        exchangeCount: 0,
        promptTokens: 0,
        completionTokens: 0,
        totalTokens: 0,
    };

    // Build updated model breakdown (immutable).
    const updatedModel: ModelBreakdown = {
        exchangeCount: existing.exchangeCount + 1,
        promptTokens: existing.promptTokens + prompt,
        completionTokens: existing.completionTokens + completion,
        totalTokens: existing.totalTokens + total,
    };

    return {
        exchangeCount: acc.exchangeCount + 1,
        promptTokens: acc.promptTokens + prompt,
        completionTokens: acc.completionTokens + completion,
        totalTokens: acc.totalTokens + total,
        byModel: {
            ...acc.byModel,
            [modelKey]: updatedModel,
        },
    };
}

// ═══════════════════════════════════════════════════════════════════════
//  DISPLAY FORMATTING
// ═══════════════════════════════════════════════════════════════════════

/**
 * Format a token count for compact display.
 *
 * Produces human-readable abbreviated counts:
 *   - 0–999       → exact number: "0", "42", "999"
 *   - 1,000–9,999 → one decimal: "1.2k", "9.9k"
 *   - 10,000–999,999 → rounded: "10k", "150k", "999k"
 *   - 1,000,000+  → one decimal: "1.2M", "3.4M"
 *   - null        → "—" (em-dash, indicates data not available)
 *
 * The goal is always ≤5 characters for compact display in the MetricsBar.
 *
 * @param count - Token count, or null if not reported by provider
 * @returns Formatted string for display
 */
export function formatTokenCount(count: number | null): string {
    if (count === null) return "—";
    if (count < 1_000) return count.toString();
    if (count < 10_000) {
        // 1,234 → "1.2k" — one decimal for precision at small thousands
        const k = count / 1_000;
        return `${k.toFixed(1)}k`;
    }
    if (count < 1_000_000) {
        // 12,345 → "12k" — no decimal needed, the magnitude tells the story
        const k = Math.round(count / 1_000);
        return `${k}k`;
    }
    // 1,234,567 → "1.2M"
    const m = count / 1_000_000;
    return `${m.toFixed(1)}M`;
}

/**
 * Compute the context window fill percentage.
 *
 * Used for the context fill bar in MetricsBar — a visual indicator of
 * how close the conversation is to the model's context window limit.
 *
 * Returns null when either value is unavailable (can't compute a
 * percentage without both a numerator and a denominator).
 *
 * @param totalTokens - Total tokens used in the last exchange (prompt + completion)
 * @param windowSize - Model's maximum context window in tokens
 * @returns Fill percentage (0–100), or null if either input is null/zero
 */
export function contextFillPercent(
    totalTokens: number | null,
    windowSize: number | null,
): number | null {
    if (totalTokens === null || windowSize === null || windowSize === 0) {
        return null;
    }
    // Clamp to 100 — shouldn't exceed it, but defensive against edge cases
    // where totalTokens includes completion tokens beyond the window.
    return Math.min(Math.round((totalTokens / windowSize) * 100), 100);
}

/**
 * Determine the fill bar color based on context window usage percentage.
 *
 * Color bands match the design doc §6.1:
 *   - 'muted'  → 0–49%   — plenty of room, don't draw attention
 *   - 'green'  → 50–74%  — healthy usage, subtle positive indicator
 *   - 'amber'  → 75–89%  — approaching limit, user should be aware
 *   - 'red'    → 90–100% — danger zone, near or at context limit
 *
 * Returns 'muted' for null (unknown) — don't alarm users when we
 * simply don't have the data yet.
 *
 * @param percent - Context fill percentage (0–100), or null if unknown
 * @returns Color band identifier used for CSS class selection
 */
export function fillBarColor(
    percent: number | null,
): "muted" | "green" | "amber" | "red" {
    if (percent === null) return "muted";
    if (percent >= 90) return "red";
    if (percent >= 75) return "amber";
    if (percent >= 50) return "green";
    return "muted";
}