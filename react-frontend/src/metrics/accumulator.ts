/**
 * Metrics Module — Accumulator & Formatting Functions
 *
 * Pure functions for metrics logic. No side effects, no state, no React
 * dependencies. These are the building blocks used by useBridge.ts
 * (state management) and MetricsBar.tsx (display).
 *
 * ## Functions
 *
 * - createAccumulator() — zero-state accumulator for new tabs
 * - accumulate() — adds one snapshot to running totals (immutable)
 * - formatTokenCount() — human-friendly token display (1.2k, 1.2M)
 * - contextFillPercent() — fill bar percentage (or null)
 * - fillBarColor() — color threshold for the fill bar
 *
 * ## Null Safety
 *
 * All functions handle null inputs gracefully. Null token counts are
 * skipped in accumulation. Null in formatting returns "–".
 * Null in fill calculations returns null (bar hidden).
 *
 * @see types.ts — Type definitions these functions operate on
 * @see MetricsBar.tsx — UI that calls these functions
 * @see useBridge.ts — Hook that calls createAccumulator() and accumulate()
 */

import type { MetricsAccumulator, MetricsSnapshot, ModelBreakdown } from "./types";

// ═══════════════════════════════════════════════════════════════════════
//  ACCUMULATOR LIFECYCLE
// ═══════════════════════════════════════════════════════════════════════

/**
 * Create a zero-state accumulator for a new or cleared tab.
 *
 * Used in:
 * - createTab() — fresh tabs start with empty metrics
 * - CONVERSATION_CLEARED handler — reset after clear chat
 * - TAB_STATE handler — restored tabs before history seeding
 *
 * @returns A fresh MetricsAccumulator with all counters at zero
 */
export function createAccumulator(): MetricsAccumulator {
    return {
        totalPromptTokens: 0,
        totalCompletionTokens: 0,
        totalTokens: 0,
        exchangeCount: 0,
        byModel: {},
    };
}

/**
 * Add one exchange's metrics to the running session totals.
 *
 * Returns a new accumulator (immutable update for React state).
 * Null token values in the snapshot are skipped — only non-null
 * values contribute to the sums.
 *
 * The byModel breakdown groups exchanges by model name. Exchanges
 * with a null model are grouped under "__unknown__".
 *
 * Used in two places:
 * 1. UPDATE_METRICS handler — each live exchange
 * 2. CONVERSATION_HISTORY handler — seeding from historical data
 *
 * @param acc The current accumulator state
 * @param snapshot The new exchange's metrics to add
 * @returns A new accumulator with updated totals
 */
export function accumulate(
    acc: MetricsAccumulator,
    snapshot: MetricsSnapshot
): MetricsAccumulator {
    // Resolve the model key for the byModel breakdown.
    // Null models are grouped under "__unknown__" so they're still
    // counted and visible in per-model charts (as "Unknown model").
    const modelKey = snapshot.model ?? "__unknown__";

    // Get existing breakdown for this model, or create a fresh one.
    const existing: ModelBreakdown = acc.byModel[modelKey] ?? {
        promptTokens: 0,
        completionTokens: 0,
        totalTokens: 0,
        exchangeCount: 0,
    };

    // Update the per-model breakdown.
    const updatedModelEntry: ModelBreakdown = {
        promptTokens: existing.promptTokens + (snapshot.promptTokens ?? 0),
        completionTokens: existing.completionTokens + (snapshot.completionTokens ?? 0),
        totalTokens: existing.totalTokens + (snapshot.totalTokens ?? 0),
        exchangeCount: existing.exchangeCount + 1,
    };

    return {
        totalPromptTokens: acc.totalPromptTokens + (snapshot.promptTokens ?? 0),
        totalCompletionTokens: acc.totalCompletionTokens + (snapshot.completionTokens ?? 0),
        totalTokens: acc.totalTokens + (snapshot.totalTokens ?? 0),
        exchangeCount: acc.exchangeCount + 1,
        byModel: {
            ...acc.byModel,
            [modelKey]: updatedModelEntry,
        },
    };
}

// ═══════════════════════════════════════════════════════════════════════
//  FORMATTING
// ═══════════════════════════════════════════════════════════════════════

/**
 * Format a token count for human display.
 *
 * Examples:
 * - null → "–"
 * - 0 → "0"
 * - 847 → "847"
 * - 1234 → "1.2k"
 * - 12345 → "12.3k"
 * - 123456 → "123k"
 * - 1234567 → "1.2M"
 *
 * Uses one decimal place for k/M to balance precision and readability.
 * The MetricsBar is a compact ~20px strip — every character matters.
 *
 * @param count Token count, or null if unavailable
 * @returns Formatted string for display
 */
export function formatTokenCount(count: number | null): string {
    if (count === null || count === undefined) return "–";
    if (count === 0) return "0";

    const abs = Math.abs(count);
    const sign = count < 0 ? "-" : "";

    if (abs < 1_000) {
        return `${sign}${abs}`;
    }
    if (abs < 1_000_000) {
        const k = abs / 1_000;
        // Show one decimal for < 100k, no decimal for >= 100k
        return abs < 100_000
            ? `${sign}${k.toFixed(1)}k`
            : `${sign}${Math.round(k)}k`;
    }

    const m = abs / 1_000_000;
    return `${sign}${m.toFixed(1)}M`;
}

// ═══════════════════════════════════════════════════════════════════════
//  CONTEXT FILL BAR
// ═══════════════════════════════════════════════════════════════════════

/**
 * Fill bar color thresholds.
 *
 * These match the design doc §6.1:
 * - muted (gray): < 50% — not distracting, context is plentiful
 * - green: 50–74% — context is being used well
 * - amber: 75–89% — approaching the limit, user should be aware
 * - red: ≥ 90% — near capacity, responses may be truncated
 */
export type FillBarColor = "muted" | "green" | "amber" | "red";

/**
 * Compute the context window fill percentage.
 *
 * @param totalTokens Total tokens from the last exchange (or session total)
 * @param windowSize Model's maximum context window in tokens
 * @returns Fill percentage (0-100+), or null if either input is null/zero.
 *          Can exceed 100% if the provider reports more tokens than the
 *          configured window (happens with some providers).
 */
export function contextFillPercent(
    totalTokens: number | null,
    windowSize: number | null
): number | null {
    if (totalTokens === null || totalTokens === undefined) return null;
    if (windowSize === null || windowSize === undefined || windowSize <= 0) return null;

    return (totalTokens / windowSize) * 100;
}

/**
 * Determine the fill bar color based on the fill percentage.
 *
 * Thresholds from §6.1:
 * - < 50%  → muted (gray) — context is plentiful
 * - 50–74% → green — healthy usage
 * - 75–89% → amber — approaching limit
 * - ≥ 90%  → red — near capacity
 *
 * @param percent Fill percentage from contextFillPercent(), or null
 * @returns Color name for CSS class selection, or "muted" if null
 */
export function fillBarColor(percent: number | null): FillBarColor {
    if (percent === null) return "muted";
    if (percent < 50) return "muted";
    if (percent < 75) return "green";
    if (percent < 90) return "amber";
    return "red";
}