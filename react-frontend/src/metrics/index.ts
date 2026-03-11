/**
 * Metrics Module — Public API
 *
 * This barrel file defines the module boundary. Everything imported
 * from "../metrics" in the rest of the codebase goes through here.
 *
 * ## What's Exported
 *
 * **Types** (used by useBridge.ts, ChatApp.tsx, MetricsBar.tsx):
 * - MetricsSnapshot — one exchange's token metrics
 * - MetricsAccumulator — session running totals
 * - TabMetricsState — per-tab container (lastExchange + session)
 * - ModelBreakdown — per-model subtotals within the accumulator
 * - FillBarColor — color type for the context fill bar
 *
 * **Functions** (used by useBridge.ts for state management):
 * - createAccumulator() — zero-state for new/cleared tabs
 * - accumulate() — add one exchange to running totals
 *
 * **Display Functions** (used by MetricsBar.tsx):
 * - formatTokenCount() — human-friendly token display
 * - contextFillPercent() — fill bar percentage calculation
 * - fillBarColor() — color threshold determination
 *
 * **Components** (used by ChatApp.tsx):
 * - MetricsBar — compact per-tab metrics display (default export)
 * - MetricsDashboard — expanded analytics view (placeholder)
 *
 * ## Module Boundary Rules
 *
 * - Other modules import from "../metrics" (this file), never from
 *   "../metrics/accumulator" or "../metrics/types" directly.
 * - This keeps internal file structure changeable without breaking imports.
 * - The only exception is MetricsBar.css which is imported by MetricsBar.tsx
 *   internally (CSS modules are component-scoped).
 *
 * @see types.ts — type definitions
 * @see accumulator.ts — pure functions
 * @see MetricsBar.tsx — compact bar component
 * @see MetricsDashboard.tsx — expanded dashboard (placeholder)
 */

// ── Types ────────────────────────────────────────────────────────────
export type {
    MetricsSnapshot,
    MetricsAccumulator,
    TabMetricsState,
    ModelBreakdown,
} from "./types";

// ── Accumulator Functions ────────────────────────────────────────────
export {
    createAccumulator,
    accumulate,
    formatTokenCount,
    contextFillPercent,
    fillBarColor,
} from "./accumulator";

export type { FillBarColor } from "./accumulator";

// ── Components ───────────────────────────────────────────────────────
export { default as MetricsBar } from "./MetricsBar";
export { default as MetricsDashboard } from "./MetricsDashboard";