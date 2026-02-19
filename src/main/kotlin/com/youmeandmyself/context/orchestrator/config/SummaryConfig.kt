// File: src/main/kotlin/com/youmeandmyself/context/orchestrator/config/SummaryConfig.kt
package com.youmeandmyself.context.orchestrator.config

import java.time.Instant

/**
 * Data classes and enums for the summary configuration system.
 *
 * ## Why This File Exists
 *
 * Centralizes all summary config types in one place. These are used by:
 * - SummaryConfigService (reads/writes config)
 * - SummaryStore (checks before summarizing)
 * - SummaryConfigurable (settings UI)
 * - SummaryCommandHandler (slash commands)
 * - SummaryQueue (queue management)
 *
 * ## Design Principle: Record Every Dimension, Aggregate Later
 *
 * Token tracking stores ALL dimensions (API key, profile, model, purpose) on every exchange.
 * Display aggregation is a separate concern — we show by API key today, but can pivot
 * to any grouping (per-model, per-purpose, per-profile) without schema changes.
 */

// ==================== Configuration ====================

/**
 * Mirrors the summary_config table in SQLite.
 * One config per project.
 *
 * ## Defaults
 *
 * - mode = OFF → never burn tokens without explicit user consent
 * - enabled = false → kill switch starts in safe position
 * - dryRun = true → plan-only mode, no API calls (safe for development/testing)
 * - All budget fields nullable → null means unlimited/unset
 *
 * ## Thread Safety
 *
 * This is a plain data class. SummaryConfigService handles synchronization
 * when reading/writing. Don't share mutable instances across threads.
 *
 * @param mode The summarization mode (OFF, ON_DEMAND, SUMMARIZE_PATH, SMART_BACKGROUND)
 * @param enabled Kill switch. When false, ALL summarization stops immediately regardless of mode.
 * @param maxTokensPerSession Budget cap per project session. Null = unlimited.
 * @param tokensUsedSession Running counter of tokens spent on summaries this session. Resets on project open.
 * @param complexityThreshold Minimum complexity score (1-10) for auto-summarization. Null = no threshold.
 * @param includePatterns Glob patterns for files to include. Empty = include all.
 * @param excludePatterns Glob patterns for files to exclude. Empty = exclude none.
 * @param minFileLines Skip files shorter than this. Null = no minimum.
 * @param dryRun When true, the system evaluates everything but skips the actual API call.
 */
data class SummaryConfig(
    val mode: SummaryMode = SummaryMode.OFF,
    val enabled: Boolean = false,
    val maxTokensPerSession: Int? = null,
    val tokensUsedSession: Int = 0,
    val complexityThreshold: Int? = null,
    val includePatterns: List<String> = emptyList(),
    val excludePatterns: List<String> = emptyList(),
    val minFileLines: Int? = null,
    val dryRun: Boolean = true
) {

    /**
     * Quick check: is summarization actually active?
     * Both the kill switch AND a non-OFF mode must be set.
     */
    val isActive: Boolean
        get() = enabled && mode != SummaryMode.OFF

    /**
     * Is there budget remaining for this session?
     * Returns true if no cap is set OR if we haven't exceeded the cap.
     */
    val hasBudget: Boolean
        get() = maxTokensPerSession == null || tokensUsedSession < maxTokensPerSession

    /**
     * How many tokens remain before hitting the budget cap.
     * Null if no cap is set (unlimited).
     */
    val remainingBudget: Int?
        get() = maxTokensPerSession?.let { (it - tokensUsedSession).coerceAtLeast(0) }
}

// ==================== Modes ====================

/**
 * Summarization modes, ordered from least to most token-consuming.
 *
 * ## Extensibility
 *
 * New modes can be added without breaking existing code. The enum is evaluated
 * with `when` blocks that always have an `else` branch defaulting to OFF behavior.
 * SUMMARIZE_PATH is defined now but its logic is implemented in Phase 5.
 *
 * @property displayName User-facing label for the settings UI
 * @property description Tooltip/help text explaining the mode
 * @property tokenRisk Brief indicator of token cost risk
 */
enum class SummaryMode(
    val displayName: String,
    val description: String,
    val tokenRisk: String
) {
    /**
     * Nothing happens. No summaries generated. Zero token cost.
     * This is the default — user must explicitly opt in.
     */
    OFF(
        displayName = "Off",
        description = "No summarization. Zero token cost.",
        tokenRisk = "None"
    ),

    /**
     * Summaries only generated when user explicitly requests them
     * via slash commands (/summarize) or context menu.
     * Token cost is fully user-controlled.
     */
    ON_DEMAND(
        displayName = "On Demand",
        description = "Only when you explicitly request a summary. You control every token spent.",
        tokenRisk = "User-controlled"
    ),

    /**
     * User picks a process/flow + complexity threshold.
     * Only code related to that path above the threshold gets summarized.
     * Targeted and bounded.
     *
     * NOTE: Logic implemented in Phase 5 (requires code element detection
     * and hierarchical summarization). The enum value exists now for
     * forward compatibility — selecting this mode in Phase 3 will show
     * a "coming soon" message in the UI.
     */
    SUMMARIZE_PATH(
        displayName = "Summarize Path",
        description = "Targeted: pick a code flow and complexity level. Only related code gets summarized. (Coming in a future update)",
        tokenRisk = "Bounded, targeted"
    ),

    /**
     * Background summarization using heuristics, budget caps, and scope limits.
     * The system decides what to summarize based on file importance, recency,
     * and user-defined constraints. Token cost is bounded by configuration.
     */
    SMART_BACKGROUND(
        displayName = "Smart Background",
        description = "Auto-summarizes important files within your budget and scope limits.",
        tokenRisk = "Bounded by config"
    );

    companion object {
        /**
         * Safe parse from string (e.g., from SQLite).
         * Returns OFF for any unrecognized value — fail-safe.
         */
        fun fromString(value: String): SummaryMode {
            return entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: OFF
        }
    }
}

