// File: src/main/kotlin/com/example/ai/settings/AiSettings.kt
package com.youmeandmyself.ai.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

/**
 * Stores plugin configuration persistently across IDE restarts.
 * This includes values like API URL, model name, timeout, and selected backend.
 */
@State(name = "AiSettings", storages = [Storage("ai_settings.xml")])
class AiSettings : PersistentStateComponent<AiSettings.State> {

    /**
     * This inner class holds all your saved preferences.
     * Fields must be mutable (`var`) for IntelliJ's serialization to work.
     */
    data class State(
        var baseUrl: String = "http://localhost:8080",  // Optional base URL for custom backend
        var model: String = "gpt-4",                    // Model name to use
        var timeoutMs: Int = 30000,                     // Request timeout (in milliseconds)
        var backend: String = "mock"                    // ⚠️ New: selected AI backend ("mock", "openai", etc.)
    )

    private var state = State()  // Holds the current user settings in memory

    override fun getState(): State = state  // Called by IntelliJ to save current settings
    override fun loadState(s: State) { state = s }  // Called by IntelliJ to restore saved settings

    companion object {
        /**
         * Globally accessible entry point to read settings from anywhere in the plugin.
         */
        fun getInstance(): AiSettings =
            ApplicationManager
                .getApplication()
                .getService(AiSettings::class.java)
    }
}
