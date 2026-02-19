// File: src/main/kotlin/com/youmeandmyself/ai/settings/AiProfileState.kt
package com.youmeandmyself.ai.settings

import com.intellij.openapi.components.*
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.XmlSerializerUtil
import com.youmeandmyself.dev.Dev
import java.util.*

/**
 * Configurable request parameters for LLM API calls.
 *
 * This class encapsulates all the knobs you can tweak when making a request to an LLM.
 * Different models respond differently to these parameters, so having them configurable
 * per-profile allows fine-tuning for each provider/model combination.
 *
 * ## Why These Parameters Exist
 *
 * - **temperature**: Controls randomness. 0.0 = deterministic (same input → same output),
 *   1.0+ = creative/random. Summaries typically want low (0.1-0.3), chat can be higher (0.7).
 *
 * - **maxTokens**: Maximum length of the response. Prevents runaway responses and controls costs.
 *   Summaries should be short (100-300), chat can be longer (2000+).
 *
 * - **topP** (nucleus sampling): Only consider tokens whose cumulative probability is within
 *   this threshold. 0.9 = consider tokens making up 90% of probability mass. Alternative to temperature.
 *
 * - **topK**: Only consider the top K most likely tokens. Some providers support this (Gemini),
 *   others don't (OpenAI). Set to null if unsupported.
 *
 * - **frequencyPenalty**: Reduce likelihood of tokens that have already appeared, proportional
 *   to frequency. Helps avoid repetitive text. Range typically -2.0 to 2.0.
 *
 * - **presencePenalty**: Reduce likelihood of tokens that have appeared at all (regardless of
 *   frequency). Encourages the model to introduce new topics. Range typically -2.0 to 2.0.
 *
 * - **stopSequences**: Custom strings that signal the model to stop generating. Useful for
 *   structured outputs. Example: ["END", "---"] would stop generation when either appears.
 *
 * - **systemPrompt**: Instructions prepended to every request. For chat, this sets the assistant's
 *   persona. For summaries, this contains the summarization instructions.
 *
 * ## XML Serialization
 *
 * All fields use `var` (mutable) with default values to ensure IntelliJ's XML serializer
 * can instantiate and populate this class. Null means "use provider default".
 *
 * ## Provider Compatibility
 *
 * Not all providers support all parameters. The provider implementation should:
 * - Use supported parameters
 * - Ignore unsupported ones (don't send them in the request)
 * - Document which parameters it supports
 */
data class RequestSettings(
    /**
     * Controls randomness in response generation.
     * - 0.0 = deterministic (always pick most likely token)
     * - 0.5-0.7 = balanced (good for most tasks)
     * - 1.0+ = creative/random (may produce nonsense at high values)
     * Null = use provider's default.
     */
    var temperature: Double? = null,

    /**
     * Maximum number of tokens in the response.
     * Protects against runaway generation and controls costs.
     * Null = use provider's default (often 4096 or unlimited).
     */
    var maxTokens: Int? = null,

    /**
     * Nucleus sampling threshold (0.0 to 1.0).
     * Only tokens with cumulative probability <= topP are considered.
     * Lower values = more focused, higher values = more diverse.
     * Typically used as alternative to temperature, not both.
     * Null = use provider's default.
     */
    var topP: Double? = null,

    /**
     * Top-K sampling: only consider the K most likely tokens.
     * Supported by some providers (Gemini), not others (OpenAI).
     * Null = use provider's default or don't send if unsupported.
     */
    var topK: Int? = null,

    /**
     * Penalty for tokens based on how often they've appeared (-2.0 to 2.0).
     * Positive values reduce repetition, negative values encourage it.
     * Null = use provider's default (usually 0).
     */
    var frequencyPenalty: Double? = null,

    /**
     * Penalty for tokens that have appeared at all (-2.0 to 2.0).
     * Positive values encourage new topics, negative values encourage staying on topic.
     * Null = use provider's default (usually 0).
     */
    var presencePenalty: Double? = null,

    /**
     * Custom stop sequences that signal the model to stop generating.
     * When any of these strings appears in the output, generation stops.
     * Example: ["END", "\n\n"] to stop at "END" or double newline.
     * Null or empty = no custom stop sequences.
     */
    var stopSequences: MutableList<String>? = null,

    /**
     * System prompt / instruction text prepended to requests.
     *
     * For chat: Sets the assistant's persona and behavior.
     * Example: "You are a helpful coding assistant specializing in Kotlin."
     *
     * For summaries: Contains the summarization instructions with placeholders.
     * Example: "Summarize this {languageId} code concisely in plain text..."
     *
     * Placeholders (for summary prompts):
     * - {languageId} = programming language (e.g., "Kotlin", "Python")
     * - {sourceText} = the code to summarize
     *
     * Null = use default prompt defined in code.
     */
    var systemPrompt: String? = null
) {
    /**
     * Check if any settings have been customized.
     * Useful for deciding whether to include settings in API requests.
     */
    fun hasCustomSettings(): Boolean {
        return temperature != null ||
                maxTokens != null ||
                topP != null ||
                topK != null ||
                frequencyPenalty != null ||
                presencePenalty != null ||
                !stopSequences.isNullOrEmpty() ||
                !systemPrompt.isNullOrBlank()
    }

    companion object {
        /**
         * Default settings optimized for chat conversations.
         * Balanced temperature for helpful but not too random responses.
         */
        fun chatDefaults(): RequestSettings = RequestSettings(
            temperature = 0.7,
            maxTokens = 2048,
            topP = 0.9
        )

        /**
         * Default settings optimized for code summarization.
         * Low temperature for consistent, factual summaries.
         * Lower max tokens since summaries should be concise.
         */
        fun summaryDefaults(): RequestSettings = RequestSettings(
            temperature = 0.2,
            maxTokens = 300,
            topP = 0.9,
            systemPrompt = """Summarize this {languageId} code concisely in plain text.
No markdown formatting, no code blocks, just a clear description of what this code does.
Start your response with "Summary: " followed by the summary text.

{sourceText}"""
        )
    }
}

