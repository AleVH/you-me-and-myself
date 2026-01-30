// File: src/main/kotlin/com/youmeandmyself/context/provider/SynopsisProviderRegistry.kt

package com.youmeandmyself.context.provider

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

/**
 * Resolves the active SynopsisProvider. You can later add UI to switch providers.
 */
@Service
class SynopsisProviderRegistry(
    private val settings: SynopsisSettings = SynopsisSettings.getInstance()
) {
    /** Choose a provider based on settings. Extend this when you add more providers. */
    fun activeProvider(): SynopsisProvider {
        val endpoint = settings.endpointUrl.orEmpty().trim()
        val apiKey = settings.apiKey.orEmpty().trim()

        // Fail fast with a clear error if not configured
        require(endpoint.isNotEmpty()) { "Synopsis endpoint URL is not configured." }
        require(apiKey.isNotEmpty()) { "Synopsis API key is not configured." }

        return KtorSynopsisProvider(
            endpointUrl = endpoint,
            apiKey = apiKey,
            maxRetries = settings.maxRetries,
            initialBackoffMs = settings.initialBackoffMs
        )
    }

    companion object {
        fun getInstance(project: Project): SynopsisProviderRegistry =
            project.service<SynopsisProviderRegistry>()
    }
}
