package com.youmeandmyself.budget

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.youmeandmyself.dev.Dev

/**
 * Launch implementation of [BudgetChecker] — approves every AI call unconditionally.
 *
 * ## Why This Exists
 *
 * At launch (Individual Basic tier), there is no budget enforcement.
 * But every AI call path (ChatOrchestrator, SummarizationService, future SummaryPipeline)
 * already calls `budgetChecker.check()` — this class makes those calls succeed
 * without any logic.
 *
 * ## What Happens Post-Launch
 *
 * When the Pro/Company tier gates open (`canUse(Feature.BUDGET_ENFORCEMENT)` → true),
 * the plugin.xml registration is updated to point to a real implementation that:
 * - Reads [BudgetConfig] from project settings
 * - Queries MetricsService for current usage totals (session/daily/monthly)
 * - Compares usage against caps
 * - Returns blocked/warning statuses when limits are approached or exceeded
 *
 * Because all callers code against the [BudgetChecker] interface, swapping this
 * class out requires zero changes to any consumer.
 *
 * ## Logging
 *
 * Every check is logged via [Dev.info] so we can verify the budget check pipeline
 * is firing correctly during development and testing. These logs also serve as
 * a preview of what the real implementation will track: purpose, provider, and
 * estimated tokens for each AI call.
 *
 * In production, these logs may be demoted to [Dev.debug] (if/when we add that level)
 * or gated behind a verbose logging setting to avoid noise.
 *
 * @param project The IntelliJ project context (required by the service container)
 */
@Service(Service.Level.PROJECT)
class AlwaysAllowBudgetChecker(
    @Suppress("unused") private val project: Project
) : BudgetChecker {

    private val log = Dev.logger(AlwaysAllowBudgetChecker::class.java)

    /**
     * Always returns [BudgetStatus.allowed] — no enforcement at launch.
     *
     * Logs the check parameters so we can verify the budget pipeline is
     * wired correctly in every AI call path (chat, summarization, etc.).
     *
     * @param purpose The type of AI work ("CHAT", "FILE_SUMMARY", etc.)
     * @param providerId The AI profile being used
     * @param estimatedTokens Caller's token estimate (usually null for chat)
     * @return Always [BudgetStatus.allowed] (allowed=true, warning=false)
     */
    override fun check(purpose: String, providerId: String, estimatedTokens: Int?): BudgetStatus {
        Dev.info(log, "budget.check.always_allow",
            "purpose" to purpose,
            "providerId" to providerId,
            "estimatedTokens" to (estimatedTokens?.toString() ?: "none")
        )

        return BudgetStatus.allowed()
    }

    // NOTE: No getInstance() companion here — intentionally.
    //
    // Unlike MetricsService or ConversationManager (which are final concrete classes),
    // BudgetChecker is a swappable interface. Callers should resolve via:
    //
    //     val budgetChecker = project.getService(BudgetChecker::class.java)
    //
    // This way they depend on the interface, not this placeholder class.
    // When the real implementation ships, callers need zero changes.
}