// ==========================
// File: src/main/kotlin/com/youmeandmyself/ai/settings/PluginSettingsConfigurable.kt
// ==========================
// path: src/main/kotlin/com/youmeandmyself/ai/settings/PluginSettingsConfigurable.kt — Settings UI
package com.youmeandmyself.ai.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import javax.swing.*

/**
 * Simple settings UI to paste API keys and optional base URLs.
 * Now bound to project-level settings via getInstance(project).
 */
class PluginSettingsConfigurable(private val project: Project) : Configurable {
    private lateinit var panel: JPanel
    private lateinit var openAiKey: JTextField
    private lateinit var openAiBase: JTextField
    private lateinit var geminiKey: JTextField
    private lateinit var geminiBase: JTextField
    private lateinit var deepSeekKey: JTextField
    private lateinit var deepSeekBase: JTextField


    override fun getDisplayName(): String = "YouMeAndMyself Assistant"

    override fun createComponent(): JComponent {
        val s = PluginSettingsState.getInstance(project)
        panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        fun row(label: String, field: JTextField) = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(JLabel(label))
            add(Box.createHorizontalStrut(8))
            add(field)
        }
        openAiKey = JTextField(s.openAiApiKey ?: "")
        openAiBase = JTextField(s.openAiBaseUrl ?: "")
        geminiKey = JTextField(s.geminiApiKey ?: "")
        geminiBase = JTextField(s.geminiBaseUrl ?: "")
        deepSeekKey = JTextField(s.deepSeekApiKey ?: "")
        deepSeekBase = JTextField(s.deepSeekBaseUrl ?: "")
        panel.add(row("OpenAI API Key", openAiKey))
        panel.add(row("OpenAI Base URL (opt)", openAiBase))
        panel.add(row("Gemini API Key", geminiKey))
        panel.add(row("Gemini Base URL (opt)", geminiBase))
        panel.add(row("DeepSeek API Key", deepSeekKey))
        panel.add(row("DeepSeek Base URL (opt)", deepSeekBase))
        return panel
    }

    override fun isModified(): Boolean {
        val s = PluginSettingsState.getInstance(project)
        return openAiKey.text != (s.openAiApiKey ?: "") ||
                openAiBase.text != (s.openAiBaseUrl ?: "") ||
                geminiKey.text != (s.geminiApiKey ?: "") ||
                geminiBase.text != (s.geminiBaseUrl ?: "") ||
                deepSeekKey.text != (s.deepSeekApiKey ?: "") ||
                deepSeekBase.text != (s.deepSeekBaseUrl ?: "")
    }

    override fun apply() {
        val s = PluginSettingsState.getInstance(project)
        s.openAiApiKey = openAiKey.text.ifBlank { null }
        s.openAiBaseUrl = openAiBase.text.ifBlank { null }
        s.geminiApiKey = geminiKey.text.ifBlank { null }
        s.geminiBaseUrl = geminiBase.text.ifBlank { null }
        s.deepSeekApiKey = deepSeekKey.text.ifBlank { null }
        s.deepSeekBaseUrl = deepSeekBase.text.ifBlank { null }
    }

    override fun reset() {
        val s = PluginSettingsState.getInstance(project)
        openAiKey.text = s.openAiApiKey.orEmpty()
        openAiBase.text = s.openAiBaseUrl.orEmpty()
        geminiKey.text = s.geminiApiKey.orEmpty()
        geminiBase.text = s.geminiBaseUrl.orEmpty()
        deepSeekKey.text = s.deepSeekApiKey.orEmpty()
        deepSeekBase.text = s.deepSeekBaseUrl.orEmpty()
    }
}
