// ==========================
// File: src/main/kotlin/com/youmeandmyself/ai/providers/ProviderRegistry.kt
// ==========================
// path: src/main/kotlin/com/youmeandmyself/ai/providers/ProviderRegistry.kt — Provider registry exposing configured providers
package com.youmeandmyself.ai.providers

import com.youmeandmyself.ai.settings.PluginSettingsState
import com.intellij.openapi.project.Project
import com.youmeandmyself.ai.providers.deepseek.DeepSeekProvider
import com.youmeandmyself.ai.providers.gemini.GeminiProvider
import com.youmeandmyself.ai.providers.openai.OpenAIProvider

/**
 * Registry that exposes only the providers enabled/configured in settings.
 * Requires a Project argument since settings are project-level.
 */
object ProviderRegistry {

    /**
     * NEW: Returns *all* known providers (including those not configured).
     * Used by the "AI Providers Test" status view to show a complete picture.
     * - If an API key/base URL is missing, the provider itself should surface
     *   a clear status (e.g., "not configured (no API key)" or a 401).
     */
    fun allProviders(project: Project): List<AiProvider> {
        val s = PluginSettingsState.getInstance(project)

        val list = mutableListOf<AiProvider>()

        // Keep Mock first so the UI always shows a quick "ok (simulated)" line.
        list += MockProvider

        // Note: We pass empty strings if keys are missing so providers can report
        // a precise status instead of being omitted from the list.
        list += OpenAIProvider(s.openAiApiKey?.trim().orEmpty(), s.openAiBaseUrl, s.openAiModel)
        list += GeminiProvider(s.geminiApiKey?.trim().orEmpty(), s.geminiBaseUrl)
        list += DeepSeekProvider(s.deepSeekApiKey?.trim().orEmpty(), s.deepSeekBaseUrl)

        return list
    }

    /**
     * EXISTING: Returns only the providers that are configured/enabled.
     * Left unchanged for any existing call sites that rely on "active only".
     */
    fun activeProviders(project: Project): List<AiProvider> {
        val s = PluginSettingsState.getInstance(project)
        val list = mutableListOf<AiProvider>()
        list += MockProvider
        val openAiKey = s.openAiApiKey?.trim()
        if (!openAiKey.isNullOrBlank()) list += OpenAIProvider(openAiKey, s.openAiBaseUrl, s.openAiModel)

        val geminiKey = s.geminiApiKey?.trim()
        if (!geminiKey.isNullOrBlank()) list += GeminiProvider(geminiKey, s.geminiBaseUrl)

        val deepSeekKey = s.deepSeekApiKey?.trim()
        if (!deepSeekKey.isNullOrBlank()) list += DeepSeekProvider(deepSeekKey, s.deepSeekBaseUrl)
        return list
    }

    // Purpose: snapshot mutable settings before checks/usage to avoid smart-cast errors
    fun openAiProvider(project: Project): OpenAIProvider? {
        val s = PluginSettingsState.getInstance(project)

        // Snapshot the fields once
        val key: String? = s.openAiApiKey?.trim()
        val base: String? = s.openAiBaseUrl
        val model = s.openAiModel

        // Guard and return using the snapshot (no double reads)
        if (key.isNullOrBlank()) return null
        return OpenAIProvider(key, base, model)
    }

    /**
     * Returns only the provider explicitly selected in settings — or null if
     * either nothing is selected or the chosen provider isn’t configured.
     *
     * Valid ids (case-insensitive): "openai", "gemini", "deepseek", "mock"
     */
    fun selectedProvider(project: Project): AiProvider? {
        val s = PluginSettingsState.getInstance(project)

        when (s.selectedProvider?.lowercase()) {
            "openai" -> {
                // Reuse existing guard logic (returns null if not configured)
                return openAiProvider(project)
            }
            "gemini" -> {
                val key = s.geminiApiKey?.trim()
                if (!key.isNullOrBlank()) {
                    return GeminiProvider(key, s.geminiBaseUrl)
                }
            }
            "deepseek" -> {
                val key = s.deepSeekApiKey?.trim()
                if (!key.isNullOrBlank()) {
                    return DeepSeekProvider(key, s.deepSeekBaseUrl)
                }
            }
            "mock" -> {
                return MockProvider
            }
        }

        // Either nothing selected or the selected provider isn’t configured.
        return null
    }

}
