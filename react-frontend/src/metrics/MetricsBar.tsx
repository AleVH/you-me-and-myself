/**
 * MetricsBar — Compact token usage bar for the chat window.
 *
 * ## Layout (Design Doc §6.1)
 *
 * ┌──────────────────────────────────────────────────────────────────────────┐
 * │ gpt-4o  • P: 1.2k  C: 340  T: 1.5k   ⏱ 1.2s  [░░░░░░░░] 42%  $—  ▾  ⤢  │
 * └──────────────────────────────────────────────────────────────────────────┘
 *
 * Elements left to right:
 * 1. Model name — truncated with tooltip for full name
 * 2. •/Σ indicator + P:/C:/T: — token counts (click toggles exchange ↔ session)
 * 3. ⏱ response time — shows wall-clock AI call duration
 * 4. Context fill bar — shows context window usage % (color-coded)
 * 5. $— cost — PLACEHOLDER (Pricing Module — post-launch)
 * 6. ▾ collapse toggle — hides/shows the bar content
 * 7. ⤢ expand button — PLACEHOLDER (Metrics Panel — future)
 *
 * ## Interaction
 *
 * - **Click anywhere on token area**: toggles between "last exchange" and
 *   "session totals" view. Visual indicator shows which view is active.
 * - **▾ toggle**: collapses the bar to a thin strip (saves vertical space).
 *   State is per-component instance (not persisted across tab switches).
 * - **⤢ expand**: placeholder — logs "Metrics Tab not implemented yet".
 *   Will eventually open a full metrics panel with per-model breakdown.
 *
 * ## Data Flow
 *
 * Receives TabMetricsState from ChatApp (via useBridge). The component
 * does NO state management of metrics data — it's purely presentational
 * except for the view toggle and collapse toggle (local UI state only).
 *
 * ## Block 2 Updates
 *
 * - responseTimeMs now flows from Kotlin via UpdateMetricsEvent
 * - contextWindowSize now flows from Kotlin via UpdateMetricsEvent
 * - Placeholder modifiers removed from response time and fill bar
 *   when real data is available (still shown as placeholders when null)
 * - Accumulator field names updated to match new MetricsAccumulator type
 *   (totalPromptTokens, totalCompletionTokens instead of promptTokens, completionTokens)
 *
 * @see types.ts — TabMetricsState, MetricsSnapshot, MetricsAccumulator
 * @see accumulator.ts — formatTokenCount, contextFillPercent, fillBarColor
 * @see useBridge.ts — Provides metricsState to ChatApp
 * @see MetricsBar.css — Styles for this component
 */

import { useState } from "react";
import type { TabMetricsState, MetricsSnapshot, MetricsAccumulator } from "./types";
import {
    formatTokenCount,
    contextFillPercent,
    fillBarColor,
} from "./accumulator";
import "./MetricsBar.css";
import { log } from "../utils/log";

// ═══════════════════════════════════════════════════════════════════════
//  CONSTANTS
// ═══════════════════════════════════════════════════════════════════════

/**
 * Maximum characters to display for the model name before truncating.
 * Full name is always available via hover tooltip.
 */
const MODEL_NAME_MAX_DISPLAY = 18;

// ═══════════════════════════════════════════════════════════════════════
//  PROPS
// ═══════════════════════════════════════════════════════════════════════

export interface MetricsBarProps {
    /** Complete metrics state for the active tab. */
    metricsState: TabMetricsState;
}

// ═══════════════════════════════════════════════════════════════════════
//  COMPONENT
// ═══════════════════════════════════════════════════════════════════════

/**
 * Compact metrics bar displayed at the top of the chat window.
 *
 * Hidden entirely when there's no data (no exchanges yet). Shows a
 * thin collapsed strip when the user toggles collapse. Otherwise
 * shows the full token breakdown with all interactive elements.
 */
