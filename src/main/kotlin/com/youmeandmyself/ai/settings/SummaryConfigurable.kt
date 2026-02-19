// File: src/main/kotlin/com/youmeandmyself/ai/settings/SummaryConfigurable.kt
package com.youmeandmyself.ai.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.youmeandmyself.context.orchestrator.config.SummaryConfig
import com.youmeandmyself.context.orchestrator.config.SummaryConfigService
import com.youmeandmyself.context.orchestrator.config.SummaryMode
import com.youmeandmyself.dev.Dev
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.*

/**
 * Settings UI panel for summary configuration.
 *
 * ## How IntelliJ Settings Work (Quick Primer)
 *
 * IntelliJ's settings system uses the [Configurable] interface. Here's what each method does:
 *
 * - [getDisplayName]: The label shown in Settings → Tools → YMM Assistant → **Summary**
 * - [createComponent]: Builds the Swing UI. Called once when the settings page is opened.
 * - [isModified]: IntelliJ calls this frequently to decide whether to enable the Apply button.
 *   Returns true if the UI fields differ from the persisted state.
 * - [apply]: Called when user clicks OK or Apply. This is where we persist changes.
 * - [reset]: Called on dialog open and when user clicks Reset. Loads persisted state into UI.
 * - [disposeUIResources]: Cleanup when settings dialog closes.
 *
 * ## UI Layout
 *
 * The panel uses GridBagLayout which is verbose but gives precise control over
 * label/field alignment. Each row is a label on the left and a control on the right.
 *
 * ## The "?" Help Icons
 *
 * Each field has a "?" label that shows a tooltip on hover, explaining what the
 * setting does. This helps users who aren't familiar with the summarization system.
 *
 * ## Working Copy Pattern
 *
 * Like AiProfilesConfigurable, we load a working copy on reset() and only persist
 * on apply(). This means users can change things, see the effect, and cancel without
 * saving. The kill switch is special — it saves immediately (via SummaryConfigService).
 *
 * @param project The IntelliJ project these settings belong to
 */
class SummaryConfigurable(private val project: Project) : Configurable {

    // ==================== SERVICE ====================

    /** The config service — our single point of contact for summary settings. */
    private val configService: SummaryConfigService by lazy {
        SummaryConfigService.getInstance(project)
    }

    // ==================== WORKING COPY ====================

    /**
     * Working copy of the config, loaded from service on reset().
     * UI fields read from / write to this. Only persisted on apply().
     */
    private var workingConfig = SummaryConfig()

    // ==================== UI COMPONENTS ====================

    // Main container
    private val root = JPanel(BorderLayout(0, 12))

    // Kill switch — the big on/off toggle at the top
    private val enabledCheckbox = JCheckBox("Enable summarization")

    // Mode selector
    private val modeCombo = JComboBox(SummaryMode.values())

    // Dry-run toggle
    private val dryRunCheckbox = JCheckBox("Dry-run mode (plan only, no API calls)")

    // Budget fields
    private val maxTokensField = JBTextField()
    private val minFileLinesField = JBTextField()
    private val complexityField = JBTextField()

    // Scope pattern text areas
    private val includePatternsArea = JTextArea(4, 30)
    private val excludePatternsArea = JTextArea(4, 30)

    // Status labels (read-only, updated on reset)
    private val tokensUsedLabel = JBLabel("0")
    private val budgetRemainingLabel = JBLabel("unlimited")
    private val queueSizeLabel = JBLabel("0")

    // Reset session counter button
    private val resetCounterButton = JButton("Reset Session Counter")

    // ==================== CONFIGURABLE INTERFACE ====================

    /**
     * Display name shown in the settings tree.
     * Appears as: Settings → Tools → YMM Assistant → Summary
     */
    override fun getDisplayName(): String = "Summary"

    /**
     * Build the entire settings UI.
     *
     * This is called once when the user opens the settings page.
     * All Swing components are created and laid out here.
     */
    override fun createComponent(): JComponent {

        // ── Top: Kill Switch ──
        // This is prominently placed at the very top because it's the most
        // important control. Users need to find it instantly.

        val topPanel = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(enabledCheckbox)
            add(helpIcon("Master switch. When off, NO summarization happens regardless of other settings."))
        }

        // ── Middle: All settings in a form layout ──