// ==================== Scope Evaluation Results ====================

/**
 * Result of evaluating whether a file should be summarized.
 *
 * Used by SummaryConfigService.shouldSummarize() to explain its decision.
 * The reason string is human-readable for both UI display and logging.
 *
 * @param allowed Whether summarization should proceed
 * @param reason Human-readable explanation of the decision
 * @param matchedPattern The specific pattern that matched (if applicable)
 */
data class ScopeDecision(
    val allowed: Boolean,
    val reason: String,
    val matchedPattern: String? = null
) {
    companion object {
        // ── Factory methods for common decisions ──
        // These keep decision-making code clean and consistent.

        fun allowed(reason: String) = ScopeDecision(true, reason)
        fun denied(reason: String) = ScopeDecision(false, reason)
        fun deniedByPattern(pattern: String) = ScopeDecision(false, "Excluded by pattern: $pattern", pattern)
        fun deniedByBudget(used: Int, max: Int) = ScopeDecision(false, "Budget exhausted: $used / $max tokens used")
        fun deniedByMinLines(lines: Int, min: Int) = ScopeDecision(false, "File too short: $lines lines (minimum: $min)")
        fun deniedByComplexity(score: Int, threshold: Int) = ScopeDecision(false, "Below complexity threshold: $score < $threshold")
        fun deniedByKillSwitch() = ScopeDecision(false, "Summarization is disabled (kill switch)")
        fun deniedByMode(mode: SummaryMode) = ScopeDecision(false, "Current mode ($mode) does not allow this operation")
        fun deniedByDryRun() = ScopeDecision(false, "Dry-run mode: would summarize, but skipping API call")
    }
}

// ==================== Dry Run ====================

/**
 * Result of a dry-run evaluation.
 *
 * Tells the user exactly what WOULD happen if dry-run were disabled.
 * Used by the status panel and /dev-summary-test command.
 *
 * @param wouldSummarize Whether the file would be summarized with current config
 * @param reason Human-readable explanation of the decision chain
 * @param estimatedTokens Rough token estimate for the summary request (null if can't estimate)
 * @param budgetRemaining Tokens remaining in session budget (null if unlimited)
 * @param matchedPattern The include/exclude pattern that matched (if any)
 * @param providerInfo Which provider/model would be used (for transparency)
 */
data class DryRunResult(
    val wouldSummarize: Boolean,
    val reason: String,
    val estimatedTokens: Int? = null,
    val budgetRemaining: Int? = null,
    val matchedPattern: String? = null,
    val providerInfo: String? = null
)

// ==================== Queue ====================

/**
 * A request to summarize a file, held in the summary queue.
 *
 * ## Priority
 *
 * Lower number = higher priority. Default is 0 (normal).
 * Negative values for user-requested summaries (ON_DEMAND), positive for background.
 * This ensures user-triggered summaries always run first.
 *
 * @param filePath Absolute file path to summarize
 * @param languageId Programming language (e.g., "kotlin", "java") for prompt customization
 * @param contentHash Hash of file content at time of enqueue (for staleness check)
 * @param priority Queue priority. Lower = higher priority. Negative for user-requested.
 * @param enqueuedAt When this request was created
 * @param estimatedTokens Rough token estimate (null if unknown)
 * @param triggeredBy What caused this request (for logging/transparency)
 */
data class SummaryRequest(
    val filePath: String,
    val languageId: String?,
    val contentHash: String?,
    val priority: Int = 0,
    val enqueuedAt: Instant = Instant.now(),
    val estimatedTokens: Int? = null,
    val triggeredBy: SummaryTrigger = SummaryTrigger.BACKGROUND
)

/**
 * What triggered a summary request.
 * Useful for logging, UI display, and priority assignment.
 */
enum class SummaryTrigger {
    /** User explicitly requested via /summarize command or context menu */
    USER_REQUEST,

    /** Smart background mode detected the file should be summarized */
    BACKGROUND,

    /** File changed and was re-queued for refresh */
    STALENESS_REFRESH,

    /** Warm-up on project open */
    WARMUP
}

// ==================== Token Tracking ====================

/**
 * A snapshot of token usage, aggregated by whatever dimensions the caller needs.
 *
 * ## Why This Exists
 *
 * We store tokens per-exchange with all dimensions (profile, model, key, purpose).
 * This class is for DISPLAY purposes — pre-aggregated snapshots for the UI.
 * The caller decides the grouping; this just holds the numbers.
 *
 * @param profileId The profile these stats belong to (or "all" for totals)
 * @param profileDisplayName Human-readable profile name
 * @param apiKeyHint Last few chars of the API key (e.g., "...a3f") for identification
 * @param chatTokens Tokens spent on chat exchanges
 * @param summaryTokens Tokens spent on summary exchanges
 * @param totalTokens Total tokens (chat + summary + any future purposes)
 */
data class TokenUsageSnapshot(
    val profileId: String,
    val profileDisplayName: String,
    val apiKeyHint: String? = null,
    val chatTokens: Int = 0,
    val summaryTokens: Int = 0,
    val totalTokens: Int = chatTokens + summaryTokens
)

// ==================== Listener ====================

/**
 * Listener for config changes.
 *
 * Allows SummaryStore, VfsSummaryWatcher, and UI components to react
 * when the user changes summary settings.
 */
fun interface ConfigChangeListener {
    /**
     * Called when summary configuration changes.
     * @param oldConfig The previous configuration
     * @param newConfig The new configuration
     */
    fun onConfigChanged(oldConfig: SummaryConfig, newConfig: SummaryConfig)
}