export default function MetricsBar({ metricsState }: MetricsBarProps) {
    // ── Local UI State ───────────────────────────────────────────────

    /**
     * Which view is active: "exchange" shows the last exchange,
     * "session" shows the running session totals.
     * Default to "exchange" — most users care about what just happened.
     */
    const [view, setView] = useState<"exchange" | "session">("exchange");

    /**
     * Whether the bar content is collapsed to a thin strip.
     * Not persisted — resets on tab switch or component remount.
     */
    const [collapsed, setCollapsed] = useState(false);

    // ── Early Return: Nothing To Show ────────────────────────────────

    // Show the bar if there's either a last exchange OR historical session data.
    // Historical data (from reopened conversations) has session totals but no
    // lastExchange — we still want to show the accumulated usage.
    if (!metricsState.lastExchange && metricsState.session.exchangeCount === 0) {
        return null;
    }

    // ── Resolve Which Data To Display ────────────────────────────────

    const snapshot: MetricsSnapshot | null = metricsState.lastExchange;
    const session: MetricsAccumulator = metricsState.session;

    // When viewing "exchange", show snapshot data.
    // When viewing "session", show accumulated totals.
    // Force session view when there's no last exchange (reopened conversation
    // with only historical data — no individual exchange to show).
    const hasSnapshot = snapshot !== null;
    const isSessionView = !hasSnapshot || view === "session";

    // Block 2: field names match the new MetricsAccumulator type
    // (totalPromptTokens, totalCompletionTokens instead of promptTokens, completionTokens)
    const displayPrompt = isSessionView ? session.totalPromptTokens : snapshot!.promptTokens;
    const displayCompletion = isSessionView ? session.totalCompletionTokens : snapshot!.completionTokens;
    const displayTotal = isSessionView ? session.totalTokens : snapshot!.totalTokens;

    // Model name: always from the last exchange (session view doesn't have a single model).
    // Session with multiple models shows "N models" instead.
    const modelCount = Object.keys(session.byModel).length;
    const modelDisplay = isSessionView && modelCount > 1
        ? `${modelCount} models`
        : isSessionView && !hasSnapshot && modelCount === 0
            ? "historical"
            : isSessionView && !hasSnapshot
                ? Object.keys(session.byModel)[0] ?? "historical"
                : snapshot!.model ?? "unknown";

    // Model tooltip: for session multi-model, list all models.
    const modelTooltip = isSessionView && modelCount > 1
        ? `Models used:\n${Object.entries(session.byModel)
            .map(([name, bd]) => `  ${name}: ${bd.exchangeCount} exchanges, ${formatTokenCount(bd.totalTokens)} tokens`)
            .join("\n")}`
        : !hasSnapshot
            ? "Historical totals from previous exchanges (no per-model breakdown)"
            : snapshot!.model ?? "Model not reported by provider";

    // Truncate model name for display.
    const modelTruncated = modelDisplay.length > MODEL_NAME_MAX_DISPLAY
        ? modelDisplay.slice(0, MODEL_NAME_MAX_DISPLAY - 1) + "…"
        : modelDisplay;

    // Context fill — only meaningful for "exchange" view (uses snapshot's context window).
    // Session view doesn't have a single context window size.
    const fillPct = isSessionView || !hasSnapshot
        ? null
        : contextFillPercent(snapshot!.totalTokens, snapshot!.contextWindowSize);
    const fillColor = fillBarColor(fillPct);

    // Response time — show placeholder modifier only when data isn't available
    const hasResponseTime = hasSnapshot && snapshot!.responseTimeMs !== null;

    // Fill bar — show placeholder modifier only when contextWindowSize isn't available
    const hasFillData = fillPct !== null;

    // View toggle tooltip.
    const viewToggleTooltip = isSessionView
        ? `Session totals (${session.exchangeCount} exchanges). Click for last exchange.`
        : "Last exchange. Click for session totals.";

    // ── Collapsed View ───────────────────────────────────────────────

    if (collapsed) {
        return (
            <div
                className="ymm-metrics-bar ymm-metrics-bar--collapsed"
                title="Click to expand metrics bar"
            >
                <button
                    className="ymm-metrics-bar__toggle"
                    onClick={() => setCollapsed(false)}
                    title="Expand metrics bar"
                    aria-label="Expand metrics bar"
                >
                    ▸
                </button>
                <span className="ymm-metrics-bar__collapsed-summary">
                    T: {formatTokenCount(displayTotal)}
                </span>
            </div>
        );
    }

    // ── Full View ────────────────────────────────────────────────────

    return (
        <div className="ymm-metrics-bar">
            {/* Model name — truncated, hover for full */}
            <span
                className="ymm-metrics-bar__model"
                title={modelTooltip}
            >
                {modelTruncated}
            </span>

            {/* Token counts — clickable to toggle exchange/session view */}
            <button
                className="ymm-metrics-bar__tokens"
                onClick={() => {
                    if (hasSnapshot) setView(isSessionView ? "exchange" : "session");
                }}
                title={hasSnapshot ? viewToggleTooltip : "Session totals from conversation history. Send a message to see per-exchange metrics."}
                aria-label={hasSnapshot ? viewToggleTooltip : "Session totals from conversation history"}
                style={!hasSnapshot ? { cursor: "default" } : undefined}
            >
                {/* View indicator: dot or sigma symbol */}
                <span className="ymm-metrics-bar__view-indicator">
                    {isSessionView ? "Σ" : "•"}
                </span>

                <span className="ymm-metrics-bar__item" title="Prompt tokens">
                    P: {formatTokenCount(displayPrompt)}
                </span>

                <span className="ymm-metrics-bar__item" title="Completion tokens">
                    C: {formatTokenCount(displayCompletion)}
                </span>

                <span
                    className="ymm-metrics-bar__item ymm-metrics-bar__item--total"
                    title="Total tokens"
                >
                    T: {formatTokenCount(displayTotal)}
                </span>

                {/* Exchange count badge in session view */}
                {isSessionView && (
                    <span
                        className="ymm-metrics-bar__exchange-count"
                        title={`${session.exchangeCount} exchanges in this session`}
                    >
                        ×{session.exchangeCount}
                    </span>
                )}
            </button>

            {/* Response time — shows real data when available, placeholder when not */}
            <span
                className={`ymm-metrics-bar__response-time${
                    !hasResponseTime ? " ymm-metrics-bar__response-time--placeholder" : ""
                }`}
                title={
                    hasResponseTime
                        ? `Response time: ${(snapshot!.responseTimeMs! / 1000).toFixed(1)}s`
                        : "Response time: waiting for data"
                }
            >
                ⏱ {hasResponseTime
                ? `${(snapshot!.responseTimeMs! / 1000).toFixed(1)}s`
                : "—"}
            </span>

            {/* Context fill bar — shows real data when available, placeholder when not */}
            <div
                className={`ymm-metrics-bar__fill-wrapper${
                    !hasFillData ? " ymm-metrics-bar__fill-wrapper--placeholder" : ""
                }`}
                title={
                    hasFillData
                        ? `Context window: ${fillPct!.toFixed(1)}% used`
                        : "Context fill: waiting for context window size data"
                }
            >
                <div className="ymm-metrics-bar__fill-track">
                    <div
                        className={`ymm-metrics-bar__fill-bar ymm-metrics-bar__fill-bar--${fillColor}`}
                        style={{ width: hasFillData ? `${Math.min(fillPct!, 100)}%` : "0%" }}
                    />
                </div>
                <span className="ymm-metrics-bar__fill-label">
                    {hasFillData ? `${fillPct!.toFixed(0)}%` : "—"}
                </span>
            </div>

            {/*
              * PLACEHOLDER: Cost display (Pricing Module — post-launch).
              *
              * The Pricing Module converts tokens → cost using time-bucketed
              * rates and user-defined currencies. This placeholder reminds
              * you it's planned. NOT part of launch scope.
              */}
            <span
                className="ymm-metrics-bar__cost ymm-metrics-bar__cost--placeholder"
                title="Cost: not implemented yet (Pricing Module — post-launch)"
            >
                $—
            </span>

            {/* Collapse toggle */}
            <button
                className="ymm-metrics-bar__toggle"
                onClick={() => setCollapsed(true)}
                title="Collapse metrics bar"
                aria-label="Collapse metrics bar"
            >
                ▾
            </button>

            {/*
              * PLACEHOLDER: Expand to full metrics panel.
              *
              * Will open a dedicated panel/tab with:
              * - Per-model breakdown table (data already accumulated in session.byModel)
              * - Historical usage charts (requires reading from SQLite)
              * - Cost estimates (requires Pricing Module — post-launch)
              * - Response time trends
              */}
            <button
                className="ymm-metrics-bar__expand ymm-metrics-bar__expand--placeholder"
                onClick={() => {
                    log.warn("MetricsBar", "Metrics Tab not implemented yet — " +
                        "will show per-model breakdown, historical usage, and cost estimates (Pricing Module)");
                }}
                title="Open metrics panel (not implemented yet)"
                aria-label="Open metrics panel (not implemented yet)"
            >
                ⤢
            </button>
        </div>
    );
}