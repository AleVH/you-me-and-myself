package com.youmeandmyself.ai.settings

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.youmeandmyself.dev.Dev
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import java.awt.BorderLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.*

/**
 * General settings page for YMM Assistant.
 *
 * ## Location in Settings Tree
 *
 * Settings → Tools → YMM Assistant → General
 *
 * ## What Lives Here
 *
 * This page covers plugin-wide configuration that doesn't belong to
 * AI profiles (AiProfilesConfigurable) or summarization (SummaryConfigurable):
 *
 * - Storage root path — where chats and summaries are saved on disk.
 *   This is architecturally critical: the company tier shares summaries
 *   by pointing all developers at the same storage root (e.g. a shared
 *   network drive or synced folder). Individual tier uses a local path.
 *
 * - Keep tabs on restart — whether open conversation tabs are restored
 *   when the IDE reopens. Persisted to [TabSettingsState] XML.
 *
 * - Maximum open tabs — hard cap on simultaneous open tabs (2–20).
 *   Persisted to [TabSettingsState] XML. Frontend wiring is a separate task.
 *
 * - Scan for new added files — triggers an import flow to index JSONL
 *   files that were added to the storage root manually (e.g. copied from
 *   another machine or exported from another tool).
 *   [PLACEHOLDER — button shown, import flow not yet implemented]
 *
 * - Rebuild index— wipes SQLite and rebuilds from all JSONL files.
 *   Currently, does a full rescan (all files, every line). Incremental
 *   indexing (skip already-known exchange IDs) is planned but not yet
 *   implemented. [PLACEHOLDER for incremental — full rebuild is functional]
 *
 * ## Working Copy Pattern
 *
 * Same as SummaryConfigurable: load on reset(), only persist on apply().
 * Users can change fields and cancel without saving.
 *
 * ## Storage Root and Company Tier
 *
 * The storage root path determines where ALL plugin data lives:
 *   {storageRoot}/
 *     chat/{projectId}/exchanges-(any).jsonl ← conversation history
 *     summaries/{projectId}/(any).jsonl ← code summaries
 *     ymm.db ← SQLite index (rebuildable)
 *
 * For the company tier, summaries are shared by pointing multiple
 * developers at the same storageRoot. Chat history is always private —
 * this is enforced structurally (chat/ folder is never shared).
 *
 * Changing the storage root requires a rebuild of the SQLite index
 * from the new location. The UI warns the user about this.
 *
 * @param project The IntelliJ project these settings belong to
 */
class GeneralConfigurable(private val project: Project) : Configurable {

    private val log = Dev.logger(GeneralConfigurable::class.java)

    // ── Services ──────────────────────────────────────────────────────
    //
    // No monolithic GeneralSettingsState — settings are modularized:
    //   Tab preferences → TabSettingsState (XML)
    //   Storage root    → storage_config SQLite table (via LocalStorageFacade)

    private val tabSettingsState get() = TabSettingsState.getInstance(project)
    private val storageFacade get() = com.youmeandmyself.storage.LocalStorageFacade.getInstance(project)

    // ── Working copy ──────────────────────────────────────────────────

    private var workingStorageRoot: String = defaultStorageRoot()
    private var workingKeepTabs: Boolean = true
    private var workingMaxTabs: Int = 5

    // ── UI components ─────────────────────────────────────────────────

    private val root = JPanel(BorderLayout(0, 12))

    // Storage root — text field + browse button
    private val storageRootField = TextFieldWithBrowseButton()

    // Chat tabs
    private val keepTabsCheckbox = JCheckBox("Restore open tabs when IDE restarts")
    private val maxTabsSpinner = JSpinner(SpinnerNumberModel(5, 2, 20, 1))

    // Index management buttons
    private val scanNewFilesButton = JButton("Scan for new added files")
    private val rebuildIndexButton = JButton("Rebuild index from all files")

    // ── Configurable interface ────────────────────────────────────────

    override fun getDisplayName(): String = "General"

