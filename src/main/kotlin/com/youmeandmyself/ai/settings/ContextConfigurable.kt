package com.youmeandmyself.ai.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.youmeandmyself.ai.bridge.CrossPanelBridge
import com.youmeandmyself.dev.Dev
import com.youmeandmyself.tier.CompositeTierProvider
import com.youmeandmyself.tier.Feature
import java.awt.BorderLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.*

/**
 * Context settings page for YMM Assistant.
 *
 * ## Location in Settings Tree
 *
 * Settings → Tools → YMM Assistant → Context
 *
 * ## What Lives Here
 *
 * User-configurable context gathering preferences:
 *
 * ### Launch (Individual Basic)
 * - **Enable context gathering** [on/off toggle] — master kill-switch
 * - **Smart context filter** [on/off toggle] — heuristic pre-filter (default: off)
 * - Default bypass mode combo (FULL only for Basic; SELECTIVE added for Pro)
 *
 * ### Post-Launch (Pro Tier)
 * - Selective bypass mode options (greyed out with "Pro" label for Basic)
 *
 * ## Smart Context Filter
 *
 * When enabled, the [ContextHeuristicFilter] scans the user's message for
 * code-related markers BEFORE context gathering starts. If no markers are
 * found (e.g. "hello", "tell me a joke"), context gathering is skipped
 * to save tokens.
 *
 * Default: OFF — context always flows through. This is the safe launch
 * default because the heuristic has known false negatives (e.g. "how does
 * this class relate to the backend?" doesn't match any keyword pattern).
 *
 * The checkbox is disabled when "Enable context gathering" is unchecked,
 * because there's nothing to filter if context gathering is off entirely.
 *
 * ## Working Copy Pattern
 *
 * Same as MetricsConfigurable: load on reset(), persist on apply().
 * Users can change fields and cancel without saving.
 *
 * @param project The IntelliJ project these settings belong to
 * @see ContextSettingsState — persistence layer
 * @see ContextHeuristicFilter — the filter controlled by the checkbox
 * @see ContextAssembler — reads heuristicFilterEnabled at call time
 */
class ContextConfigurable(private val project: Project) : Configurable {

    private val log = Dev.logger(ContextConfigurable::class.java)

    // ── Services ──────────────────────────────────────────────────────
    private val settingsState get() = ContextSettingsState.getInstance(project)

    // ── Working copy ──────────────────────────────────────────────────
    // These hold the UI values between reset() and apply().
    // They allow the user to change things and cancel without persisting.
    private var workingContextEnabled: Boolean = true
    private var workingDefaultBypassMode: String = "FULL"
    private var workingHeuristicFilterEnabled: Boolean = false

    // ── UI components ─────────────────────────────────────────────────
    private val root = JPanel(BorderLayout(0, 12))

    /** Master toggle: enable/disable context gathering globally. */
    private val contextEnabledCheckbox = JCheckBox("Enable context gathering")

    /**
     * Default bypass mode combo for new tabs.
     *
     * When context is enabled (master toggle on), the only sensible default
     * is "FULL" (full context gathering). "OFF" is NOT offered here because
     * the master toggle is the kill-switch for context — having a per-tab
     * "OFF" default when context is enabled contradicts the global setting.
     *
     * Pro tier adds "SELECTIVE" as an option (per-component context).
     */
    private val defaultBypassModeCombo = JComboBox(arrayOf("FULL"))

    /**
     * Smart context filter toggle.
     *
     * When checked, [ContextHeuristicFilter] is active: messages that don't
     * contain code-related markers skip context gathering to save tokens.
     * Default: unchecked (context always flows through).
     *
     * Disabled/greyed out when "Enable context gathering" is unchecked,
     * because there's nothing to filter if context gathering is off entirely.
     */
    private val heuristicFilterCheckbox = JCheckBox("Smart context filter (skip context for non-code messages)")

    // ── Configurable interface ────────────────────────────────────────

    override fun getDisplayName(): String = "Context"

