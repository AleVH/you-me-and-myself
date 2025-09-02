// File: src/main/kotlin/com/example/ai/backend/AiBackendFactory.kt
package com.youmeandmyself.ai.backend

import com.youmeandmyself.ai.settings.AiSettings

/**
 * Factory class to return the correct AI backend implementation based on plugin settings.
 * This enables dynamic switching between mock/testing and real API integrations.
 */
object AiBackendFactory {

    /**
     * Returns an implementation of AiBackend based on the selected backend in settings.
     * - "mock" → returns MockAiClient
     * - Later: you can add "openai" or "ollama" backends here
     */
    fun create(): AiBackend {
        val backendSetting = AiSettings.getInstance().state.backend.lowercase()
        return when (backendSetting) {
            "mock" -> MockAiClient()
            // Future: "openai" -> OpenAiClient()
            else -> MockAiClient()  // Default fallback to avoid crashing
        }
    }
}