    override fun createComponent(): JComponent {
        val formPanel = JPanel(GridBagLayout())
        val gbc = GridBagConstraints().apply {
            insets = Insets(4, 8, 4, 8)
            anchor = GridBagConstraints.WEST
            fill = GridBagConstraints.HORIZONTAL
        }

        var row = 0

        // ── Section: Storage ──────────────────────────────────────────

        row = addSeparator(formPanel, gbc, row, "Storage")

        // Storage root path
        storageRootField.addBrowseFolderListener(
            "Select Storage Root",
            "All chats and summaries will be stored here. " +
                    "For company tier: point all developers at the same shared folder.",
            project,
            FileChooserDescriptorFactory.createSingleFolderDescriptor()
        )
        row = addFormRow(formPanel, gbc, row,
            label = "Storage root:",
            component = storageRootField,
            help = "Where all plugin data is stored on disk. " +
                    "Default: ~/YouMeAndMyself/. " +
                    "Company tier: set this to a shared network folder so summaries " +
                    "are visible to all team members. Chat history stays private " +
                    "regardless — only the summaries/ subfolder is shared."
        )

        // Storage root change warning
        val storageWarning = JBLabel(
            "<html><i>⚠ Changing the storage root requires a full index rebuild from the new location.</i></html>"
        ).apply { foreground = java.awt.Color(180, 130, 0) }
        row = addFormRow(formPanel, gbc, row,
            label = "",
            component = storageWarning,
            help = ""
        )

        // ── Section: Chat Tabs ────────────────────────────────────────

        row = addSeparator(formPanel, gbc, row, "Chat Tabs")

        // Keep tabs
        row = addFormRow(formPanel, gbc, row,
            label = "",
            component = keepTabsCheckbox,
            help = "When enabled, your open conversation tabs are saved when you close the IDE " +
                    "and restored next time you open it. Disabling starts you fresh on each restart."
        )

        // Max tabs
        row = addFormRow(formPanel, gbc, row,
            label = "Maximum open tabs:",
            component = maxTabsSpinner,
            help = "Hard cap on simultaneous open conversation tabs (2–20). Default: 5. " +
                    "More tabs = more memory used by the embedded browser."
        )

        // ── Section: Index Management ─────────────────────────────────

        row = addSeparator(formPanel, gbc, row, "Index Management")

        val indexNote = JBLabel(
            "<html>The SQLite index is rebuilt from JSONL files on disk. " +
                    "JSONL files are the source of truth — the index is always recoverable.</html>"
        )
        row = addFormRow(formPanel, gbc, row, label = "", component = indexNote, help = "")

        // Scan for new files button
        scanNewFilesButton.addActionListener { onScanNewFiles() }
        row = addFormRow(formPanel, gbc, row,
            label = "",
            component = scanNewFilesButton,
            help = "Scans the storage root for JSONL files that have been added manually " +
                    "(e.g. copied from another machine or exported from another tool) " +
                    "and imports them into the index. " +
                    "Before pressing: make sure the files are in the correct location " +
                    "under the storage root, and are valid YMM JSONL format. " +
                    "A confirmation dialog will show how many new files were found. " +
                    "PLACEHOLDER: the import flow is not yet implemented. " +
                    "To implement: query SQLite for known file names, walk storage root " +
                    "for .jsonl files not in that list, show confirmation dialog with count, " +
                    "then run JsonlRebuildService on new files only."
        )

        val scanNotice = placeholderLabel(
            "PLACEHOLDER — import flow not yet implemented. Button shows intent only."
        )
        row = addFormRow(formPanel, gbc, row, label = "", component = scanNotice, help = "")

        // Rebuild full index button
        rebuildIndexButton.addActionListener { onRebuildIndex() }
        row = addFormRow(formPanel, gbc, row,
            label = "",
            component = rebuildIndexButton,
            help = "Wipes the SQLite index and rebuilds it from scratch by reading " +
                    "every JSONL file in the storage root. Use this if the index seems " +
                    "corrupted or out of sync. " +
                    "WARNING: currently does a FULL rescan — reads every line of every file. " +
                    "For large storage roots this can be slow. " +
                    "Planned improvement: incremental rebuild that skips exchange IDs " +
                    "already present in the index (check id field against chat_exchanges table). " +
                    "This is the JsonlRebuildService.rebuildFromDirectory() path."
        )

        // Push to top
        gbc.gridy = row
        gbc.weighty = 1.0
        gbc.fill = GridBagConstraints.BOTH
        formPanel.add(JPanel(), gbc)

        root.add(JScrollPane(formPanel), BorderLayout.CENTER)

        reset()
        return root
    }

