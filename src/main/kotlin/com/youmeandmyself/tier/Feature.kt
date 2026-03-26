package com.youmeandmyself.tier

/**
 * Every tier-gated capability in YMM.
 *
 * ## Design Principle
 *
 * Features are the unit of gating. Modules never check tiers directly —
 * they check features via [TierProvider.canUse]. This means:
 * - Adding a new feature = one enum value + one map entry in [FeatureMatrix]
 * - Changing which tier gets a feature = one map change in [FeatureMatrix]
 * - No module code changes when tiers or features are added/removed
 *
 * ## Naming Convention
 *
 * Features are grouped by module area: METRICS_*, SUMMARY_*, PROFILE_*, etc.
 * Each feature name should be self-documenting: [SUMMARY_SHARED] means
 * "access to the shared summary store" without needing to read docs.
 *
 * @property description Human-readable explanation of what this feature enables.
 * @property visibleFrom Which tiers should SEE this feature in the UI (regardless of access).
 *                       Default: all active tiers. Use [FeatureVisibility.PRO_AND_ABOVE] or [FeatureVisibility.COMPANY_ONLY] for gated features.
 * @property implemented Whether the code behind this feature is real. `false` = UI shows "Coming soon".
 *                       Flip to `true` when the feature ships. Default: `true` (existing features work).
 */
enum class Feature(
    val description: String,
    val visibleFrom: Set<Tier> = FeatureVisibility.ALL_ACTIVE_TIERS,
    val implemented: Boolean = true
) {

    // ── Metrics ──────────────────────────────────────────────────────────
    /** Per-tab token counters and session accumulator. */
    METRICS_BASIC("Basic token usage tracking"),

    /** Global metrics bar, per-model breakdown, historical charts. */
    METRICS_PRO("Advanced metrics and historical analysis", FeatureVisibility.PRO_AND_ABOVE, implemented = false),

    /** Team-wide aggregated metrics visible to admins. */
    METRICS_COMPANY("Company-wide usage metrics", FeatureVisibility.COMPANY_ONLY, implemented = false),

    // ── Summarization ────────────────────────────────────────────────────
    /** Generate and cache summaries locally (in-memory + SQLite). */
    SUMMARY_LOCAL("Local summary generation and caching"),

    /** Persist summaries to JSONL for long-term storage. */
    SUMMARY_PERSISTENT("Persistent summary storage"),

    /** Read/write summaries from the shared company store. */
    SUMMARY_SHARED("Shared summary access across team", FeatureVisibility.COMPANY_ONLY, implemented = false),

    /** Generate module-level summaries (aggregates file summaries). */
    SUMMARY_MODULE_LEVEL("Module-level summarization", FeatureVisibility.PRO_AND_ABOVE, implemented = false),

    /** Generate project-level summaries (top of the hierarchy). */
    SUMMARY_PROJECT_LEVEL("Project-level summarization", FeatureVisibility.PRO_AND_ABOVE, implemented = false),

    // ── Profiles ─────────────────────────────────────────────────────────
    /** Create and manage AI provider profiles (limited count at Basic). */
    PROFILE_BASIC("Basic AI profile management"),

    /** Unlimited AI provider profiles. */
    PROFILE_UNLIMITED("Unlimited AI profiles", FeatureVisibility.PRO_AND_ABOVE, implemented = false),

    // ── Budget ───────────────────────────────────────────────────────────
    /** Token budget caps per session/day/month with automatic enforcement. */
    BUDGET_ENFORCEMENT("Token budget enforcement"),

    // ── Context Control ────────────────────────────────────────────────────
    /**
     * Per-component context bypass (SELECTIVE mode).
     *
     * Allows Pro-tier users to selectively disable individual context
     * detectors (e.g., skip project structure but keep current file) via
     * the ContextLever UI. Basic-tier users only get OFF/FULL toggle.
     *
     * @see ContextAssembler.assemble — checks bypassMode parameter
     * @see ContextDial — React component (Phase C) that sets the mode
     */
    CONTEXT_SELECTIVE_BYPASS("Per-component context bypass (Pro)", FeatureVisibility.PRO_AND_ABOVE, implemented = false),

    /**
     * Individual context badge removal from the staging area.
     *
     * Pro-tier users can remove individual context entries before sending
     * (X button on badges in the tray). Basic-tier users cannot — once
     * context is attached, it stays for the conversation.
     *
     * @see ContextStagingService.removeEntry — backend removal
     * @see ContextBadgeTray — frontend X button rendering
     */
    CONTEXT_BADGE_REMOVAL("Individual context badge removal (Pro)", FeatureVisibility.PRO_AND_ABOVE),

    // ── Context & Branching ──────────────────────────────────────────────
    /** Smart conversation branching (Lite/Thread/Deep modes). */
    CONTEXT_BRANCHING("Conversation branching", FeatureVisibility.PRO_AND_ABOVE, implemented = false),

    // ── Team ─────────────────────────────────────────────────────────────
    /** Role-based access: Admin, Contributor, Consumer. */
    TEAM_ROLES("Team role management", FeatureVisibility.COMPANY_ONLY, implemented = false),

    /** Admin panel for team configuration, coverage, and metrics. */
    TEAM_ADMIN_PANEL("Team admin panel", FeatureVisibility.COMPANY_ONLY, implemented = false),

    // ── Background Processing ────────────────────────────────────────────
    /** Automatic background summarization of code changes. */
    BACKGROUND_SUMMARIZER("Background summarization"),

    // ── Assistant Profile ─────────────────────────────────────────────
    ASSISTANT_PROFILE("Assistant profile personality system");

}

/**
 * Tier visibility constants for [Feature.visibleFrom].
 *
 * Defined as a top-level object (not a companion) because Kotlin enum entries
 * are initialized before the companion object, so companion constants cannot
 * be referenced in enum constructor arguments.
 */
object FeatureVisibility {
    /** All tiers where features are visible by default. Excludes NONE and UNKNOWN. */
    val ALL_ACTIVE_TIERS: Set<Tier> = setOf(
        Tier.INDIVIDUAL_BASIC, Tier.INDIVIDUAL_PRO,
        Tier.COMPANY_BASIC, Tier.COMPANY_PRO
    )

    /** Pro and above — visible to Individual Pro, Company Basic, and Company Pro. */
    val PRO_AND_ABOVE: Set<Tier> = setOf(
        Tier.INDIVIDUAL_PRO, Tier.COMPANY_BASIC, Tier.COMPANY_PRO
    )

    /** Company tiers only — visible to Company Basic and Company Pro. */
    val COMPANY_ONLY: Set<Tier> = setOf(
        Tier.COMPANY_BASIC, Tier.COMPANY_PRO
    )
}