/**
 * Declares which tasks/roles this profile can serve.
 *
 * A single profile can be used for multiple purposes (chat AND summary),
 * or you can have separate profiles for each task with different settings.
 *
 * The UI allows selecting different profiles for chat vs summary,
 * giving users flexibility in how they configure their AI usage.
 */
data class AiRoles(
    /** Whether this profile can be used for chat conversations. */
    var chat: Boolean = false,

    /** Whether this profile can be used for code summarization. */
    var summary: Boolean = false
)

/**
 * A reusable, labeled AI profile containing all configuration for an LLM provider.
 *
 * ## Purpose
 *
 * Profiles allow users to:
 * - Configure multiple AI providers (Gemini, OpenAI, local Ollama, etc.)
 * - Save credentials and settings for each
 * - Assign different profiles to different tasks (chat vs summary)
 * - Fine-tune parameters per profile for optimal results
 *
 * ## Structure
 *
 * A profile contains:
 * - **Identity**: id, label (user-friendly name)
 * - **Connection**: baseUrl, apiKey, protocol
 * - **Model**: which model to use
 * - **Roles**: what this profile can be used for (chat, summary)
 * - **Settings**: per-role request parameters (temperature, maxTokens, etc.)
 *
 * ## Example Configurations
 *
 * ```
 * Profile: "Gemini Chat"
 *   - baseUrl: https://generativelanguage.googleapis.com
 *   - model: gemini-2.5-flash
 *   - roles: chat=true, summary=false
 *   - chatSettings: temperature=0.7, maxTokens=2048
 *
 * Profile: "Gemini Summary"
 *   - baseUrl: https://generativelanguage.googleapis.com
 *   - model: gemini-2.5-flash
 *   - roles: chat=false, summary=true
 *   - summarySettings: temperature=0.2, maxTokens=200
 *
 * Profile: "Local Ollama"
 *   - baseUrl: http://localhost:11434
 *   - model: codellama
 *   - roles: chat=true, summary=true
 *   - chatSettings: temperature=0.8
 *   - summarySettings: temperature=0.3
 * ```
 *
 * ## XML Serialization
 *
 * All fields use `var` with defaults to support IntelliJ's XML persistence.
 * Nested objects (roles, chatSettings, summarySettings) are serialized as child elements.
 */
