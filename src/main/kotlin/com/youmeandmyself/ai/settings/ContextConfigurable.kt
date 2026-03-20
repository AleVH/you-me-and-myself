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
 * User-configurable context gathering preferences. STUB at launch —
 * settings are present and persisted but not yet read by the backend.
 * The per-message bypassMode from SendMessage is the only active
 * context control during launch.
 *
 * ### Launch (Individual Basic)
 * - Enable context gathering [on/off toggle]
 * - Default bypass mode [OFF / FULL combo]
 *
 * ### Post-Launch (Pro Tier)
 * - Selective bypass mode (greyed out with "Pro" label)
 *
 * ## Working Copy Pattern
 *
 * Same as MetricsConfigurable: load on reset(), persist on apply().
 * Users can change fields and cancel without saving.
 *
 * ## Tier-Aware Display
 *
 * The SELECTIVE bypass mode option is shown greyed out for Basic-tier
 * users with a "(Pro)" label. Pro-tier users see it as a selectable
 * option in the combo box.
 *
 * @param project The IntelliJ project these settings belong to
 */
class ContextConfigurable(private val project: Project) : Configurable {

    private val log = Dev.logger(ContextConfigurable::class.java)

    // ── Services ──────────────────────────────────────────────────────
    private val settingsState get() = ContextSettingsState.getInstance(project)

    // ── Working copy ──────────────────────────────────────────────────
    private var workingContextEnabled: Boolean = true
    private var workingDefaultBypassMode: String = "FULL"

    // ── UI components ─────────────────────────────────────────────────
    private val root = JPanel(BorderLayout(0, 12))

    /** Master toggle: enable/disable context gathering globally. */
    private val contextEnabledCheckbox = JCheckBox("Enable context gathering")

    /** Default bypass mode combo: FULL (full context) or OFF (no context). */
    private val defaultBypassModeCombo = JComboBox(arrayOf("FULL", "OFF"))

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
        // No ActionListener — changes only take effect when the user clicks Apply.
        // apply() persists the state and notifies the React panel immediately.
        row = addFormRow(formPanel, gbc, row,
            label = "",
            component = contextEnabledCheckbox,
            help = "When enabled, YMM gathers IDE context (open files, project " +
                    "structure, summaries) and includes it in the prompt sent to " +
                    "the AI provider. When disabled, context gathering is skipped " +
                    "globally — applies to all tabs immediately."
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
                        "OFF = no context sent by default. Pro tier."
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
                workingDefaultBypassMode != (defaultBypassModeCombo.selectedItem as? String ?: "FULL")
    }

    override fun apply() {
        workingContextEnabled = contextEnabledCheckbox.isSelected
        workingDefaultBypassMode = defaultBypassModeCombo.selectedItem as? String ?: "FULL"

        // Persist to state
        val state = settingsState.state
        state.contextEnabled = workingContextEnabled
        state.defaultBypassMode = workingDefaultBypassMode
        settingsState.loadState(state)

        Dev.info(log, "context.settings.applied",
            "contextEnabled" to workingContextEnabled,
            "defaultBypassMode" to workingDefaultBypassMode
        )

        // Push updated settings to the React panel
        try {
            CrossPanelBridge.getInstance(project).notifyContextSettingsChanged()
        } catch (e: Exception) {
            Dev.warn(log, "context.settings.apply_notify_failed", e)
        }
    }

    override fun reset() {
        // Load from persisted state
        val state = settingsState.state
        workingContextEnabled = state.contextEnabled
        workingDefaultBypassMode = state.defaultBypassMode

        contextEnabledCheckbox.isSelected = workingContextEnabled
        defaultBypassModeCombo.selectedItem = workingDefaultBypassMode
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
