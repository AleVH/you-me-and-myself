// File: src/main/kotlin/com/youmeandmyself/ai/settings/AiProfileState.kt
// PURPOSE: Add ApiProtocol + CustomProtocolConfig and a simple migration rule.
package com.youmeandmyself.ai.settings

import com.intellij.openapi.components.*
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.XmlSerializerUtil
import com.youmeandmyself.dev.Dev
import java.util.*

/**
 * One reusable, labeled AI profile: vendor + credentials + model + roles.
 * Example: label="DeepSeek Prod", providerId="deepseek", apiKey="...", baseUrl, model="deepseek-chat", roles(chat=true, summary=true)
 */
// Purpose: declare which tasks this profile can serve (chat/summary)
data class AiRoles(
    var chat: Boolean = false,
    var summary: Boolean = false
)

data class AiProfile(
    var id: String = UUID.randomUUID().toString(),
    var label: String = "",
    var providerId: String = "",     // kept for display/legacy import only
    var apiKey: String = "",
    var baseUrl: String = "",
    var model: String? = null,
    var roles: AiRoles = AiRoles(),
    var protocol: ApiProtocol? = null,
    var custom: CustomProtocolConfig? = null
)

/** Protocols supported by the GenericLlmProvider. */
enum class ApiProtocol {
    OPENAI_COMPAT,  // OpenAI, OpenRouter, many gateways, often DeepSeek & Ollama when OpenAI-compatible
    GEMINI,         // Google Gemini REST
    CUSTOM          // User-specified auth header & path; request/response assumed OpenAI-compatible body
}

/** Minimal knobs for exotic gateways without adding code. */
data class CustomProtocolConfig(
    var chatPath: String = "/v1/chat/completions",
    var authHeaderName: String = "Authorization",
    var authHeaderValueTemplate: String = "Bearer {key}"
)

/**
 * Persisted state: the list of profiles + which profile ids are selected for chat/summary (can differ).
 * Project-level so different projects can pick different profiles.
 */
@State(
    name = "YouMeAndMyself.AiProfilesState",
    storages = [Storage("youmeandmyself-ai-profiles.xml")],
    category = SettingsCategory.TOOLS
)
@Service(Service.Level.PROJECT)
class AiProfilesState : PersistentStateComponent<AiProfilesState.State> {
    private val log = Dev.logger(AiProfilesState::class.java)

    class State {
        var profiles: MutableList<AiProfile> = mutableListOf()
        var selectedChatProfileId: String? = null
        var selectedSummaryProfileId: String? = null
    }

    private var state = State()

    var profiles: MutableList<AiProfile>
        get() = state.profiles
        set(value) {
            Dev.info(log, "state.profiles.set", "count" to value.size)
            state.profiles = value
        }

    var selectedChatProfileId: String?
        get() = state.selectedChatProfileId
        set(value) {
            Dev.info(log, "state.chatId.set", "id" to value)
            state.selectedChatProfileId = value
        }

    var selectedSummaryProfileId: String?
        get() = state.selectedSummaryProfileId
        set(value) {
            Dev.info(log, "state.summaryId.set", "id" to value)
            state.selectedSummaryProfileId = value
        }

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

    override fun loadState(incoming: State) {
        Dev.info(log, "state.loadState", "incomingProfilesSize" to incoming.profiles.size)

        // Log each incoming profile in detail
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

        // FIX: Clear empty profiles created by XML serialization
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

        // Migration logic
        state.profiles.forEach { p ->
            if (p.protocol == null) {
                p.protocol = when (p.providerId.trim().lowercase()) {
                    "gemini" -> ApiProtocol.GEMINI
                    else -> ApiProtocol.OPENAI_COMPAT
                }
                Dev.info(log, "state.migration",
                    "profile" to p.label,
                    "protocol" to p.protocol
                )
            }
        }

        Dev.info(log, "state.loadState.complete", "finalProfilesSize" to state.profiles.size)
    }
    companion object {
        fun getInstance(project: Project): AiProfilesState {
            val instance = project.service<AiProfilesState>()
            Dev.info(Dev.logger(AiProfilesState::class.java), "state.getInstance", "instance" to instance.hashCode())
            return instance
        }
    }

    init {
        // DEVELOPMENT RESET - remove this in production!
        if (isDevelopmentMode()) {
            Dev.info(log, "DEVELOPMENT: Resetting profiles")
            state.profiles.clear()
            state.selectedChatProfileId = null
            state.selectedSummaryProfileId = null
        }
    }

    private fun isDevelopmentMode(): Boolean {
        return System.getProperty("idea.is.internal") != null ||
                java.lang.management.ManagementFactory.getRuntimeMXBean().inputArguments.toString().contains("idea.sandbox")
    }
}

//@State(
//    name = "YouMeAndMyself.AiProfilesState",
//    storages = [Storage("youmeandmyself-ai-profiles.xml")],
//    category = SettingsCategory.TOOLS
//)
//@Service(Service.Level.PROJECT)
//class AiProfilesState : PersistentStateComponent<AiProfilesState.State> {
//
//    class State {
//        var profiles: MutableList<AiProfile> = mutableListOf()
//        var selectedChatProfileId: String? = null
//        var selectedSummaryProfileId: String? = null
//    }
//
//    private var state = State()
//
//    var profiles: MutableList<AiProfile>
//        get() = state.profiles
//        set(value) { state.profiles = value }
//
//    var selectedChatProfileId: String?
//        get() = state.selectedChatProfileId
//        set(value) { state.selectedChatProfileId = value }
//
//    var selectedSummaryProfileId: String?
//        get() = state.selectedSummaryProfileId
//        set(value) { state.selectedSummaryProfileId = value }
//
//    override fun getState(): State = state
//
//    override fun loadState(incoming: State) {
//        XmlSerializerUtil.copyBean(incoming, state)
//        // BACKWARD-COMPATIBLE MIGRATION:
//        state.profiles.forEach { p ->
//            if (p.protocol == null) {
//                p.protocol = when (p.providerId.trim().lowercase()) {
//                    "gemini" -> ApiProtocol.GEMINI
//                    else -> ApiProtocol.OPENAI_COMPAT
//                }
//            }
//        }
//    }
//
//    companion object { fun getInstance(project: Project): AiProfilesState = project.service() }
//}

//class AiProfilesState : PersistentStateComponent<AiProfilesState> {
//
//    var profiles: MutableList<AiProfile> = mutableListOf()
//
//    // explicit selections; if null we'll fall back (single profile, or to legacy PluginSettingsState fields)
//    var selectedChatProfileId: String? = null
//    var selectedSummaryProfileId: String? = null
//
//    override fun getState(): AiProfilesState = this
//
//    override fun loadState(state: AiProfilesState) {
//        XmlSerializerUtil.copyBean(state, this)
//
//        // BACKWARD-COMPATIBLE MIGRATION:
//        // If protocol is not set, infer from providerId.
//        profiles.forEach { p ->
//            if (p.baseUrl.isBlank()) {
//                // If you previously had nulls, ensure they become empty strings, then the UI will force a value on Apply.
//                p.baseUrl = p.baseUrl.trim()
//            }
//            if (p.protocol == null) {
//                p.protocol = when (p.providerId.trim().lowercase()) {
//                    "gemini" -> ApiProtocol.GEMINI
//                    // Most other vendors nowadays expose an OpenAI-compatible surface
//                    else -> ApiProtocol.OPENAI_COMPAT
//                }
//            }
//        }
//    }
//
//    companion object {
//        fun getInstance(project: Project): AiProfilesState = project.service()
//    }
//}
