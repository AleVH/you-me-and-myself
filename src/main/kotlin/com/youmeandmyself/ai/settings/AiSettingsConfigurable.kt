// File: src/main/kotlin/com/youmeandmyself/ai/settings/AiSettingsConfigurable.kt
// path: src/main/kotlin/com/youmeandmyself/ai/settings/AiSettingsConfigurable.kt — Settings UI for OpenAI
package com.youmeandmyself.ai.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.youmeandmyself.ai.providers.openai.OpenAIProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.swing.*

/**
 * Project-level settings page: API key, base URL, and a models dropdown with "Refresh".
 * Keeps things minimal and synchronous. You can polish later.
 */
class AiSettingsConfigurable(private val project: Project) : Configurable {
    private val scope = CoroutineScope(Dispatchers.IO)

    private lateinit var panel: JPanel
    private lateinit var openAiKeyField: JTextField
    private lateinit var openAiBaseField: JTextField
    private lateinit var openAiModelBox: JComboBox<String>
    private lateinit var refreshButton: JButton

    override fun getDisplayName(): String = "YMM Assistant"

    override fun createComponent(): JComponent {
        val s = PluginSettingsState.getInstance(project)

        panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)

        openAiKeyField = JTextField(s.openAiApiKey.orEmpty())
        openAiBaseField = JTextField(s.openAiBaseUrl.orEmpty())
        openAiModelBox = JComboBox<String>().apply {
            isEditable = false
            prototypeDisplayValue = "gpt-4o-mini-long"
            if (!s.openAiModel.isNullOrBlank()) addItem(s.openAiModel)
        }
        refreshButton = JButton("Refresh OpenAI Models")

        refreshButton.addActionListener {
            val key = openAiKeyField.text?.trim().orEmpty()
            val base = openAiBaseField.text?.trim()?.ifEmpty { null }

            openAiModelBox.removeAllItems()
            openAiModelBox.addItem("Loading...")
            scope.launch {
                try {
                    val models = OpenAIProvider(key, base, null).listModels()
                    SwingUtilities.invokeLater {
                        openAiModelBox.removeAllItems()
                        if (models.isEmpty()) openAiModelBox.addItem("<no models or not configured>")
                        else models.forEach { openAiModelBox.addItem(it) }
                        // re-select saved if present
                        val saved = PluginSettingsState.getInstance(project).openAiModel
                        if (!saved.isNullOrBlank()) openAiModelBox.selectedItem = saved
                    }
                } catch (t: Throwable) {
                    SwingUtilities.invokeLater {
                        openAiModelBox.removeAllItems()
                        openAiModelBox.addItem("<error: ${t.message}>")
                    }
                }
            }
        }

        panel.add(JLabel("OpenAI API Key:"))
        panel.add(openAiKeyField)
        panel.add(Box.createVerticalStrut(8))
        panel.add(JLabel("OpenAI Base URL (optional):"))
        panel.add(openAiBaseField)
        panel.add(Box.createVerticalStrut(8))
        panel.add(JLabel("OpenAI Model:"))
        panel.add(openAiModelBox)
        panel.add(Box.createVerticalStrut(6))
        panel.add(refreshButton)

        return panel
    }

    override fun isModified(): Boolean {
        val s = PluginSettingsState.getInstance(project)
        val selected = openAiModelBox.selectedItem?.toString()?.takeIf { !it.startsWith("<") } ?: ""
        return s.openAiApiKey.orEmpty() != openAiKeyField.text.orEmpty() ||
                s.openAiBaseUrl.orEmpty() != openAiBaseField.text.orEmpty() ||
                s.openAiModel.orEmpty()   != selected
    }

    override fun apply() {
        val s = PluginSettingsState.getInstance(project)
        s.openAiApiKey = openAiKeyField.text?.trim()
        s.openAiBaseUrl = openAiBaseField.text?.trim()
        val selected = openAiModelBox.selectedItem?.toString()
        s.openAiModel = selected?.takeIf { it.isNotBlank() && !it.startsWith("<") }
    }

    override fun reset() {
        val s = PluginSettingsState.getInstance(project)
        openAiKeyField.text = s.openAiApiKey.orEmpty()
        openAiBaseField.text = s.openAiBaseUrl.orEmpty()
        openAiModelBox.removeAllItems()
        if (!s.openAiModel.isNullOrBlank()) openAiModelBox.addItem(s.openAiModel)
    }

    override fun disposeUIResources() { /* no-op */ }
}
