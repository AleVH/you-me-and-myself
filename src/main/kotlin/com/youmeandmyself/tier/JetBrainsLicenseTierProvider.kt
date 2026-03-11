package com.youmeandmyself.tier

import com.youmeandmyself.dev.Dev
import com.youmeandmyself.dev.DevMode

/**
 * Resolves the user's tier from JetBrains Marketplace licensing.
 *
 * ## How JetBrains Licensing Works
 *
 * 1. The plugin declares a `<product-descriptor>` in `plugin.xml` with a product code.
 * 2. JetBrains handles commerce: purchase, trial, subscription, renewal.
 * 3. The plugin reads licensing state via `LicensingFacade.getInstance()`.
 * 4. The facade populates **asynchronously** on IDE startup — `getInstance()` returning
 *    null means "not initialized yet," NOT "no license."
 *
 * ## Launch Mapping
 *
 * At launch, JetBrains licensing gives us presence/absence of a license, not rich
 * tier differentiation. So the mapping is intentionally simple:
 *
 * | LicensingFacade state                    | Tier              |
 * |------------------------------------------|-------------------|
 * | `getInstance()` returns null              | UNKNOWN           |
 * | Facade available, no license for our code | NONE              |
 * | Facade available, valid license exists    | INDIVIDUAL_BASIC  |
 *
 * ## Future: Pro and Company Tiers
 *
 * It is not confirmed whether JetBrains supports multiple commercial plans for one
 * plugin listing (e.g., Basic vs Pro as separate subscription options). Options:
 * - Separate product codes / listings per tier
 * - License parameters distinguishing tiers within one listing
 * - Company differentiation only visible in Marketplace admin, not in-IDE
 *
 * Until JetBrains confirms the mechanism, Pro and Company tiers cannot be resolved
 * from Marketplace licensing. They will be handled by [CustomServerTierProvider]
 * or a future update to this class.
 *
 * See: Rai's research notes (2026-03-10) on JetBrains Marketplace licensing constraints.
 *
 * ## Product Code
 *
 * The product code must: start with P, be uppercase, 4–15 chars, no digits/special chars.
 * JetBrains explicitly warns that changing it later is painful.
 */
class JetBrainsLicenseTierProvider : TierProvider {

    companion object {
        /**
         * JetBrains Marketplace product code for YMM.
         *
         * TODO: Confirm availability of "PYMM" with JetBrains before first Marketplace submission.
         *  Must start with P, uppercase, 4–15 chars, no digits or special characters.
         *  Once published, this is effectively permanent — JetBrains warns changing it is painful.
         */
        const val PRODUCT_CODE = "PYMM"

        private val log = Dev.logger(JetBrainsLicenseTierProvider::class.java)
    }

    /**
     * Resolve the current tier from JetBrains licensing.
     *
     * ## Implementation Notes
     *
     * This uses reflection or the platform API to access LicensingFacade.
     * The facade is part of the IntelliJ commercial platform and may not be
     * available in Community Edition builds or during unit testing.
     *
     * JetBrains recommends not checking too frequently (CPU overhead).
     * Callers should cache the result where appropriate.
     */
    override fun currentTier(): Tier {
        return try {
            resolveTierFromFacade()
        } catch (e: Exception) {
            // LicensingFacade not available (e.g., Community Edition, test environment).
            // Treat as UNKNOWN — don't lock features for infrastructure reasons.
            Dev.warn(log, "tier.facade.unavailable", e)
            Tier.UNKNOWN
        }
    }

    override fun canUse(feature: Feature): Boolean {
        return FeatureMatrix.canUse(currentTier(), feature)
    }

    // ── Internal ─────────────────────────────────────────────────────────

    /**
     * Reads the licensing state from JetBrains LicensingFacade.
     *
     * TODO: Wire to actual LicensingFacade API. The implementation below is the
     *  intended logic — uncomment and adapt when integrating with the real facade.
     *
     * The actual call pattern (from JetBrains docs):
     * ```
     * val facade = LicensingFacade.getInstance()
     *     ?: return Tier.UNKNOWN  // Not initialized yet
     *
     * val stamp = facade.confirmationStampForKey(PRODUCT_CODE)
     *     ?: return Tier.NONE  // No license for our product code
     *
     * // stamp is non-null → valid license exists
     * return mapLicenseToTier(stamp)
     * ```
     */
    private fun resolveTierFromFacade(): Tier {
        // DEV BYPASS: Skip license check in dev mode so the plugin is usable during development.
        // Remove or gate behind DevMode.isEnabled() before launch.
        if (DevMode.isEnabled()) {
            Dev.info(log, "tier.dev_bypass", "tier" to Tier.INDIVIDUAL_BASIC)
            return Tier.INDIVIDUAL_BASIC
        }
        // ── Step 1: Check if LicensingFacade is available ────────────────
        // LicensingFacade populates asynchronously on IDE startup.
        // null means "not ready yet" — NOT "no license."
        val facadeClass = try {
            Class.forName("com.intellij.ui.LicensingFacade")
        } catch (e: ClassNotFoundException) {
            Dev.info(log, "tier.facade.missing", "reason" to "Community Edition or test env")
            return Tier.UNKNOWN
        }

        val getInstance = facadeClass.getMethod("getInstance")
        val facade = getInstance.invoke(null)
        if (facade == null) {
            Dev.info(log, "tier.facade.init", "status" to "not_ready")
            return Tier.UNKNOWN
        }

        // ── Step 2: Check for our product's license ──────────────────────
        // confirmationStampForKey returns null if no license exists for this product code.
        val confirmMethod = facadeClass.getMethod("confirmationStampForKey", String::class.java)
        val stamp = confirmMethod.invoke(facade, PRODUCT_CODE) as? String
        if (stamp == null) {
            Dev.info(log, "tier.license.check", "productCode" to PRODUCT_CODE, "result" to "none")
            return Tier.NONE
        }

        // ── Step 3: Map license to tier ──────────────────────────────────
        // At launch, any valid license = INDIVIDUAL_BASIC.
        // TODO: When JetBrains confirms how to differentiate Pro/Company,
        //  parse the stamp or license metadata here to return the appropriate tier.
        //  Options under investigation:
        //  - Separate product codes per tier (would need multiple product-descriptors)
        //  - License parameters within the stamp string
        //  - External validation against a custom server (handled by CustomServerTierProvider)
        Dev.info(log, "tier.license.check", "productCode" to PRODUCT_CODE, "result" to "valid", "tier" to Tier.INDIVIDUAL_BASIC)
        return Tier.INDIVIDUAL_BASIC
    }
}