data class AiProfile(
    /**
     * Unique identifier for this profile.
     * Generated automatically, used for selection persistence.
     */
    var id: String = UUID.randomUUID().toString(),

    /**
     * User-friendly name displayed in the UI.
     * Example: "My Gemini", "Work OpenAI", "Local Llama"
     */
    var label: String = "",

    /**
     * Provider identifier for display and legacy compatibility.
     * Examples: "gemini", "openai", "deepseek", "ollama"
     * Note: The actual API behavior is determined by `protocol`, not this field.
     */
    var providerId: String = "",

    /**
     * API key / authentication secret.
     * TODO: Phase 2 will migrate this to PasswordSafe for secure storage.
     */
    var apiKey: String = "",

    /**
     * Base URL for the API endpoint.
     * Examples:
     * - Gemini: https://generativelanguage.googleapis.com
     * - OpenAI: https://api.openai.com
     * - Local: http://localhost:11434
     */
    var baseUrl: String = "",

    /**
     * Model identifier to use for requests.
     * Examples: "gemini-2.5-flash", "gpt-4o-mini", "codellama"
     */
    var model: String? = null,

    /**
     * Which tasks this profile can serve.
     * Allows assigning different profiles to chat vs summary.
     */
    var roles: AiRoles = AiRoles(),

    /**
     * API protocol determining request/response format and auth style.
     * - OPENAI_COMPAT: OpenAI-style API (also works for many other providers)
     * - GEMINI: Google Gemini REST API
     * - CUSTOM: User-defined paths and auth headers
     */
    var protocol: ApiProtocol? = null,

    /**
     * Custom protocol configuration when protocol=CUSTOM.
     * Allows specifying custom paths and auth headers for exotic providers.
     */
    var custom: CustomProtocolConfig? = null,

    /**
     * Request settings for chat conversations.
     * Controls temperature, max tokens, etc. when this profile is used for chat.
     * Null = use defaults defined in RequestSettings.chatDefaults()
     */
    var chatSettings: RequestSettings? = null,

    /**
     * Request settings for code summarization.
     * Controls temperature, max tokens, prompt template, etc. for summaries.
     * Null = use defaults defined in RequestSettings.summaryDefaults()
     */
    var summarySettings: RequestSettings? = null
) {
    /**
     * Get chat settings, falling back to defaults if not configured.
     */
    fun effectiveChatSettings(): RequestSettings {
        return chatSettings ?: RequestSettings.chatDefaults()
    }

    /**
     * Get summary settings, falling back to defaults if not configured.
     */
    fun effectiveSummarySettings(): RequestSettings {
        return summarySettings ?: RequestSettings.summaryDefaults()
    }
}

/**
 * Protocols supported by the GenericLlmProvider.
 *
 * Each protocol defines:
 * - How to construct the request URL
 * - How to authenticate (header vs query param)
 * - Request/response body format
 */
enum class ApiProtocol {
    /**
     * OpenAI-compatible API format.
     * Used by: OpenAI, OpenRouter, many gateways, DeepSeek, Ollama (OpenAI mode)
     * Auth: Bearer token in Authorization header
     * Endpoint: POST {base}/v1/chat/completions
     */
    OPENAI_COMPAT,

    /**
     * Google Gemini REST API format.
     * Auth: API key as query parameter
     * Endpoint: POST {base}/v1beta/models/{model}:generateContent?key={apiKey}
     */
    GEMINI,

    /**
     * User-defined custom protocol.
     * For exotic gateways that don't fit the standard patterns.
     * Uses CustomProtocolConfig for path and auth header customization.
     * Request/response body assumed to be OpenAI-compatible.
     */
    CUSTOM
}

/**
 * Configuration for custom API protocols.
 *
 * Allows users to connect to exotic gateways without code changes by specifying:
 * - Custom endpoint path
 * - Custom auth header name and value format
 *
 * The request/response body format is assumed to be OpenAI-compatible.
 */
data class CustomProtocolConfig(
    /**
     * Path appended to baseUrl for chat requests.
     * Default: "/v1/chat/completions" (OpenAI-compatible)
     */
    var chatPath: String = "/v1/chat/completions",

    /**
     * Name of the authentication header.
     * Default: "Authorization"
     */
    var authHeaderName: String = "Authorization",

    /**
     * Template for the auth header value.
     * Use {key} as placeholder for the API key.
     * Default: "Bearer {key}"
     * Example for custom: "Api-Key {key}" or just "{key}"
     */
    var authHeaderValueTemplate: String = "Bearer {key}"
)

/**
 * Persisted state containing all AI profiles and selection preferences.
 *
 * ## Scope
 *
 * This is a PROJECT-level service, meaning:
 * - Each project has its own set of profiles
 * - Each project can select different profiles for chat/summary
 * - Profiles don't automatically sync across projects
 *
 * ## Persistence
 *
 * Stored in: .idea/youmeandmyself-ai-profiles.xml
 * Uses IntelliJ's standard PersistentStateComponent mechanism.
 *
 * ## Selection Model
 *
 * Users can select different profiles for different tasks:
 * - selectedChatProfileId: which profile to use for chat
 * - selectedSummaryProfileId: which profile to use for summaries
 *
 * This allows configurations like:
 * - Use fast/cheap model for summaries, powerful model for chat
 * - Use local model for summaries (privacy), cloud for chat
 * - Use same profile for both (simplicity)
 */
@State(
    name = "YouMeAndMyself.AiProfilesState",
    storages = [Storage("youmeandmyself-ai-profiles.xml")],
    category = SettingsCategory.TOOLS
)
@Service(Service.Level.PROJECT)
class AiProfilesState : PersistentStateComponent<AiProfilesState.State> {
    private val log = Dev.logger(AiProfilesState::class.java)

