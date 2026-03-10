package com.youmeandmyself.tier

/**
 * Resolves the current licensing tier and checks feature access.
 *
 * ## Usage
 *
 * Modules never check tiers directly. They ask:
 * ```kotlin
 * val tierProvider = TierProvider.getInstance()
 * if (tierProvider.canUse(Feature.SUMMARY_SHARED)) {
 *     // show shared summary UI
 * }
 * ```
 *
 * ## Implementations
 *
 * - [JetBrainsLicenseTierProvider]: Reads tier from JetBrains Marketplace licensing.
 *   This is the launch implementation. Resolves to UNKNOWN → NONE → INDIVIDUAL_BASIC.
 *
 * - [CustomServerTierProvider]: (Stubbed) Will resolve Company tiers via a custom
 *   backend server. Not implemented at launch.
 *
 * - [CompositeTierProvider]: Checks [CustomServerTierProvider] first, falls back to
 *   [JetBrainsLicenseTierProvider]. Registered as the application-level service —
 *   all consumers get this implementation injected.
 *
 * ## Thread Safety
 *
 * Implementations must be safe to call from any thread. Tier resolution may involve
 * I/O (license file reads, network calls for custom server) — callers should not
 * assume instant return, but implementations should cache aggressively.
 */
interface TierProvider {

    /**
     * The user's current licensing tier.
     *
     * May return [Tier.UNKNOWN] during IDE startup while JetBrains
     * [LicensingFacade] initializes. Callers should handle UNKNOWN gracefully —
     * typically by treating it as [Tier.INDIVIDUAL_BASIC] (which the [FeatureMatrix]
     * already does).
     */
    fun currentTier(): Tier

    /**
     * Check whether the current tier has access to the given [feature].
     *
     * This is the primary method modules should use. Equivalent to:
     * ```kotlin
     * FeatureMatrix.canUse(currentTier(), feature)
     * ```
     *
     * @param feature The capability to check.
     * @return true if the feature is available under the current tier.
     */
    fun canUse(feature: Feature): Boolean
}