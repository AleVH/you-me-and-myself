package com.youmeandmyself.tier

/**
 * Represents the user's current licensing tier.
 *
 * ## Tier Hierarchy
 *
 * Individual tiers are for single developers using their own API keys.
 * Company tiers are for teams sharing AI-generated summaries across developers.
 *
 * ## Special Tiers
 *
 * - [UNKNOWN]: JetBrains [LicensingFacade] has not initialized yet. This happens
 *   asynchronously on IDE startup. Returning UNKNOWN means "we don't know yet" —
 *   NOT "unlicensed." The plugin must not lock features during this window.
 *   See: JetBrains docs on LicensingFacade async initialization.
 *
 * - [NONE]: LicensingFacade is initialized but no valid license exists.
 *   No features are available. The user sees trial-expired / not-purchased state.
 *
 * ## Launch State
 *
 * At launch, only [INDIVIDUAL_BASIC] is reachable through JetBrains Marketplace
 * licensing. Pro and Company tiers are defined here for the feature matrix but
 * require confirmation from JetBrains on how to differentiate (separate product
 * codes vs. license parameters vs. separate listings). See [JetBrainsLicenseTierProvider].
 *
 * @property displayName Human-readable name for UI display.
 * @property isActive True if the user has any valid license (excludes NONE and UNKNOWN).
 */
enum class Tier(
    val displayName: String,
    val isActive: Boolean
) {
    /** Single developer, core features. The launch tier. */
    INDIVIDUAL_BASIC("Individual Basic", isActive = true),

    /** Single developer, advanced features (Pro metrics, branching, module/project summaries). */
    INDIVIDUAL_PRO("Individual Pro", isActive = true),

    /** Team, core features + shared summaries. */
    COMPANY_BASIC("Company Basic", isActive = true),

    /** Team, all features including admin panel and full metrics. */
    COMPANY_PRO("Company Pro", isActive = true),

    /** No valid license. All features locked. */
    NONE("Unlicensed", isActive = false),

    /**
     * LicensingFacade has not initialized yet.
     * Treated as [INDIVIDUAL_BASIC] for feature gating — never lock the user out
     * during the startup race condition.
     */
    UNKNOWN("Initializing…", isActive = false);

    /** True if this is any Company-level tier. */
    val isCompany: Boolean
        get() = this == COMPANY_BASIC || this == COMPANY_PRO

    /** True if this is any Individual-level tier. */
    val isIndividual: Boolean
        get() = this == INDIVIDUAL_BASIC || this == INDIVIDUAL_PRO
}