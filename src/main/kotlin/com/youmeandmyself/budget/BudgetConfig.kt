package com.youmeandmyself.budget

/**
 * Configuration for budget enforcement thresholds.
 *
 * ## Why All Fields Are Nullable
 *
 * At launch (Individual Basic), no caps are enforced — all fields are null.
 * This data class exists so that:
 *
 * 1. The settings UI can be built against a real type (not a Map or JSON blob)
 * 2. The real [BudgetChecker] implementation can read thresholds without
 *    needing a schema migration — the fields are already here
 * 3. Serialization (to/from project settings XML) works out of the box
 *    with nullable fields defaulting to null
 *
 * ## Cap Hierarchy (from METRICS_MODULE_DESIGN.md §13.4)
 *
 * Caps are checked in order from most specific to least specific:
 *
 * ```
 * Purpose-specific caps (chatTokenCap, summaryTokenCap)
 *   ↓ if not set, fall through to
 * Time-window caps (sessionTokenCap → dailyTokenCap → monthlyTokenCap)
 *   ↓ if not set, fall through to
 * No cap (unlimited)
 * ```
 *
 * A call is blocked if ANY applicable cap is exceeded.
 *
 * ## Units
 *
 * All token caps are in raw token counts (not thousands, not cost).
 * Cost-based budgeting depends on the Pricing Module (post-launch)
 * and will get its own config fields when that ships.
 *
 * @property sessionTokenCap Maximum tokens per IDE session (from plugin load to IDE close).
 *   Resets when the IDE restarts. Useful for developers who want to limit
 *   "accidental" AI usage during a single work session.
 *   Null = no session cap.
 *
 * @property dailyTokenCap Maximum tokens per calendar day (midnight-to-midnight, local time).
 *   Null = no daily cap.
 *
 * @property monthlyTokenCap Maximum tokens per calendar month.
 *   Null = no monthly cap.
 *
 * @property warningThresholdPct Percentage (0–100) at which a warning is triggered
 *   BEFORE the hard cap is reached. For example, 80 means "warn at 80% usage".
 *   Applies to all caps uniformly. Null = no warnings (only hard blocks).
 *   Default suggestion for UI: 80.
 *
 * @property hardBlock Whether exceeding a cap should hard-block the AI call (true)
 *   or just warn and allow the user to proceed (false, "soft cap" mode).
 *   Null = defaults to true (hard block) in the real implementation.
 *   Soft cap mode is useful for teams that want visibility without enforcement.
 *
 * @property chatTokenCap Purpose-specific cap for CHAT exchanges only.
 *   Checked in addition to the time-window caps — whichever is lower wins.
 *   Null = no chat-specific cap (only time-window caps apply).
 *
 * @property summaryTokenCap Purpose-specific cap for all summarization exchanges
 *   (FILE_SUMMARY, CLASS_SUMMARY, METHOD_SUMMARY, MODULE_SUMMARY, PROJECT_SUMMARY).
 *   Null = no summary-specific cap.
 */
data class BudgetConfig(
    val sessionTokenCap: Long? = null,
    val dailyTokenCap: Long? = null,
    val monthlyTokenCap: Long? = null,
    val warningThresholdPct: Int? = null,
    val hardBlock: Boolean? = null,
    val chatTokenCap: Long? = null,
    val summaryTokenCap: Long? = null
) {
    companion object {
        /**
         * Default configuration: no caps, no warnings, no enforcement.
         *
         * This is what Individual Basic tier uses at launch.
         * The real settings UI will let users populate these fields.
         */
        val NONE = BudgetConfig()

        /**
         * Check if any cap is configured (i.e., at least one field is non-null).
         *
         * Useful for the real [BudgetChecker] to short-circuit: if no caps
         * are configured, skip all the aggregation queries and return allowed.
         */
        fun BudgetConfig.hasAnyCap(): Boolean =
            sessionTokenCap != null ||
                    dailyTokenCap != null ||
                    monthlyTokenCap != null ||
                    chatTokenCap != null ||
                    summaryTokenCap != null
    }
}