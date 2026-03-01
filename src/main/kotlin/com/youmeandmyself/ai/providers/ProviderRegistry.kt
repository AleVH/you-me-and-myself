package com.youmeandmyself.ai.providers

import com.intellij.openapi.project.Project
import com.youmeandmyself.ai.settings.AiProfilesState
import com.youmeandmyself.ai.settings.AiProfile
import com.youmeandmyself.ai.providers.generic.GenericLlmProvider
import com.youmeandmyself.dev.Dev

/**
 * Registry that resolves which AI provider to use for different tasks.
 *
 * ## Purpose
 *
 * This is the single point where the plugin asks "give me a provider for X".
 * It reads from AiProfilesState, applies selection logic, and returns a
 * ready-to-use AiProvider instance.
 *
 * ## Selection Logic
 *
 * For both chat and summary:
 * 1. Filter profiles that have the required role enabled AND are valid
 * 2. If there's an explicit selection (selectedChatProfileId/selectedSummaryProfileId), use it
 * 3. Otherwise, auto-select the newest valid profile (UUIDs are time-based)
 *
 * ## Profile Validity
 *
 * A profile is valid if it has:
 * - Non-blank API key
 * - Non-blank base URL
 * - Non-blank model
 *
 * ## Why Object (Singleton)?
 *
 * The registry is stateless — it just reads from AiProfilesState and creates providers.
 * Making it an object simplifies access from anywhere in the codebase.
 */
object ProviderRegistry {

    /**
     * Get the provider configured for chat conversations.
     *
     * Returns null if no valid chat-enabled profile exists.
     *
     * @param project The IntelliJ project (profiles are project-scoped)
     * @return AiProvider ready to handle chat requests, or null
     */
    fun selectedChatProvider(project: Project): AiProvider? {
        val ps = AiProfilesState.getInstance(project)
        val eligible = ps.profiles
            .filter { it.roles.chat && isValidProfile(it) }
            .sortedByDescending { it.id } // Newest first (UUIDs are time-based)

        if (eligible.isEmpty()) return null

        // Use explicit selection if set
        val chosenId = ps.selectedChatProfileId
        if (chosenId != null) {
            return eligible.find { it.id == chosenId }?.let { providerFromProfile(it, project) }
        }

        // Auto-selection logic: newest profile that has chat role
        val chosen = eligible.firstOrNull()
        Dev.info(Dev.logger(ProviderRegistry::class.java), "chat.autoSelect",
            "chosen" to chosen?.label, "eligible" to eligible.size)
        return chosen?.let { providerFromProfile(it, project) }
    }

    /**
     * Get the provider configured for code summarization.
     *
     * Returns null if no valid summary-enabled profile exists.
     *
     * @param project The IntelliJ project (profiles are project-scoped)
     * @return AiProvider ready to handle summary requests, or null
     */
    fun selectedSummaryProvider(project: Project): AiProvider? {
        val ps = AiProfilesState.getInstance(project)
        val eligible = ps.profiles
            .filter { it.roles.summary && isValidProfile(it) }
            .sortedByDescending { it.id } // Newest first

        if (eligible.isEmpty()) return null

        // Use explicit selection if set
        val chosenId = ps.selectedSummaryProfileId
        if (chosenId != null) {
            return eligible.find { it.id == chosenId }?.let { providerFromProfile(it, project) }
        }

        // Auto-selection logic: newest profile that has summary role
        val chosen = eligible.firstOrNull()
        Dev.info(Dev.logger(ProviderRegistry::class.java), "summary.autoSelect",
            "chosen" to chosen?.label, "eligible" to eligible.size)
        return chosen?.let { providerFromProfile(it, project) }
    }

    /**
     * Create a provider instance from a profile.
     *
     * Maps the profile's configuration to a GenericLlmProvider, including:
     * - Connection settings (baseUrl, apiKey, model, protocol)
     * - Request settings for chat and summary (temperature, maxTokens, etc.)
     *
     * @param profile The AI profile with configuration
     * @param project The IntelliJ project (needed for storage, etc.)
     * @return A configured AiProvider instance
     */
    private fun providerFromProfile(profile: AiProfile, project: Project): AiProvider {
        // NOTE: For now, we pass through profile.apiKey (plaintext in state).
        // In phase 2, swap to PasswordSafe via a Secrets helper.
        return GenericLlmProvider(
            id = profile.id,
            displayName = profile.label.ifBlank { profile.providerId.ifBlank { "Generic LLM" } },
            baseUrl = profile.baseUrl,
            apiKey = profile.apiKey,
            model = profile.model,
            protocol = profile.protocol!!, // ensured by migration in loadState
            custom = profile.custom,
            chatSettings = profile.chatSettings,
            summarySettings = profile.summarySettings,
        )
    }

    /**
     * Check if a profile has all required fields for making API calls.
     *
     * A profile needs at minimum:
     * - API key (for authentication)
     * - Base URL (where to send requests)
     * - Model (which model to use)
     */
    private fun isValidProfile(profile: AiProfile): Boolean {
        return profile.apiKey.isNotBlank() &&
                profile.baseUrl.isNotBlank() &&
                profile.model?.isNotBlank() == true
    }
}