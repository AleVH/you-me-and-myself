package com.youmeandmyself.budget

/**
 * Gate that every AI call must pass through before execution.
 *
 * ## Why This Exists
 *
 * Every path that calls an AI provider (chat, summarization, future branching)
 * must check budget constraints before making the HTTP request. This interface
 * is that single checkpoint.
 *
 * ## Placeholder-Now, Real-Later Pattern
 *
 * At launch (Individual Basic tier), the only implementation is
 * [AlwaysAllowBudgetChecker], which returns "allowed" for everything.
 * The interface exists so that:
 *
 * 1. Every caller wires `budgetChecker.check()` from day one
 * 2. When real enforcement ships (Pro/Company tiers), we swap the
 *    implementation in plugin.xml — zero changes to ChatOrchestrator,
 *    SummarizationService, SummaryPipeline, or any future consumer
 *
 * ## Integration Pattern (from METRICS_MODULE_DESIGN.md §13.3)
 *
 * ```
 * // In any AI-calling code:
 * val status = budgetChecker.check(purpose, providerId, estimatedTokens)
 * if (!status.allowed) {
 *     // Show status.reason to the user, abort the AI call
 *     return
 * }
 * if (status.warning) {
 *     // Optionally show a "approaching limit" warning in the UI
 * }
 * // Proceed with AI call
 * ```
 *
 * ## Thread Safety
 *
 * Implementations must be safe to call from any coroutine context.
 * The placeholder [AlwaysAllowBudgetChecker] is stateless and trivially safe.
 * Future real implementations will need to synchronize access to running totals.
 */
interface BudgetChecker {

    /**
     * Check whether an AI call is allowed under current budget constraints.
     *
     * Called BEFORE every AI provider HTTP request. The caller should inspect
     * the returned [BudgetStatus] and abort the call if [BudgetStatus.allowed]
     * is false.
     *
     * @param purpose What kind of AI work this is. Matches [ExchangePurpose] names:
     *   "CHAT", "FILE_SUMMARY", "CLASS_SUMMARY", "METHOD_SUMMARY", etc.
     *   Used by the real implementation to apply purpose-specific caps
     *   (e.g., chatTokenCap vs summaryTokenCap from [BudgetConfig]).
     *
     * @param providerId The AI profile ID being used. Allows future per-provider
     *   budget tracking (e.g., "don't spend more than X on GPT-4 per day").
     *
     * @param estimatedTokens Optional: caller's estimate of how many tokens this
     *   call will consume. Null when the caller can't predict (most chat messages).
     *   Summarization pipelines may provide estimates based on content length.
     *   The real implementation uses this for pre-flight checks ("this single call
     *   would exceed your remaining daily budget").
     *
     * @return [BudgetStatus] indicating whether the call should proceed
     */
    fun check(purpose: String, providerId: String, estimatedTokens: Int?): BudgetStatus
}

/**
 * Result of a budget check — tells the caller whether to proceed with the AI call.
 *
 * ## Field Semantics
 *
 * - [allowed] + [warning] = false/false → impossible (if not allowed, warning is irrelevant)
 * - [allowed] + [warning] = true/false  → all clear, proceed normally
 * - [allowed] + [warning] = true/true   → proceed, but user is approaching a limit
 * - [allowed] + [warning] = false/true  → blocked, limit reached (warning is technically
 *   redundant here but kept for consistency)
 *
 * ## At Launch
 *
 * [AlwaysAllowBudgetChecker] returns: allowed=true, warning=false, everything else null.
 * The remaining fields are populated by the real implementation post-launch.
 *
 * @property allowed Whether the AI call should proceed. If false, the caller MUST
 *   abort and show [reason] to the user.
 * @property warning Whether the user is approaching a budget limit. The UI can
 *   show a soft warning (e.g., "80% of daily budget used") without blocking.
 * @property reason Human-readable explanation when [allowed] is false or [warning]
 *   is true. Examples: "Daily token cap reached (50,000/50,000)",
 *   "Approaching session limit (4,200/5,000 tokens)". Null when all clear.
 * @property remainingTokens Tokens remaining under the active cap. Null at launch
 *   (no caps configured). Useful for UI progress bars or "X tokens left" displays.
 * @property remainingCost Remaining cost allowance in the smallest currency unit
 *   (e.g., cents). Null until the Pricing Module ships. Kept here so the data
 *   class doesn't need to change when pricing is added.
 * @property capType Which cap triggered the block/warning: "SESSION", "DAILY",
 *   "MONTHLY", "CHAT", "SUMMARY". Null when no cap is active. Helps the UI
 *   show context-appropriate messages ("Daily limit" vs "Session limit").
 */
data class BudgetStatus(
    val allowed: Boolean,
    val warning: Boolean,
    val reason: String?,
    val remainingTokens: Long?,
    val remainingCost: Long?,
    val capType: String?
) {
    companion object {
        /**
         * Convenience factory for the "all clear" status.
         *
         * Used by [AlwaysAllowBudgetChecker] and by the real implementation
         * when no caps are configured or all caps have remaining headroom.
         */
        fun allowed(): BudgetStatus = BudgetStatus(
            allowed = true,
            warning = false,
            reason = null,
            remainingTokens = null,
            remainingCost = null,
            capType = null
        )

        /**
         * Convenience factory for a blocked status.
         *
         * @param reason Why the call was blocked (shown to the user)
         * @param capType Which cap triggered the block
         * @param remainingTokens Should be 0 or near-zero when blocked
         */
        fun blocked(reason: String, capType: String, remainingTokens: Long = 0): BudgetStatus =
            BudgetStatus(
                allowed = false,
                warning = true,
                reason = reason,
                remainingTokens = remainingTokens,
                remainingCost = null,
                capType = capType
            )
    }
}