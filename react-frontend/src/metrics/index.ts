/**
 * Metrics Module — Public API.
 *
 * Barrel file exporting everything consumers need from the metrics module.
 * Import from here rather than reaching into individual files:
 *
 *   import { TabMetricsState, createAccumulator, accumulate } from "../metrics";
 *   import MetricsBar from "../metrics/MetricsBar";
 *
 * Note: MetricsBar is a default export from its own file (React convention),
 * so it's re-exported as a named export here for flexibility. Either import
 * style works.
 *
 * @see types.ts — Type definitions
 * @see accumulator.ts — Pure functions
 * @see MetricsBar.tsx — Display component
 */

// ── Types ────────────────────────────────────────────────────────────
export type {
    MetricsSnapshot,
    ModelBreakdown,
    MetricsAccumulator,
    TabMetricsState,
} from "./types";

// ── Functions ────────────────────────────────────────────────────────
export {
    createAccumulator,
    accumulate,
    formatTokenCount,
    contextFillPercent,
    fillBarColor,
} from "./accumulator";

// ── Components ───────────────────────────────────────────────────────
export { default as MetricsBar } from "./MetricsBar";
export type { MetricsBarProps } from "./MetricsBar";