    override fun isModified(): Boolean {
        // Storage root — compare against SQLite storage_config
        val currentRoot = try {
            storageFacade.getConfigValue("storage_root_path") ?: defaultStorageRoot()
        } catch (e: Exception) {
            defaultStorageRoot()
        }
        if (storageRootField.text != currentRoot) return true

        // Tab settings — compare against TabSettingsState XML
        val tabState = tabSettingsState.state
        if (keepTabsCheckbox.isSelected != tabState.keepTabs) return true
        if ((maxTabsSpinner.value as Int) != tabState.maxTabs) return true

        return false
    }

    override fun apply() {
        // ── Tab settings → TabSettingsState XML ──
        val newTabState = tabSettingsState.state.copy(
            keepTabs = keepTabsCheckbox.isSelected,
            maxTabs = maxTabsSpinner.value as Int
        )
        tabSettingsState.loadState(newTabState)

        // ── Storage root → SQLite storage_config ──
        val newRoot = storageRootField.text
        val currentRoot = try {
            storageFacade.getConfigValue("storage_root_path") ?: defaultStorageRoot()
        } catch (e: Exception) {
            defaultStorageRoot()
        }
        if (newRoot != currentRoot) {
            storageFacade.setConfigValue("storage_root_path", newRoot)
            Dev.info(log, "general.settings.storage_root_changed",
                "from" to currentRoot, "to" to newRoot)
            // NOTE: Full reinitialization with the new root is a separate task.
            // The value is persisted; it will take effect on next IDE restart.
        }

        Dev.info(log, "general.settings.applied",
            "storageRoot" to newRoot,
            "keepTabs" to keepTabsCheckbox.isSelected,
            "maxTabs" to maxTabsSpinner.value
        )
    }

    override fun reset() {
        // Storage root: read from SQLite storage_config
        workingStorageRoot = try {
            storageFacade.getConfigValue("storage_root_path") ?: defaultStorageRoot()
        } catch (e: Exception) {
            defaultStorageRoot()
        }

        // Tab settings: read from TabSettingsState XML
        val tabState = tabSettingsState.state
        workingKeepTabs = tabState.keepTabs
        workingMaxTabs = tabState.maxTabs

        // Update UI
        storageRootField.text = workingStorageRoot
        keepTabsCheckbox.isSelected = workingKeepTabs
        maxTabsSpinner.value = workingMaxTabs
    }

    override fun disposeUIResources() { /* nothing to dispose */ }

    // ── Button Handlers ───────────────────────────────────────────────

    /**
     * Handle "Scan for new added files" button.
     *
     * PLACEHOLDER: the actual import flow is not yet implemented.
     * Shows a dialog explaining what the button will do when implemented.
     *
     * Full implementation steps:
     * 1. Query SQLite chat_exchanges for all distinct raw_file values (known files)
     * 2. Walk storageRoot for all .jsonl files
     * 3. Find files NOT in the known set
     * 4. Show confirmation: "Found X new files. Import them?"
     * 5. Run JsonlRebuildService on those files only (not full rescan)
     * 6. Show result: "Imported N exchanges from X files"
     */
    private fun onScanNewFiles() {
        JOptionPane.showMessageDialog(
            root,
            """
            PLACEHOLDER — Scan for new added files
            
            This button will scan the storage root for JSONL files that 
            have been added manually and are not yet in the index.
            
            Before using this feature, ensure:
            • Files are placed under the configured storage root
            • Files are valid YMM JSONL format
            • You have applied any storage root changes first
            
            The import process will:
            1. Find JSONL files not yet known to the index
            2. Show you how many were found (confirmation required)
            3. Import only the new files — not a full rescan
            
            Implementation needed:
            → Query chat_exchanges.raw_file for known files
            → Walk storageRoot for unknown .jsonl files  
            → Run JsonlRebuildService on new files only
            → Incremental: skip exchange IDs already in chat_exchanges
            """.trimIndent(),
            "Not Yet Implemented",
            JOptionPane.INFORMATION_MESSAGE
        )
    }