    override fun createComponent(): JComponent {
        val formPanel = JPanel(GridBagLayout())
        val gbc = GridBagConstraints().apply {
            insets = Insets(4, 8, 4, 8)
            anchor = GridBagConstraints.WEST
            fill = GridBagConstraints.HORIZONTAL
        }

        var row = 0

        // ── Section: General ──────────────────────────────────────────
        row = addSeparator(formPanel, gbc, row, "Context Gathering")

        // Enable context gathering toggle.
        // ActionListener updates the heuristic filter checkbox enabled state
        // immediately so the user sees the dependency visually.
        contextEnabledCheckbox.addActionListener {
            // The heuristic filter checkbox only makes sense when context is enabled.
            // If context is off, there's nothing to filter — grey it out.
            heuristicFilterCheckbox.isEnabled = contextEnabledCheckbox.isSelected
        }
        row = addFormRow(formPanel, gbc, row,
            label = "",
            component = contextEnabledCheckbox,
            help = "When enabled, YMM gathers IDE context (open files, project " +
                    "structure, summaries) and includes it in the prompt sent to " +
                    "the AI provider. When disabled, context gathering is skipped " +
                    "globally — applies to all tabs immediately."
        )

        // Smart context filter — heuristic pre-filter toggle.
        // Disabled when "Enable context gathering" is unchecked.
        row = addFormRow(formPanel, gbc, row,
            label = "",
            component = heuristicFilterCheckbox,
            help = "When enabled, the plugin uses keyword detection to skip context " +
                    "gathering for messages that don't appear code-related (e.g. " +
                    "'hello', 'tell me a joke'). This saves tokens but may occasionally " +
                    "skip context for ambiguous messages like 'explain how this relates " +
                    "to the backend'. Default: off (context always flows through)."
        )

        // ── Section: Selective Bypass (Pro) ───────────────────────────
        // Tier check governs: defaultBypassMode combo + SELECTIVE content below.
        val tierProvider = try {
            CompositeTierProvider.getInstance()
        } catch (e: Exception) {
            Dev.warn(log, "context.settings.tier_check_failed", e)
            null
        }

        val canUseSelective = tierProvider != null &&
                tierProvider.canUse(Feature.CONTEXT_SELECTIVE_BYPASS)

        // Default bypass mode combo — Pro tier only.
        // Basic users always get "FULL" (context ON) from the bridge; the stored value
        // is ignored for Basic. Grey out the combo with a note so they see it exists.
        defaultBypassModeCombo.isEnabled = canUseSelective
        row = addFormRow(formPanel, gbc, row,
            label = "Default mode for new tabs:",
            component = defaultBypassModeCombo,
            help = if (canUseSelective) {
                "The dial position applied when a new tab is created. " +
                        "FULL = full context gathering (recommended). " +
                        "SELECTIVE = per-component context (Pro tier)."
            } else {
                "Pro tier: set the dial's starting position for new tabs. " +
                        "Basic tier: new tabs always start with full context gathering."
            }
        )

        row = addSeparator(formPanel, gbc, row, "Selective Bypass")

        if (canUseSelective) {
            // Pro tier: show full selective bypass options (placeholder)
            val proInfo = JBLabel(
                "<html><i>Per-component context bypass settings will appear here " +
                        "when the ContextLever component is fully wired.</i></html>"
            ).apply {
                foreground = java.awt.Color(140, 100, 0)
                font = font.deriveFont(font.size2D - 1f)
            }
            row = addFormRow(formPanel, gbc, row,
                label = "",
                component = proInfo,
                help = ""
            )
        } else {
            // Basic tier: show greyed-out Pro label
            val basicInfo = JBLabel(
                "<html><i>Selective bypass lets you control which context " +
                        "components are gathered per-message. Available with Pro tier.</i></html>"
            ).apply {
                foreground = java.awt.Color.GRAY
                font = font.deriveFont(font.size2D - 1f)
            }
            row = addFormRow(formPanel, gbc, row,
                label = "",
                component = basicInfo,
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
        return workingContextEnabled != contextEnabledCheckbox.isSelected ||
                workingDefaultBypassMode != (defaultBypassModeCombo.selectedItem as? String ?: "FULL") ||
                workingHeuristicFilterEnabled != heuristicFilterCheckbox.isSelected
    }

    override fun apply() {
        workingContextEnabled = contextEnabledCheckbox.isSelected
        workingDefaultBypassMode = defaultBypassModeCombo.selectedItem as? String ?: "FULL"
        workingHeuristicFilterEnabled = heuristicFilterCheckbox.isSelected

        // Persist to state — this writes to IntelliJ's project-level XML.
        // ContextAssembler reads heuristicFilterEnabled at call time (no caching),
        // so the change takes effect on the next message sent.
        val state = settingsState.state
        state.contextEnabled = workingContextEnabled
        state.defaultBypassMode = workingDefaultBypassMode
        state.heuristicFilterEnabled = workingHeuristicFilterEnabled
        settingsState.loadState(state)

        Dev.info(log, "context.settings.applied",
            "contextEnabled" to workingContextEnabled,
            "defaultBypassMode" to workingDefaultBypassMode,
            "heuristicFilterEnabled" to workingHeuristicFilterEnabled
        )

        // Push updated context settings to the React panel immediately.
        // This re-emits CONTEXT_SETTINGS so the dial/strip reflect the new state.
        try {
            CrossPanelBridge.getInstance(project).notifyContextSettingsChanged()
        } catch (e: Exception) {
            Dev.warn(log, "context.settings.apply_notify_failed", e)
        }
    }

    override fun reset() {
        // Load from persisted state — populates the UI with saved values.
        val state = settingsState.state
        workingContextEnabled = state.contextEnabled
        workingDefaultBypassMode = state.defaultBypassMode
        workingHeuristicFilterEnabled = state.heuristicFilterEnabled

        contextEnabledCheckbox.isSelected = workingContextEnabled
        defaultBypassModeCombo.selectedItem = workingDefaultBypassMode
        heuristicFilterCheckbox.isSelected = workingHeuristicFilterEnabled

        // Sync the heuristic filter checkbox enabled state with the context toggle.
        // If context is off, the filter checkbox should be greyed out.
        heuristicFilterCheckbox.isEnabled = workingContextEnabled
    }

    override fun disposeUIResources() { /* nothing to dispose */ }

    // ── Layout Helpers ────────────────────────────────────────────────
    // Same pattern as MetricsConfigurable.

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
