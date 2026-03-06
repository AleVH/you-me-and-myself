/**
 * Metrics Module — Type definitions.
 *
 * ## Purpose
 *
 * Defines the data shapes for per-exchange snapshots, session accumulators,
 * and per-tab metrics state. These types are consumed by useBridge (state
 * management), MetricsBar (display), and the accumulator (pure functions).
 *
 * ## Architecture Context
 *
 * This is the Metrics Module — one of three planned usage tracking modules:
 *   1. **Metrics** (this module) — measures tokens. Launch scope.
 *   2. **Pricing** — converts tokens to cost via time-bucketed rates. Post-launch.
 *   3. **Budget** — enforces spending limits. Post-launch.
 *
 * The Metrics Module intentionally has NO cost/price fields. Cost belongs
 * to the Pricing Module. The old `estimatedCost` field on UpdateMetricsEvent
 * is deprecated and ignored.
 *
 * ## Kotlin Sync
 *
 * MetricsSnapshot maps to UpdateMetricsEvent from the bridge. Fields not
 * yet sent by Kotlin (contextWindowSize, responseTimeMs) are nullable and
 * display as "—" in the UI until the Kotlin side is enhanced.
 *
 * @see accumulator.ts — Pure functions operating on these types
 * @see MetricsBar.tsx — Display component consuming TabMetricsState
 * @see useBridge.ts — State management wiring
 * @see METRICS_MODULE_DESIGN.md — Full design document
 */

// ═══════════════════════════════════════════════════════════════════════
//  SINGLE EXCHANGE SNAPSHOT
// ═══════════════════════════════════════════════════════════════════════

/**
 * Token usage data from a single AI exchange (one prompt + response).
 *
 * Created from each UPDATE_METRICS event received from the Kotlin bridge.
 * Stored as `lastExchange` in TabMetricsState so the MetricsBar can show
 * "this exchange" vs "session totals".
 *
 * ## Nullable Fields
 *
 * - model: null if the provider didn't report it (shouldn't happen, but defensive)
 * - promptTokens/completionTokens/totalTokens: null if provider didn't return usage
 * - contextWindowSize: null until Kotlin sends it (requires provider config lookup)
 * - responseTimeMs: null until Kotlin sends it (requires timing instrumentation)
 *
 * All nullable fields render as "—" in the MetricsBar.
 */
export interface MetricsSnapshot {
    /** Model identifier as reported by the provider (e.g., "gpt-4o", "gemini-1.5-pro"). */
    model: string | null;

    /** Tokens in the prompt (user message + context + system prompt). */
    promptTokens: number | null;

    /** Tokens in the AI response. */
    completionTokens: number | null;

    /** Total tokens (prompt + completion). Some providers report this independently. */
    totalTokens: number | null;

    /**
     * Maximum context window for the model, in tokens.
     *
     * NOT YET SENT BY KOTLIN — always null until the Kotlin bridge
     * includes model context limits from provider configuration.
     * Used to compute the context fill percentage bar.
     */
    contextWindowSize: number | null;

    /**
     * Wall-clock time for the AI response, in milliseconds.
     *
     * NOT YET SENT BY KOTLIN — always null until the Kotlin bridge
     * includes response timing instrumentation.
     * Planned for the metrics panel expanded view.
     */
    responseTimeMs: number | null;
}

// ═══════════════════════════════════════════════════════════════════════
//  PER-MODEL BREAKDOWN
// ═══════════════════════════════════════════════════════════════════════

/**
 * Accumulated token counts for a single model within a session.
 *
 * When a tab uses multiple models (e.g., user switches provider mid-conversation),
 * each model gets its own breakdown. The session-level totals are the sum of
 * all model breakdowns.
 *
 * exchangeCount tracks how many exchanges used this model — useful for
 * average-per-exchange calculations in the future metrics panel.
 */
export interface ModelBreakdown {
    /** Number of exchanges that used this model in this session. */
    exchangeCount: number;

    /** Cumulative prompt tokens for this model. */
    promptTokens: number;

    /** Cumulative completion tokens for this model. */
    completionTokens: number;

    /** Cumulative total tokens for this model. */
    totalTokens: number;
}

// ═══════════════════════════════════════════════════════════════════════
//  SESSION ACCUMULATOR
// ═══════════════════════════════════════════════════════════════════════

/**
 * Running session totals for a single tab.
 *
 * "Session" = the lifetime of the tab in this IDE session. Resets when
 * the tab is closed or the IDE restarts. NOT persisted — the Metrics
 * Module only tracks live session data. Historical per-conversation
 * totals come from SQLite (chat_exchanges table) when needed.
 *
 * ## Why byModel?
 *
 * Users can switch providers mid-conversation (per-tab provider feature).
 * Without per-model tracking, switching from GPT-4 to Claude mid-chat
 * would produce confusing aggregate numbers. The byModel map lets the
 * expanded metrics panel show "GPT-4: 5k tokens (3 exchanges), Claude:
 * 2k tokens (1 exchange)" — clear and honest.
 */
export interface MetricsAccumulator {
    /** Total number of exchanges in this session across all models. */
    exchangeCount: number;

    /** Cumulative prompt tokens across all models. */
    promptTokens: number;

    /** Cumulative completion tokens across all models. */
    completionTokens: number;

    /** Cumulative total tokens across all models. */
    totalTokens: number;

    /**
     * Per-model breakdown of token usage.
     *
     * Key: model name as reported by the provider (e.g., "gpt-4o").
     * Exchanges with null model are grouped under the key "unknown".
     */
    byModel: Record<string, ModelBreakdown>;
}

// ═══════════════════════════════════════════════════════════════════════
//  PER-TAB METRICS STATE
// ═══════════════════════════════════════════════════════════════════════

/**
 * Complete metrics state for a single conversation tab.
 *
 * This is what TabData holds (replacing the old `metrics: MetricsData | null`).
 * The MetricsBar component consumes this to render both "last exchange"
 * and "session totals" views.
 *
 * ## Lifecycle
 *
 * 1. Tab created → metricsState initialized with null lastExchange + empty accumulator
 * 2. Each UPDATE_METRICS event → snapshot stored as lastExchange, accumulated into session
 * 3. Tab closed → metricsState discarded (not persisted)
 * 4. Tab restored from open_tabs → metricsState re-initialized (no historical data)
 *
 * ## Why Not Just MetricsSnapshot?
 *
 * The old MetricsData was a single snapshot that got overwritten on each exchange.
 * Session totals were lost. TabMetricsState preserves both the latest exchange
 * (for "what just happened") and the session running total (for "how much have
 * I used in this conversation").
 */
export interface TabMetricsState {
    /**
     * Most recent exchange metrics, or null if no exchanges yet.
     *
     * Updated on every UPDATE_METRICS event. The MetricsBar defaults to
     * showing this (the "last exchange" view).
     */
    lastExchange: MetricsSnapshot | null;

    /**
     * Running session totals.
     *
     * Always initialized (never null) — starts as an empty accumulator.
     * Each UPDATE_METRICS event adds to these totals.
     */
    session: MetricsAccumulator;
}