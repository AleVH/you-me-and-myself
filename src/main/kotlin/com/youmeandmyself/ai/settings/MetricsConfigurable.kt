package com.youmeandmyself.ai.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.youmeandmyself.dev.Dev
import com.youmeandmyself.tier.CompositeTierProvider
import com.youmeandmyself.tier.Feature
import java.awt.BorderLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.*

/**
 * Metrics settings page for YMM Assistant.
 *
 * ## Location in Settings Tree
 *
 * Settings → Tools → YMM Assistant → Metrics
 *
 * ## What Lives Here
 *
 * All user-configurable metrics preferences. Starts with one toggle
 * at Individual Basic launch. Pro and Company tier settings are added
 * as those tiers ship — the UI structure accommodates growth.
 *
 * ### Launch (Individual Basic)
 * - Show token usage bar on chat tabs [on/off toggle]
 *
 * ### Post-Launch (Pro Tier) — sections present but hidden until Pro ships
 * - Show global metrics bar [on/off]
 * - Keep Metrics Tab open [on/off]
 * - Per-tab display mode [compact/medium/detailed]
 * - Global display mode [compact/medium/detailed]
 * - Refresh strategy [manual/on-focus/auto]
 * - Auto-refresh interval [seconds spinner]
 *
 * ### Post-Launch (Company Tier)
 * - Data retention [keep forever / auto-cleanup after N days]
 *
 * ## Working Copy Pattern
 *
 * Same as GeneralConfigurable and SummaryConfigurable: load on reset(),
 * only persist on apply(). Users can change fields and cancel without saving.
 *
 * ## Tier-Aware Display
 *
 * Settings that require a higher tier than the user's current one are
 * either hidden entirely or shown as disabled with a tier label.
 * This avoids confusion ("why doesn't this setting do anything?").
 *
 * @param project The IntelliJ project these settings belong to
 */
class MetricsConfigurable(private val project: Project) : Configurable {

    private val log = Dev.logger(MetricsConfigurable::class.java)

    // ── Services ──────────────────────────────────────────────────────
    private val settingsState get() = MetricsSettingsState.getInstance(project)

    // ── Working copy ──────────────────────────────────────────────────
    private var workingShowMetricsBar: Boolean = true

    // ── UI components ─────────────────────────────────────────────────
    private val root = JPanel(BorderLayout(0, 12))

    /** Main toggle: show/hide the per-tab metrics bar. */
    private val showMetricsBarCheckbox = JCheckBox("Show token usage bar on chat tabs")

    // ── Configurable interface ────────────────────────────────────────

    override fun getDisplayName(): String = "Metrics"

    override fun createComponent(): JComponent {
        val formPanel = JPanel(GridBagLayout())
        val gbc = GridBagConstraints().apply {
            insets = Insets(4, 8, 4, 8)
            anchor = GridBagConstraints.WEST
            fill = GridBagConstraints.HORIZONTAL
        }

        var row = 0

        // ── Section: Visibility ───────────────────────────────────────
        row = addSeparator(formPanel, gbc, row, "Visibility")

        // Show metrics bar toggle
        row = addFormRow(formPanel, gbc, row,
            label = "",
            component = showMetricsBarCheckbox,
            help = "When enabled, a compact bar at the top of each chat tab " +
                    "shows token usage (prompt, completion, total) and context " +
                    "window fill percentage. When disabled, the bar is hidden " +
                    "but metrics are still recorded in the background."
        )

        // ── Section: Pro Features (placeholder) ───────────────────────
        // Only show this section if the user is on Pro tier or above.
        // For Basic tier, these settings are hidden entirely to avoid
        // "what does this do?" confusion.
        val tierProvider = try {
            CompositeTierProvider.getInstance()
        } catch (e: Exception) {
            Dev.warn(log, "metrics.settings.tier_check_failed", e)
            null
        }
//        val tierProvider = CompositeTierProvider.getInstance()
//        if (tierProvider.canUse(Feature.METRICS_PRO)) {
        if (tierProvider != null && tierProvider.canUse(Feature.METRICS_PRO)) {
            row = addSeparator(formPanel, gbc, row, "Advanced (Pro)")

            val proPlaceholder = JBLabel(
                "<html><i>Advanced metrics settings (global bar, display modes, " +
                        "refresh strategies, graph styles) will appear here when " +
                        "the Pro metrics features are implemented.</i></html>"
            ).apply {
                foreground = java.awt.Color(140, 100, 0)
                font = font.deriveFont(font.size2D - 1f)
            }
            row = addFormRow(formPanel, gbc, row,
                label = "",
                component = proPlaceholder,
                help = ""
            )
        }

        // ── Section: Company Features (placeholder) ───────────────────
        if (tierProvider != null && tierProvider.canUse(Feature.METRICS_COMPANY)) {
//        if (tierProvider.canUse(Feature.METRICS_COMPANY)) {
            row = addSeparator(formPanel, gbc, row, "Team Analytics (Company)")

            val companyPlaceholder = JBLabel(
                "<html><i>Company-tier settings (data retention, team analytics, " +
                        "admin overrides) will appear here when Phase 6 ships.</i></html>"
            ).apply {
                foreground = java.awt.Color(140, 100, 0)
                font = font.deriveFont(font.size2D - 1f)
            }
            row = addFormRow(formPanel, gbc, row,
                label = "",
                component = companyPlaceholder,
                help = ""
            )
        }

        // ── Vertical spacer to push everything to the top ─────────────
        gbc.gridy = row
        gbc.weighty = 1.0
        gbc.fill = GridBagConstraints.BOTH
        formPanel.add(JPanel(), gbc)

        root.add(JScrollPane(formPanel), BorderLayout.CENTER)

        // Load initial values
        reset()

        return root
    }

