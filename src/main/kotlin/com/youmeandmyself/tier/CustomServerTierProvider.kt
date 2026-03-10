package com.youmeandmyself.tier

/**
 * Resolves Company tiers via a custom backend server.
 *
 * ## Purpose
 *
 * JetBrains Marketplace licensing gives us license presence/absence, but does not
 * (as of 2026-03) provide a confirmed mechanism to differentiate between multiple
 * commercial tiers for one plugin. Company tiers need a separate validation path.
 *
 * This provider will handle:
 * - Validating company licenses against a YMM-hosted backend
 * - Resolving [Tier.COMPANY_BASIC] and [Tier.COMPANY_PRO]
 * - Providing organization context (org ID, team size, role)
 *
 * ## Launch State
 *
 * **Not implemented.** All methods return defaults ([Tier.NONE], false).
 * The [CompositeTierProvider] falls through to [JetBrainsLicenseTierProvider]
 * when this provider returns [Tier.NONE].
 *
 * ## Future Implementation
 *
 * See: TIER_ARCHITECTURE_AND_SHARED_SUMMARIES_PLAN.md for full design.
 * The custom server approach enables:
 * - Per-seat licensing with org management
 * - Role assignment (Admin/Contributor/Consumer)
 * - Shared summary coordination
 * - Team metrics aggregation
 *
 * TODO: Implement when Company tier ships (Phase 6 in roadmap).
 */
class CustomServerTierProvider : TierProvider {

    /**
     * Always returns [Tier.NONE] — custom server validation not implemented.
     *
     * When implemented, this will make a (cached) call to the YMM backend
     * to validate the user's company license and resolve their tier + role.
     */
    override fun currentTier(): Tier = Tier.NONE

    /**
     * Always returns false — no Company features available without a custom server.
     */
    override fun canUse(feature: Feature): Boolean = false
}