        val formPanel = JPanel(GridBagLayout())
        val gbc = GridBagConstraints().apply {
            insets = Insets(4, 8, 4, 8)  // padding around each cell
            anchor = GridBagConstraints.WEST
            fill = GridBagConstraints.HORIZONTAL
        }

        var row = 0

        // ── Mode ──
        row = addFormRow(formPanel, gbc, row,
            label = "Mode:",
            component = modeCombo,
            help = "OFF: nothing happens. ON DEMAND: only when you request. " +
                    "SMART BACKGROUND: auto-summarizes within budget. " +
                    "SUMMARIZE PATH: coming soon."
        )

        // Set up the mode combo to show display names instead of enum names
        modeCombo.renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean
            ): Component {
                val c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus) as JLabel
                val mode = value as? SummaryMode
                if (mode != null) {
                    c.text = mode.displayName
                    c.toolTipText = mode.description
                }
                return c
            }
        }

        // ── Dry Run ──
        row = addFormRow(formPanel, gbc, row,
            label = "",  // No label — checkbox has its own text
            component = dryRunCheckbox,
            help = "When enabled, the system evaluates what WOULD be summarized " +
                    "(checks scope, budget, etc.) but skips the actual API call. " +
                    "Great for testing your configuration without spending tokens."
        )

        // ── Separator: Budget ──
        row = addSeparator(formPanel, gbc, row, "Budget")

        // ── Max Tokens Per Session ──
        maxTokensField.columns = 10
        row = addFormRow(formPanel, gbc, row,
            label = "Max tokens per session:",
            component = maxTokensField,
            help = "Maximum tokens to spend on summaries per project session. " +
                    "Leave blank for unlimited. A session starts when you open the project " +
                    "and ends when you close it."
        )

        // ── Min File Lines ──
        minFileLinesField.columns = 10
        row = addFormRow(formPanel, gbc, row,
            label = "Min file lines:",
            component = minFileLinesField,
            help = "Skip files shorter than this many lines. " +
                    "Small files are usually simple enough to read directly. " +
                    "Leave blank to summarize files of any size."
        )

        // ── Complexity Threshold ──
        complexityField.columns = 10
        row = addFormRow(formPanel, gbc, row,
            label = "Complexity threshold (1-10):",
            component = complexityField,
            help = "Only auto-summarize files with estimated complexity above this level. " +
                    "1 = summarize almost everything, 10 = only very complex files. " +
                    "Leave blank to ignore complexity."
        )

        // ── Separator: Scope ──
        row = addSeparator(formPanel, gbc, row, "Scope")

        // ── Include Patterns ──
        includePatternsArea.lineWrap = true
        includePatternsArea.wrapStyleWord = true
        val includeScroll = JScrollPane(includePatternsArea).apply {
            preferredSize = Dimension(300, 80)
        }
        row = addFormRow(formPanel, gbc, row,
            label = "Include patterns:",
            component = includeScroll,
            help = "Glob patterns for files to include (one per line). " +
                    "Example: *.kt, *.java, src/main/**. " +
                    "Leave empty to include ALL files (subject to exclude patterns)."
        )

        // ── Exclude Patterns ──
        excludePatternsArea.lineWrap = true
        excludePatternsArea.wrapStyleWord = true
        val excludeScroll = JScrollPane(excludePatternsArea).apply {
            preferredSize = Dimension(300, 80)
        }
        row = addFormRow(formPanel, gbc, row,
            label = "Exclude patterns:",
            component = excludeScroll,
            help = "Glob patterns for files to SKIP (one per line). " +
                    "Example: *Test*, */build/*, *.min.js. " +
                    "Exclude patterns take priority over include patterns."
        )

        // ── Separator: Session Status ──
        row = addSeparator(formPanel, gbc, row, "Session Status")

        // ── Tokens Used ──
        row = addFormRow(formPanel, gbc, row,
            label = "Tokens used this session:",
            component = tokensUsedLabel,
            help = "Total tokens spent on summaries since you opened this project."
        )

        // ── Budget Remaining ──
        row = addFormRow(formPanel, gbc, row,
            label = "Budget remaining:",
            component = budgetRemainingLabel,
            help = "How many tokens you can still spend before hitting the session cap."
        )

        // ── Queue Size ──
        row = addFormRow(formPanel, gbc, row,
            label = "Files queued:",
            component = queueSizeLabel,
            help = "Number of files waiting to be summarized."
        )

        // ── Reset Counter Button ──
        resetCounterButton.addActionListener {
            configService.resetSessionCounter()
            updateStatusLabels()
        }
        row = addFormRow(formPanel, gbc, row,
            label = "",
            component = resetCounterButton,
            help = "Reset the session token counter to zero."
        )

        // ── Push everything to the top ──
        // Without this, GridBagLayout would center everything vertically
        gbc.gridy = row
        gbc.weighty = 1.0
        gbc.fill = GridBagConstraints.BOTH
        formPanel.add(JPanel(), gbc)  // Empty spacer panel

        // ── Assemble ──
        root.add(topPanel, BorderLayout.NORTH)
        root.add(JScrollPane(formPanel), BorderLayout.CENTER)

        // ── Wire listeners for immediate UI feedback ──
        enabledCheckbox.addActionListener { onEnabledChanged() }

        // Load initial data
        reset()

        return root
    }

    /**
     * Check if any UI field differs from the persisted config.
     *
     * IntelliJ calls this frequently (on every keystroke, focus change, etc.)
     * to decide whether to enable the Apply button. Keep it fast.
     */
    override fun isModified(): Boolean {
        val current = readFromUI()
        return current != workingConfig
    }

    /**
     * Persist UI state to the config service.
     *
     * Called when user clicks OK or Apply. Writes to SQLite via
     * SummaryConfigService → LocalStorageFacade.
     */
    override fun apply() {
        workingConfig = readFromUI()
        configService.updateConfig(workingConfig)
        configService.apply()  // Persist to SQLite

        Dev.info(
            com.intellij.openapi.diagnostic.Logger.getInstance(SummaryConfigurable::class.java),
            "settings.applied",
            "mode" to workingConfig.mode.name,
            "enabled" to workingConfig.enabled
        )
    }

    /**
     * Load persisted config into the UI.
     *
     * Called on dialog open and when user clicks Reset.
     * Discards any unsaved changes.
     */
    override fun reset() {
        workingConfig = configService.getConfig()
        writeToUI(workingConfig)
        updateStatusLabels()
    }

    override fun disposeUIResources() { /* nothing to dispose */ }

    // ==================== UI ↔ Config Conversion ====================

    /**
     * Read all UI fields and construct a SummaryConfig.
     *
     * Handles parsing text fields to integers safely — blank or invalid
     * values become null (meaning "not set" / "unlimited").
     */
    private fun readFromUI(): SummaryConfig {
        return SummaryConfig(
            mode = modeCombo.selectedItem as? SummaryMode ?: SummaryMode.OFF,
            enabled = enabledCheckbox.isSelected,
            maxTokensPerSession = maxTokensField.text.trim().toIntOrNull(),
            tokensUsedSession = configService.getTokensUsedThisSession(),
            complexityThreshold = complexityField.text.trim().toIntOrNull(),
            includePatterns = parsePatterns(includePatternsArea.text),
            excludePatterns = parsePatterns(excludePatternsArea.text),
            minFileLines = minFileLinesField.text.trim().toIntOrNull(),
            dryRun = dryRunCheckbox.isSelected
        )
    }

    /**
     * Write a SummaryConfig to all UI fields.
     *
     * The reverse of readFromUI(). Null integer values become blank fields.
     */
    private fun writeToUI(config: SummaryConfig) {
        enabledCheckbox.isSelected = config.enabled
        modeCombo.selectedItem = config.mode
        dryRunCheckbox.isSelected = config.dryRun
        maxTokensField.text = config.maxTokensPerSession?.toString() ?: ""
        minFileLinesField.text = config.minFileLines?.toString() ?: ""
        complexityField.text = config.complexityThreshold?.toString() ?: ""
        includePatternsArea.text = config.includePatterns.joinToString("\n")
        excludePatternsArea.text = config.excludePatterns.joinToString("\n")

        updateEnabledState()
    }

    /**
     * Update the session status labels (read-only display).
     */
    private fun updateStatusLabels() {
        val config = configService.getConfig()
        tokensUsedLabel.text = config.tokensUsedSession.toString()
        budgetRemainingLabel.text = config.remainingBudget?.let { "$it tokens" } ?: "unlimited"

        // Queue size from SummaryStore
        try {
            val store = com.youmeandmyself.context.orchestrator.SummaryStore::class.java
                .let { project.getService(it) }
            queueSizeLabel.text = store.queue.size().toString()
        } catch (_: Throwable) {
            queueSizeLabel.text = "N/A"
        }
    }

    // ==================== UI Helpers ====================

    /**
     * Handle the kill switch toggle.
     *
     * When the kill switch changes, we update the enabled/disabled
     * state of all other controls. Disabled controls are greyed out,
     * making it visually clear that summarization is off.
     */
    private fun onEnabledChanged() {
        updateEnabledState()
    }

    /**
     * Enable/disable all controls based on the kill switch.
     *
     * When summarization is disabled, all other controls are greyed out.
     * This provides clear visual feedback: "nothing will happen."
     */
    private fun updateEnabledState() {
        val enabled = enabledCheckbox.isSelected

        modeCombo.isEnabled = enabled
        dryRunCheckbox.isEnabled = enabled
        maxTokensField.isEnabled = enabled
        minFileLinesField.isEnabled = enabled
        complexityField.isEnabled = enabled
        includePatternsArea.isEnabled = enabled
        excludePatternsArea.isEnabled = enabled
        resetCounterButton.isEnabled = enabled
    }

    /**
     * Parse a text area's content into a list of glob patterns.
     *
     * Splits by newline, trims whitespace, removes empty lines.
     * This is the reverse of joinToString("\n").
     */
    private fun parsePatterns(text: String): List<String> {
        return text.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    // ==================== Layout Helpers ====================

    /**
     * Add a form row with label, component, and help icon.
     *
     * ## How GridBagLayout Works (Quick Primer)
     *
     * GridBagLayout positions components in a grid. Each component gets a
     * GridBagConstraints that says:
     * - gridx, gridy: which cell (column, row)
     * - weightx: how much extra horizontal space this column gets (0 = none, 1 = all)
     * - fill: should the component stretch to fill its cell?
     * - insets: padding around the component
     *
     * Our layout has 3 columns: [Label] [Component] [?]
     *
     * @return The next row number
     */
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
        gbc.fill = GridBagConstraints.HORIZONTAL

        // Column 0: Label
        gbc.gridx = 0
        gbc.weightx = 0.0
        gbc.fill = GridBagConstraints.NONE
        panel.add(JBLabel(label), gbc)

        // Column 1: Component (gets all extra horizontal space)
        gbc.gridx = 1
        gbc.weightx = 1.0
        gbc.fill = GridBagConstraints.HORIZONTAL
        panel.add(component, gbc)

        // Column 2: Help icon
        gbc.gridx = 2
        gbc.weightx = 0.0
        gbc.fill = GridBagConstraints.NONE
        panel.add(helpIcon(help), gbc)

        return row + 1
    }

    /**
     * Add a separator row with a title.
     *
     * Creates a visual divider between sections of the form.
     * Looks like: ── Budget ──────────────────────
     *
     * @return The next row number
     */
    private fun addSeparator(
        panel: JPanel,
        gbc: GridBagConstraints,
        row: Int,
        title: String
    ): Int {
        gbc.gridy = row
        gbc.gridx = 0
        gbc.gridwidth = 3  // Span all columns
        gbc.weightx = 1.0
        gbc.weighty = 0.0
        gbc.fill = GridBagConstraints.HORIZONTAL
        gbc.insets = Insets(12, 8, 4, 8)  // Extra top padding for visual separation

        val separator = JPanel(BorderLayout()).apply {
            add(JBLabel("<html><b>$title</b></html>"), BorderLayout.WEST)
            add(JSeparator(), BorderLayout.CENTER)
        }
        panel.add(separator, gbc)

        // Reset gridwidth and insets for subsequent rows
        gbc.gridwidth = 1
        gbc.insets = Insets(4, 8, 4, 8)

        return row + 1
    }

    /**
     * Create a "?" help icon with a tooltip.
     *
     * On hover, shows a tooltip explaining what the setting does.
     * Uses a small, unobtrusive "?" label so it doesn't clutter the UI.
     */
    private fun helpIcon(tooltip: String): JLabel {
        return JBLabel("?").apply {
            toolTipText = "<html><body style='width: 250px;'>$tooltip</body></html>"
            font = font.deriveFont(font.size2D - 1f)
            foreground = java.awt.Color.GRAY
            cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
        }
    }
}