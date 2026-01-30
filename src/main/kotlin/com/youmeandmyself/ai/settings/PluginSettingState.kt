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
//@State(name = "YmmAssistantSettings", storages = [Storage("ymm-assistant.xml")])
@Service(Service.Level.PROJECT)
class PluginSettingsState : PersistentStateComponent<PluginSettingsState> {
    // Add within your @State-backed data class or persistent bean:
    var enableSummaries: Boolean = true

    // Move caps here so they’re configurable (previously constants in MergePolicy)
    var maxFilesTotal: Int = 16
    var maxCharsPerFile: Int = 2_000 //50_000
    var maxCharsTotal: Int = 500_000

    // Summary policy
    var headerSampleMaxChars: Int = 2_000

    // Synopsis (LLM) policy
    var generateSynopsesAutomatically: Boolean = true
    var synopsisMaxTokens: Int = 700

    // Which providers are permitted for each role (empty = all active are allowed)
    var chatEnabledProviders: MutableSet<String> = mutableSetOf()
    var summaryEnabledProviders: MutableSet<String> = mutableSetOf()

    // TODO(UX): Expose the above in Settings UI (PluginSettingsConfigurable).
    // Keep off by default if you prefer, but they should be editable later.
    fun isChatAllowed(id: String): Boolean =
        chatEnabledProviders.isEmpty() || chatEnabledProviders.contains(id)

    fun isSummaryAllowed(id: String): Boolean =
        summaryEnabledProviders.isEmpty() || summaryEnabledProviders.contains(id)

    override fun getState(): PluginSettingsState = this
    override fun loadState(state: PluginSettingsState) {
        this.chatEnabledProviders = state.chatEnabledProviders
        this.summaryEnabledProviders = state.summaryEnabledProviders
    }

    companion object {
        fun getInstance(project: Project): PluginSettingsState =
            project.getService(PluginSettingsState::class.java)
    }


}