    /**
     * Handle "Rebuild index from all files" button.
     *
     * Full rebuild via JsonlRebuildService.rebuildFromDirectory() is functional.
     * What's missing: progress indicator + incremental option.
     *
     * PLACEHOLDER for incremental mode — currently always full rescan.
     */
    private fun onRebuildIndex() {
        val confirm = JOptionPane.showConfirmDialog(
            root,
            """
            Rebuild the SQLite index from all JSONL files in the storage root?
            
            • The existing index will be wiped and rebuilt from scratch
            • All JSONL files will be re-read (full rescan — can be slow for large storage)
            • No data will be lost — JSONL files are the source of truth
            
            Note: This currently rescans ALL files regardless of whether they've 
            been indexed before. Incremental rebuild (skip known exchange IDs) 
            is planned but not yet implemented.
            
            Continue?
            """.trimIndent(),
            "Rebuild Index",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE
        )

        if (confirm == JOptionPane.YES_OPTION) {
            ProgressManager.getInstance().run(
                object : Task.Backgroundable(project, "Rebuilding index from JSONL files", false) {
                    override fun run(indicator: ProgressIndicator) {
                        indicator.isIndeterminate = true
                        indicator.text = "Scanning JSONL files..."
                        try {
                            val stats = storageFacade.rebuildIndex()
                            ApplicationManager.getApplication().invokeLater {
                                JOptionPane.showMessageDialog(root,
                                    "Rebuild complete:\n\n" +
                                        "  Files scanned: ${stats.filesScanned}\n" +
                                        "  Lines read: ${stats.linesRead}\n" +
                                        "  Exchanges imported: ${stats.imported}\n" +
                                        "  Skipped (duplicate): ${stats.skippedDuplicate}\n" +
                                        "  Skipped (no content): ${stats.skippedNoContent}\n" +
                                        "  Skipped (not JSON): ${stats.skippedNotJson}\n" +
                                        "  Enriched: ${stats.enriched}",
                                    "Rebuild Complete",
                                    JOptionPane.INFORMATION_MESSAGE)
                            }
                        } catch (e: Exception) {
                            Dev.error(log, "general.settings.rebuild_failed", e)
                            ApplicationManager.getApplication().invokeLater {
                                JOptionPane.showMessageDialog(root,
                                    "Rebuild failed: ${e.message}",
                                    "Error",
                                    JOptionPane.ERROR_MESSAGE)
                            }
                        }
                    }
                }
            )
        }
    }

    // ── Layout Helpers ────────────────────────────────────────────────

    /** Same pattern as SummaryConfigurable. [Label] [Component] [?] */
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

    private fun helpIcon(tooltip: String): JLabel {
        return JBLabel("?").apply {
            toolTipText = "<html><body style='width: 250px;'>$tooltip</body></html>"
            font = font.deriveFont(font.size2D - 1f)
            foreground = java.awt.Color.GRAY
            cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
        }
    }

    /** Small italic amber label for placeholder notices. */
    private fun placeholderLabel(text: String): JBLabel {
        return JBLabel("<html><i>⚙ $text</i></html>").apply {
            foreground = java.awt.Color(140, 100, 0)
            font = font.deriveFont(font.size2D - 1f)
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private fun defaultStorageRoot(): String {
        return System.getProperty("user.home") + "/YouMeAndMyself"
    }

}