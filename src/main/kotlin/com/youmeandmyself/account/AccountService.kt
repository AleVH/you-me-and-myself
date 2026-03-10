package com.youmeandmyself.account

import com.intellij.openapi.application.ApplicationManager

/**
 * Placeholder for user identity and organization context.
 *
 * ## Launch State
 *
 * **Not implemented.** All methods return null or defaults. This class exists
 * to give the account infrastructure a home in the codebase. Future Company
 * tier features (team roles, shared summaries, admin panel) will need user
 * identity and org context — this is where that logic will live.
 *
 * ## Important: User Identity from JetBrains Licensing
 *
 * As of 2026-03, JetBrains LicensingFacade provides license validation but
 * does NOT guarantee a per-user identity API that plugins can freely consume.
 * User identity for Company tier features will likely come from the custom
 * backend server, not from JetBrains. Do not assume LicensingFacade gives
 * user identity without separate confirmation.
 *
 * See: Rai's research notes (2026-03-10) on JetBrains licensing limitations.
 *
 * ## Future Design
 *
 * See: Account Model design doc (referenced in TIER_ARCHITECTURE_AND_SHARED_SUMMARIES_PLAN.md).
 * Planned capabilities:
 * - User ID resolution (from custom backend SSO)
 * - Organization context (org ID, team, role)
 * - Session validation (token refresh, expiry handling)
 *
 * TODO: Implement when Company tier ships (Phase 6 in roadmap).
 *
 * ## Service Registration
 *
 * Registered as an application-level service in plugin.xml.
 */
class AccountService {

    companion object {
        fun getInstance(): AccountService {
            return ApplicationManager.getApplication()
                .getService(AccountService::class.java)
        }
    }

    /**
     * The current user's unique identifier.
     *
     * Returns null at launch — user identity is not available from
     * JetBrains licensing alone. Will be populated when the custom
     * backend authentication is implemented.
     */
    fun userId(): String? = null

    /**
     * The current user's organization context.
     *
     * Returns null at launch — no organization system exists yet.
     * Will provide org ID, team membership, and role when Company
     * tier ships.
     */
    fun orgContext(): OrgContext? = null

    /**
     * Validate the current session against the backend.
     *
     * Returns false at launch — no session management exists yet.
     * Will handle token refresh, expiry, and re-authentication when
     * the custom backend is implemented.
     */
    fun validateSession(): Boolean = false
}

/**
 * Organization context for Company tier users.
 *
 * Placeholder data class — fields will be expanded when Company tier
 * design is finalized.
 *
 * @property orgId Unique identifier for the organization.
 * @property teamId Optional team within the org (for large organizations with multiple teams).
 * @property role The user's role within the organization.
 */
data class OrgContext(
    val orgId: String,
    val teamId: String? = null,
    val role: OrgRole = OrgRole.CONSUMER
)

/**
 * Roles within a Company tier organization.
 *
 * See: TIER_ARCHITECTURE_AND_SHARED_SUMMARIES_PLAN.md Section 5 (Roles) for full design.
 *
 * @property canRead Can read shared summaries.
 * @property canWrite Can generate and publish shared summaries.
 * @property canAdmin Can manage team configuration, roles, and view team metrics.
 */
enum class OrgRole(
    val canRead: Boolean,
    val canWrite: Boolean,
    val canAdmin: Boolean
) {
    /** Full control: manage team, roles, configuration, shared summaries. */
    ADMIN(canRead = true, canWrite = true, canAdmin = true),

    /** Generate and publish shared summaries. Cannot manage team config. */
    CONTRIBUTOR(canRead = true, canWrite = true, canAdmin = false),

    /** Read-only access to shared summaries. Cannot generate or manage. */
    CONSUMER(canRead = true, canWrite = false, canAdmin = false)
}