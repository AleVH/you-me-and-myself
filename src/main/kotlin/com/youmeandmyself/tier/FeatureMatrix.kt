package com.youmeandmyself.tier

/**
 * The single source of truth for which features each tier can access.
 *
 * ## How It Works
 *
 * A [Map] from [Tier] to [Set] of [Feature]. To check access:
 * ```kotlin
 * FeatureMatrix.canUse(Tier.INDIVIDUAL_BASIC, Feature.METRICS_BASIC) // true
 * FeatureMatrix.canUse(Tier.NONE, Feature.METRICS_BASIC)             // false
 * ```
 *
 * ## Modification Rules
 *
 * - **New feature:** Add enum value to [Feature], add it to the appropriate tier sets below.
 * - **New tier:** Add enum value to [Tier], add a map entry below with its feature set.
 * - **Change access:** Move the feature between tier sets.
 * - **No module code changes.** Ever. Modules call [TierProvider.canUse], which delegates here.
 *
 * ## UNKNOWN Tier
 *
 * [Tier.UNKNOWN] gets the same features as [Tier.INDIVIDUAL_BASIC].
 * Rationale: JetBrains [LicensingFacade] initializes asynchronously. During that
 * window, we don't know if the user has a license. Locking them out would be a
 * terrible UX for a race condition they can't control. So we assume Basic until
 * we know otherwise. If they turn out to be unlicensed, features get restricted
 * once the facade initializes — typically within seconds of IDE startup.
 */
object FeatureMatrix {

    // ── Individual Basic: the launch tier ────────────────────────────────
    private val INDIVIDUAL_BASIC_FEATURES: Set<Feature> = setOf(
        Feature.METRICS_BASIC,
        Feature.SUMMARY_LOCAL,
        Feature.SUMMARY_PERSISTENT,
        Feature.PROFILE_BASIC,
        Feature.BUDGET_ENFORCEMENT,
        Feature.BACKGROUND_SUMMARIZER
    )

    // ── Individual Pro: advanced individual features ─────────────────────
    private val INDIVIDUAL_PRO_FEATURES: Set<Feature> = INDIVIDUAL_BASIC_FEATURES + setOf(
        Feature.METRICS_PRO,
        Feature.PROFILE_UNLIMITED,
        Feature.SUMMARY_MODULE_LEVEL,
        Feature.SUMMARY_PROJECT_LEVEL,
        Feature.CONTEXT_BRANCHING
    )

    // ── Company Basic: team features on top of Individual Basic ──────────
    private val COMPANY_BASIC_FEATURES: Set<Feature> = INDIVIDUAL_BASIC_FEATURES + setOf(
        Feature.SUMMARY_SHARED,
        Feature.METRICS_COMPANY,
        Feature.TEAM_ROLES
    )

    // ── Company Pro: everything ──────────────────────────────────────────
    private val COMPANY_PRO_FEATURES: Set<Feature> = Feature.entries.toSet()

    /**
     * The master feature matrix.
     *
     * [Tier.UNKNOWN] maps to [INDIVIDUAL_BASIC_FEATURES] — safe default during
     * LicensingFacade async initialization.
     * [Tier.NONE] maps to empty set — no features without a license.
     */
    private val matrix: Map<Tier, Set<Feature>> = mapOf(
        Tier.INDIVIDUAL_BASIC to INDIVIDUAL_BASIC_FEATURES,
        Tier.INDIVIDUAL_PRO   to INDIVIDUAL_PRO_FEATURES,
        Tier.COMPANY_BASIC    to COMPANY_BASIC_FEATURES,
        Tier.COMPANY_PRO      to COMPANY_PRO_FEATURES,
        Tier.NONE             to emptySet(),
        Tier.UNKNOWN          to INDIVIDUAL_BASIC_FEATURES
    )

    /**
     * Check whether the given [tier] has access to [feature].
     *
     * Returns false for any tier not in the matrix (defensive — should never
     * happen since all enum values are mapped, but better safe than sorry).
     */
    fun canUse(tier: Tier, feature: Feature): Boolean {
        return matrix[tier]?.contains(feature) ?: false
    }

    /**
     * Get all features available for the given [tier].
     *
     * Useful for diagnostics, settings UI, and feature discovery panels.
     * Returns empty set for unknown tiers (defensive).
     */
    fun featuresFor(tier: Tier): Set<Feature> {
        return matrix[tier] ?: emptySet()
    }

    /**
     * Get all tiers that have access to the given [feature].
     *
     * Useful for "upgrade to unlock" messaging — shows the user which tiers
     * would give them the feature they're trying to use.
     */
    fun tiersFor(feature: Feature): Set<Tier> {
        return matrix.entries
            .filter { (_, features) -> feature in features }
            .map { (tier, _) -> tier }
            .toSet()
    }
}