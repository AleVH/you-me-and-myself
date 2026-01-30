package com.youmeandmyself.ai.providers

import com.intellij.openapi.project.Project
import com.youmeandmyself.ai.settings.AiProfilesState
import com.youmeandmyself.ai.settings.AiProfile
import com.youmeandmyself.ai.providers.generic.GenericLlmProvider
import com.youmeandmyself.dev.Dev

/**
 * Registry that exposes only the providers enabled/configured in settings.
 * Requires a Project argument since settings are project-level.
 */
object ProviderRegistry {

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
     * @param profile The AI profile with configuration
     * @param project The IntelliJ project (needed for storage, etc.)
     */
    private fun providerFromProfile(profile: AiProfile, project: Project): AiProvider {
        // NOTE: For now, we pass through profile.apiKey (plaintext in state).
        // In phase 2, swap to PasswordSafe via a Secrets helper.
        return GenericLlmProvider(
            id = (profile.providerId.ifBlank { "generic" }).lowercase(),
            displayName = profile.label.ifBlank { profile.providerId.ifBlank { "Generic LLM" } },
            baseUrl = profile.baseUrl,
            apiKey = profile.apiKey,
            model = profile.model,
            protocol = profile.protocol!!, // ensured by migration in loadState
            custom = profile.custom,
            project = project
        )
    }

    private fun isValidProfile(profile: AiProfile): Boolean {
        return profile.apiKey.isNotBlank() &&
                profile.baseUrl.isNotBlank() &&
                profile.model?.isNotBlank() == true
    }
}