    override fun isModified(): Boolean {
        return workingShowMetricsBar != showMetricsBarCheckbox.isSelected
    }

    override fun apply() {
        workingShowMetricsBar = showMetricsBarCheckbox.isSelected

        // Persist to state
        val state = settingsState.state
        state.showMetricsBar = workingShowMetricsBar
        settingsState.loadState(state)

        Dev.info(log, "metrics.settings.applied",
            "showMetricsBar" to workingShowMetricsBar
        )
    }

    override fun reset() {
        // Load from persisted state
        val state = settingsState.state
        workingShowMetricsBar = state.showMetricsBar

        showMetricsBarCheckbox.isSelected = workingShowMetricsBar
    }

    override fun disposeUIResources() { /* nothing to dispose */ }

    // ── Layout Helpers ────────────────────────────────────────────────
    // Same pattern as GeneralConfigurable and SummaryConfigurable.

    /** Add a labeled form row: [Label] [Component] [?] */
    private fun addFormRow(
        panel: JPanel,
        gbc: GridBagConstraints,
        row: Int,
        label: String,
        component: JComponent,
        help: String
    ): Int {
        gbc.gridy = row
        gbc.weighty = 0.0

        gbc.gridx = 0
        gbc.weightx = 0.0
        gbc.fill = GridBagConstraints.NONE
        panel.add(JBLabel(label), gbc)

        gbc.gridx = 1
        gbc.weightx = 1.0
        gbc.fill = GridBagConstraints.HORIZONTAL
        panel.add(component, gbc)

        gbc.gridx = 2
        gbc.weightx = 0.0
        gbc.fill = GridBagConstraints.NONE
        if (help.isNotBlank()) {
            panel.add(helpIcon(help), gbc)
        } else {
            panel.add(JPanel(), gbc)
        }

        return row + 1
    }

    /** Section separator with bold title. */
    private fun addSeparator(
        panel: JPanel, gbc: GridBagConstraints, row: Int, title: String
    ): Int {
        gbc.gridy = row
        gbc.gridx = 0
        gbc.gridwidth = 3
        gbc.weightx = 1.0
        gbc.weighty = 0.0
        gbc.fill = GridBagConstraints.HORIZONTAL
        gbc.insets = Insets(12, 8, 4, 8)

        val separator = JPanel(BorderLayout()).apply {
            add(JBLabel("<html><b>$title</b></html>"), BorderLayout.WEST)
            add(JSeparator(), BorderLayout.CENTER)
        }
        panel.add(separator, gbc)

        gbc.gridwidth = 1
        gbc.insets = Insets(4, 8, 4, 8)
        return row + 1
    }

    /** Help icon with tooltip. */
    private fun helpIcon(tooltip: String): JLabel {
        return JBLabel("?").apply {
            toolTipText = "<html><body style='width: 250px;'>$tooltip</body></html>"
            font = font.deriveFont(font.size2D - 1f)
            foreground = java.awt.Color.GRAY
            cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
        }
    }
}