    /**
     * The actual persisted state container.
     * Kept as a separate class for clean XML serialization.
     */
    class State {
        /** All configured AI profiles. */
        var profiles: MutableList<AiProfile> = mutableListOf()

        /** ID of the profile selected for chat, or null for auto-selection. */
        var selectedChatProfileId: String? = null

        /** ID of the profile selected for summaries, or null for auto-selection. */
        var selectedSummaryProfileId: String? = null
    }

    private var state = State()

    /** All configured AI profiles. */
    var profiles: MutableList<AiProfile>
        get() = state.profiles
        set(value) {
            Dev.info(log, "state.profiles.set", "count" to value.size)
            state.profiles = value
        }

    /** ID of the profile selected for chat, or null for auto-selection. */
    var selectedChatProfileId: String?
        get() = state.selectedChatProfileId
        set(value) {
            Dev.info(log, "state.chatId.set", "id" to value)
            state.selectedChatProfileId = value
        }

    /** ID of the profile selected for summaries, or null for auto-selection. */
    var selectedSummaryProfileId: String?
        get() = state.selectedSummaryProfileId
        set(value) {
            Dev.info(log, "state.summaryId.set", "id" to value)
            state.selectedSummaryProfileId = value
        }

    /**
     * Called by IntelliJ when persisting state.
     * Returns the current state for XML serialization.
     */
    override fun getState(): State {
        Dev.info(log, "state.getState", "currentProfilesSize" to state.profiles.size)
        state.profiles.forEachIndexed { i, profile ->
            Dev.info(log, "state.currentProfile",
                "index" to i,
                "label" to profile.label,
                "id" to profile.id
            )
        }
        return state
    }

    /**
     * Called by IntelliJ when loading persisted state.
     *
     * Performs:
     * 1. Cleaning of empty profiles (XML serialization artifact)
     * 2. Migration of old profiles without protocol field
     * 3. Copying incoming state to current state
     */
    override fun loadState(incoming: State) {
        Dev.info(log, "state.loadState", "incomingProfilesSize" to incoming.profiles.size)

        // Log each incoming profile for debugging
        incoming.profiles.forEachIndexed { i, profile ->
            Dev.info(log, "state.incomingProfile",
                "index" to i,
                "label" to profile.label,
                "id" to profile.id,
                "hasApiKey" to profile.apiKey.isNotBlank(),
                "hasBaseUrl" to profile.baseUrl.isNotBlank(),
                "hasModel" to (profile.model?.isNotBlank() == true)
            )
        }

        // Clean empty profiles created by XML serialization quirks
        val validProfiles = incoming.profiles.filter { profile ->
            profile.label.isNotBlank() || profile.apiKey.isNotBlank() || profile.baseUrl.isNotBlank()
        }

        if (validProfiles.size != incoming.profiles.size) {
            Dev.info(log, "state.cleaningEmptyProfiles",
                "before" to incoming.profiles.size,
                "after" to validProfiles.size
            )
            incoming.profiles.clear()
            incoming.profiles.addAll(validProfiles)
        }

        XmlSerializerUtil.copyBean(incoming, state)

        // Migration: ensure all profiles have a protocol set
        state.profiles.forEach { p ->
            if (p.protocol == null) {
                p.protocol = when (p.providerId.trim().lowercase()) {
                    "gemini" -> ApiProtocol.GEMINI
                    else -> ApiProtocol.OPENAI_COMPAT
                }
                Dev.info(log, "state.migration.protocol",
                    "profile" to p.label,
                    "protocol" to p.protocol
                )
            }
        }

        Dev.info(log, "state.loadState.complete", "finalProfilesSize" to state.profiles.size)
    }

    companion object {
        /**
         * Get the AiProfilesState service for a project.
         */
        fun getInstance(project: Project): AiProfilesState {
            val instance = project.service<AiProfilesState>()
            Dev.info(Dev.logger(AiProfilesState::class.java), "state.getInstance", "instance" to instance.hashCode())
            return instance
        }
    }

    init {
        // DEVELOPMENT RESET - remove this in production!
        // Clears profiles on each run in dev mode to avoid stale test data
        if (isDevelopmentMode()) {
            Dev.info(log, "DEVELOPMENT: Resetting profiles")
            state.profiles.clear()
            state.selectedChatProfileId = null
            state.selectedSummaryProfileId = null
        }
    }

    /**
     * Check if running in development/sandbox mode.
     * Used to reset state for clean testing.
     */
    private fun isDevelopmentMode(): Boolean {
        return System.getProperty("idea.is.internal") != null ||
                java.lang.management.ManagementFactory.getRuntimeMXBean()
                    .inputArguments.toString().contains("idea.sandbox")
    }
}