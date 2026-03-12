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
 */
enum class Feature(val description: String) {

    // ── Metrics ──────────────────────────────────────────────────────────
    /** Per-tab token counters and session accumulator. */
    METRICS_BASIC("Basic token usage tracking"),

    /** Global metrics bar, per-model breakdown, historical charts. */
    METRICS_PRO("Advanced metrics and historical analysis"),

    /** Team-wide aggregated metrics visible to admins. */
    METRICS_COMPANY("Company-wide usage metrics"),

    // ── Summarization ────────────────────────────────────────────────────
    /** Generate and cache summaries locally (in-memory + SQLite). */
    SUMMARY_LOCAL("Local summary generation and caching"),

    /** Persist summaries to JSONL for long-term storage. */
    SUMMARY_PERSISTENT("Persistent summary storage"),

    /** Read/write summaries from the shared company store. */
    SUMMARY_SHARED("Shared summary access across team"),

    /** Generate module-level summaries (aggregates file summaries). */
    SUMMARY_MODULE_LEVEL("Module-level summarization"),

    /** Generate project-level summaries (top of the hierarchy). */
    SUMMARY_PROJECT_LEVEL("Project-level summarization"),

    // ── Profiles ─────────────────────────────────────────────────────────
    /** Create and manage AI provider profiles (limited count at Basic). */
    PROFILE_BASIC("Basic AI profile management"),

    /** Unlimited AI provider profiles. */
    PROFILE_UNLIMITED("Unlimited AI profiles"),

    // ── Budget ───────────────────────────────────────────────────────────
    /** Token budget caps per session/day/month with automatic enforcement. */
    BUDGET_ENFORCEMENT("Token budget enforcement"),

    // ── Context & Branching ──────────────────────────────────────────────
    /** Smart conversation branching (Lite/Thread/Deep modes). */
    CONTEXT_BRANCHING("Conversation branching"),

    // ── Team ─────────────────────────────────────────────────────────────
    /** Role-based access: Admin, Contributor, Consumer. */
    TEAM_ROLES("Team role management"),

    /** Admin panel for team configuration, coverage, and metrics. */
    TEAM_ADMIN_PANEL("Team admin panel"),

    // ── Background Processing ────────────────────────────────────────────
    /** Automatic background summarization of code changes. */
    BACKGROUND_SUMMARIZER("Background summarization"),

    // ── Assistant Profile ─────────────────────────────────────────────
    ASSISTANT_PROFILE("Assistant profile personality system"),
}