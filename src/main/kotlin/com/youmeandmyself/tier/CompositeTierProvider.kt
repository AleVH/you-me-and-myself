package com.youmeandmyself.tier

import com.intellij.openapi.application.ApplicationManager
import com.youmeandmyself.dev.Dev

/**
 * Composite tier provider — the entry point for all tier checks in YMM.
 *
 * ## Resolution Order
 *
 * 1. [CustomServerTierProvider]: checks for Company tier via custom backend.
 *    If it returns an active tier (COMPANY_BASIC or COMPANY_PRO), use it.
 * 2. [JetBrainsLicenseTierProvider]: checks JetBrains Marketplace licensing.
 *    Returns UNKNOWN, NONE, or INDIVIDUAL_BASIC.
 *
 * ## Why Composite?
 *
 * At launch, only JetBrains licensing is active. The custom server provider
 * always returns [Tier.NONE], so this effectively delegates to JetBrains.
 * But the wiring is here for when Company tier ships — no changes needed
 * in any module that consumes [TierProvider].
 *
 * ## Service Registration
 *
 * Registered as an application-level service in plugin.xml. All consumers
 * should use [getInstance] to get the singleton:
 * ```kotlin
 * val tier = CompositeTierProvider.getInstance()
 * if (tier.canUse(Feature.SUMMARY_SHARED)) { ... }
 * ```
 *
 * Application-level because licensing is global — the user's tier doesn't
 * change between open projects.
 */
class CompositeTierProvider : TierProvider {

    companion object {
        private val log = Dev.logger(CompositeTierProvider::class.java)

        /**
         * Get the application-level [CompositeTierProvider] singleton.
         *
         * This is the standard way to access tier information throughout YMM.
         */
//        fun getInstance(): CompositeTierProvider {
//            return ApplicationManager.getApplication()
//                .getService(CompositeTierProvider::class.java)
//        }
        fun getInstance(): CompositeTierProvider {
            return ApplicationManager.getApplication()
                .getService(TierProvider::class.java) as CompositeTierProvider
        }
    }

    private val customServerProvider = CustomServerTierProvider()
    private val jetBrainsProvider = JetBrainsLicenseTierProvider()

    /**
     * Resolve the current tier using the composite fallback chain.
     *
     * Custom server is checked first because Company tiers take priority:
     * if a user has both a JetBrains license AND a company license, the
     * company tier provides more features (shared summaries, team roles).
     */
    override fun currentTier(): Tier {
        // 1. Check custom server (Company tiers)
        val customTier = customServerProvider.currentTier()
        if (customTier.isActive) {
            Dev.info(log, "tier.resolve", "source" to "custom", "tier" to customTier)
            return customTier
        }

        // 2. Fall back to JetBrains Marketplace (Individual tiers)
        val jbTier = jetBrainsProvider.currentTier()
        Dev.info(log, "tier.resolve", "source" to "jetbrains", "tier" to jbTier)
        return jbTier
    }

    override fun canUse(feature: Feature): Boolean {
        return FeatureMatrix.canUse(currentTier(), feature)
    }

    // ── Diagnostics ──────────────────────────────────────────────────────

    /**
     * Detailed tier resolution info for diagnostics and debug UI.
     *
     * Returns a human-readable summary of what each provider resolved.
     * Useful for the future Settings → Licensing diagnostic panel.
     */
    fun diagnosticInfo(): String {
        val customTier = customServerProvider.currentTier()
        val jbTier = jetBrainsProvider.currentTier()
        val effective = currentTier()
        val featureCount = FeatureMatrix.featuresFor(effective).size
        val totalFeatures = Feature.entries.size

        return buildString {
            appendLine("Tier Diagnostics")
            appendLine("  Custom server: $customTier (${customTier.displayName})")
            appendLine("  JetBrains:     $jbTier (${jbTier.displayName})")
            appendLine("  Effective:     $effective (${effective.displayName})")
            appendLine("  Features:      $featureCount / $totalFeatures enabled")
        }
    }
}