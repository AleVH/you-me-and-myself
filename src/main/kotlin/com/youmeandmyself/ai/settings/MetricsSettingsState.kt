package com.youmeandmyself.ai.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project

/**
 * Persistent settings state for the Metrics Module.
 *
 * ## Location in Settings Tree
 *
 * Settings → Tools → YMM Assistant → Metrics
 * Managed by [MetricsConfigurable] (the UI page).
 *
 * ## What Lives Here
 *
 * All metrics-related user preferences. Starts minimal at launch
 * (one toggle) and grows as Pro/Company features ship:
 *
 * ### Launch (Individual Basic)
 * - showMetricsBar — show/hide the per-tab token usage bar
 *
 * ### Post-Launch (Pro Tier) — fields ready, UI not yet built
 * - showGlobalMetricsBar — global cross-tab metrics bar
 * - keepMetricsTab — persist the Metrics Tab between sessions
 * - perTabDisplayMode — compact / medium / detailed
 * - globalDisplayMode — compact / medium / detailed
 * - refreshStrategy — manual / on-focus / auto
 * - autoRefreshIntervalSeconds — interval for auto refresh
 *
 * ### Post-Launch (Company Tier) — Phase 6
 * - retentionDays — auto-cleanup threshold (null = keep forever)
 *
 * ## Persistence
 *
 * Stored in IntelliJ's project-level XML configuration via
 * @State/@Storage annotations. Survives IDE restarts. Each project
 * has its own metrics settings (a developer might want metrics
 * visible on one project but hidden on another).
 *
 * ## Working Copy Pattern
 *
 * MetricsConfigurable loads from here on reset(), and writes back
 * on apply(). Users can change fields and cancel without saving.
 *
 * @param project The IntelliJ project these settings belong to
 */
@Service(Service.Level.PROJECT)
@State(
    name = "YmmMetricsSettings",
    storages = [Storage("ymmMetricsSettings.xml")]
)
class MetricsSettingsState(
    private val project: Project
) : PersistentStateComponent<MetricsSettingsState.State> {

    /**
     * The serializable state container.
     *
     * All fields use `var` with defaults so IntelliJ's XML persistence
     * can instantiate and populate them. New fields MUST have defaults
     * to maintain backward compatibility with existing settings files.
     */
    data class State(
        // ── Launch (Individual Basic) ─────────────────────────────────

        /**
         * Whether the per-tab metrics bar is visible.
         *
         * When false, the MetricsBar component is hidden in all tabs.
         * The backend still records metrics to SQLite — this only
         * controls the UI display. Default: true (visible).
         *
         * Gated by Feature.METRICS_BASIC — if the tier doesn't support
         * metrics, this setting is ignored (bar is always hidden).
         */
        var showMetricsBar: Boolean = true,

        // ── Post-Launch (Pro Tier) ────────────────────────────────────
        // Fields present from day one so we never need migration.
        // UI for these is built when Pro tier ships.

        /**
         * Whether the global cross-tab metrics bar is visible.
         * Pro tier only — ignored for Basic tier.
         * Default: false (not shown until user opts in).
         */
        var showGlobalMetricsBar: Boolean = false,

        /**
         * Whether the Metrics Tab persists between sessions.
         * When false (default), the tab is ephemeral — closed when done.
         * Pro tier only.
         */
        var keepMetricsTab: Boolean = false,

        /**
         * Display mode for per-tab metrics bar.
         * Values: "compact", "medium", "detailed".
         * Basic tier always uses "compact". Pro tier can choose.
         */
        var perTabDisplayMode: String = "compact",

        /**
         * Display mode for global metrics bar.
         * Values: "compact", "medium", "detailed".
         * Pro tier only.
         */
        var globalDisplayMode: String = "compact",

        /**
         * Refresh strategy for the Metrics Tab.
         * Values: "manual", "on_focus", "auto".
         * Pro tier only. Default: manual (zero resource impact).
         */
        var refreshStrategy: String = "manual",

        /**
         * Auto-refresh interval in seconds for the Metrics Tab.
         * Only used when refreshStrategy = "auto".
         * Pro tier only. Default: 60 seconds.
         */
        var autoRefreshIntervalSeconds: Int = 60,

        // ── Post-Launch (Company Tier) ────────────────────────────────

        /**
         * Metrics data retention in days. Null = keep forever (default).
         * When set, metrics older than this are auto-cleaned.
         * Company tier may enforce this via admin override.
         */
        var retentionDays: Int? = null
    )

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }

    companion object {
        /**
         * Get the MetricsSettingsState instance for a project.
         */
        fun getInstance(project: Project): MetricsSettingsState {
            return project.getService(MetricsSettingsState::class.java)
        }
    }
}