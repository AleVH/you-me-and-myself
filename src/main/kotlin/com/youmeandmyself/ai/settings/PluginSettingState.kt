// ==========================
// File: src/main/kotlin/com/youmeandmyself/ai/settings/PluginSettingsState.kt
// ==========================
package com.youmeandmyself.ai.settings

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.project.Project

/**
 * Persistent settings for API keys and optional base URLs.
 * Declared as project-level so each IntelliJ project can keep its own keys.
 */
@State(name = "YmmAssistantSettings", storages = [Storage("ymm-assistant.xml")])
@Service(Service.Level.PROJECT)
class PluginSettingsState : PersistentStateComponent<PluginSettingsState> {
    var openAiApiKey: String? = null
    var openAiBaseUrl: String? = null
    var openAiModel: String? = null

    var geminiApiKey: String? = null
    var geminiBaseUrl: String? = null
    var deepSeekApiKey: String? = null
    var deepSeekBaseUrl: String? = null

    // Allowed values: "openai", "gemini", "deepseek", "mock"
    // Purpose: remember the user's explicit provider choice for ChatPanel and elsewhere.
    var selectedProvider: String? = null

    override fun getState(): PluginSettingsState = this
    override fun loadState(state: PluginSettingsState) {
        this.openAiApiKey = state.openAiApiKey
        this.openAiBaseUrl = state.openAiBaseUrl
        this.openAiModel = state.openAiModel

        this.geminiApiKey = state.geminiApiKey
        this.geminiBaseUrl = state.geminiBaseUrl
        this.deepSeekApiKey = state.deepSeekApiKey
        this.deepSeekBaseUrl = state.deepSeekBaseUrl

        // Keep the selected provider in sync when settings are loaded
        this.selectedProvider = state.selectedProvider
    }

    companion object {
        fun getInstance(project: Project): PluginSettingsState =
            project.getService(PluginSettingsState::class.java)
    }
}