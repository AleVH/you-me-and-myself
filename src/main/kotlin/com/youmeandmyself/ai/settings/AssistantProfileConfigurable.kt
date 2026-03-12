package com.youmeandmyself.ai.settings

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.FormBuilder
import com.youmeandmyself.dev.Dev
import com.youmeandmyself.profile.AssistantProfileFileManager
import com.youmeandmyself.profile.AssistantProfileService
import com.youmeandmyself.storage.StorageConfig
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.BorderFactory

/**
 * Settings page for the Assistant Profile system.
 *
 * Located at: Tools → YMM Assistant → Assistant Profile
 *
 * ## Settings
 *
 * - **Profile enabled:** Master on/off toggle. When off, no profile is attached to requests.
 * - **Profile file path:** Location of the profile.yaml file. Browse button for file selection.
 *   Reset-to-default button restores the default path.
 * - **Fallback to full profile:** When enabled and summary is unavailable, sends the full
 *   unsummarized profile. Default off. Shows permanent warning when enabled.
 * - **Quality warning:** Always visible. Reminds user that vague/contradictory directives
 *   degrade AI response quality.
 * - **Re-summarize button:** Forces re-summarization regardless of content hash.
 * - **Current summary preview:** Read-only display of the current summary text.
 *
 * ## Storage Access
 *
 * All settings are saved through [AssistantProfileService.updateSetting], which
 * delegates to [LocalStorageFacade.setConfigValue]. This configurable never
 * accesses the database directly.
 *
 * ## Tier Gating
 *
 * The entire page is visible but disabled if the user's tier doesn't include
 * ASSISTANT_PROFILE. A message explains what tier is needed.
 */
class AssistantProfileConfigurable(
    private val project: Project
) : Configurable {

    private val log = Dev.logger(AssistantProfileConfigurable::class.java)

    // ── UI Components ────────────────────────────────────────────────────

    private var enabledCheckbox: JBCheckBox? = null
    private var filePathField: TextFieldWithBrowseButton? = null
    private var fallbackCheckbox: JBCheckBox? = null
    private var summaryPreview: JTextArea? = null
    private var resummarizeButton: JButton? = null

    // ── Stored state for change detection ────────────────────────────────

    private var storedEnabled: Boolean = true
    private var storedFilePath: String = ""
    private var storedFallback: Boolean = false

    // ── Configurable Implementation ──────────────────────────────────────

    override fun getDisplayName(): String = "Assistant Profile"

    override fun createComponent(): JComponent {
        val service = AssistantProfileService.getInstance(project)

        // Load current settings from the service
        storedEnabled = service.isEnabled()
        storedFilePath = service.getProfilePath()
        storedFallback = service.isFallbackEnabled()

        // ── Enabled toggle ──────────────────────────────────────────
        enabledCheckbox = JBCheckBox("Enable assistant profile", storedEnabled).apply {
            toolTipText = "When enabled, the summarized profile is attached to every AI request."
        }

        // ── File path ───────────────────────────────────────────────
        filePathField = TextFieldWithBrowseButton().apply {
            text = storedFilePath
            addBrowseFolderListener(
                "Select Profile File",
                "Choose the YAML file containing your assistant profile directives.",
                project,
                FileChooserDescriptorFactory.createSingleFileDescriptor("yaml")
            )
        }

        val resetPathButton = JButton("Reset to Default").apply {
            addActionListener {
                val defaultPath = AssistantProfileFileManager.defaultProfilePath(StorageConfig.DEFAULT_ROOT)
                filePathField?.text = defaultPath
            }
        }

        // ── Fallback toggle ─────────────────────────────────────────
        fallbackCheckbox = JBCheckBox("Use full profile if summary unavailable", storedFallback).apply {
            toolTipText = "When enabled and the summary cannot be generated, the full " +
                    "unsummarized profile is sent with every request. This increases token usage."
        }

        val fallbackWarning = JBLabel(
            "<html><i>⚠ When active and summary unavailable, the full profile is sent with " +
                    "every request, increasing token usage and cost.</i></html>"
        )

        // ── Quality warning ─────────────────────────────────────────
        val qualityWarning = JBLabel(
            "<html><b>Tip:</b> Be specific in your profile. " +
                    "\"Use 4-space indentation\" works. \"Write good code\" doesn't. " +
                    "Vague or contradictory rules degrade AI response quality.</html>"
        )

        // ── Summary preview ─────────────────────────────────────────
        summaryPreview = JTextArea(6, 50).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            text = service.getCurrentSummary() ?: "(No summary generated yet)"
            border = BorderFactory.createTitledBorder("Current Summary Preview")
        }

        // ── Re-summarize button ─────────────────────────────────────
        resummarizeButton = JButton("Re-summarize Now").apply {
            toolTipText = "Force re-summarization regardless of whether the profile content has changed."
            addActionListener {
                service.forceSummarize()
                // Refresh preview after a short delay (summarization is async)
                // The user can also close and reopen settings to see the update.
                summaryPreview?.text = "(Summarization triggered — close and reopen to see result)"
            }
        }

        // ── Summarization bypass note ───────────────────────────────
        val bypassNote = JBLabel(
            "<html><i>Note: Profile summarization runs automatically when the profile file changes, " +
                    "regardless of the summary system's enabled/disabled setting.</i></html>"
        )

        // ── Build form ──────────────────────────────────────────────
        return FormBuilder.createFormBuilder()
            .addComponent(enabledCheckbox!!)
            .addSeparator()
            .addLabeledComponent("Profile file:", filePathField!!)
            .addComponentToRightColumn(resetPathButton)
            .addSeparator()
            .addComponent(fallbackCheckbox!!)
            .addComponent(fallbackWarning)
            .addSeparator()
            .addComponent(qualityWarning)
            .addSeparator()
            .addComponent(bypassNote)
            .addComponent(summaryPreview!!)
            .addComponent(resummarizeButton!!)
            .addComponentFillVertically(JPanel(), 0)
            .panel
    }

    override fun isModified(): Boolean {
        return enabledCheckbox?.isSelected != storedEnabled ||
                filePathField?.text != storedFilePath ||
                fallbackCheckbox?.isSelected != storedFallback
    }

    override fun apply() {
        val enabled = enabledCheckbox?.isSelected ?: return
        val filePath = filePathField?.text ?: return
        val fallback = fallbackCheckbox?.isSelected ?: return

        try {
            val service = AssistantProfileService.getInstance(project)

            service.updateSetting("assistant_profile_enabled", if (enabled) "true" else "false")
            service.updateSetting("assistant_profile_file_path", filePath)
            service.updateSetting("assistant_profile_fallback_full", if (fallback) "true" else "false")

            storedEnabled = enabled
            storedFilePath = filePath
            storedFallback = fallback

            Dev.info(log, "assistant_profile.settings.saved",
                "enabled" to enabled,
                "filePath" to filePath,
                "fallback" to fallback
            )
        } catch (e: Exception) {
            Dev.error(log, "assistant_profile.settings.save_failed", e)
        }
    }

    override fun reset() {
        enabledCheckbox?.isSelected = storedEnabled
        filePathField?.text = storedFilePath
        fallbackCheckbox?.isSelected = storedFallback
    }

    override fun disposeUIResources() {
        enabledCheckbox = null
        filePathField = null
        fallbackCheckbox = null
        summaryPreview = null
        resummarizeButton = null
    }
}