package com.youmeandmyself.profile

/**
 * Data classes for the Assistant Profile system.
 *
 * ## What is an Assistant Profile?
 *
 * A user-authored YAML file containing personality directives that shape how
 * the AI assistant behaves. Communication style, coding conventions, workflow
 * preferences — any behavioural instruction the user deems relevant.
 *
 * The profile is summarized automatically by the plugin and the summary is
 * prepended to every API request's system prompt.
 *
 * ## Profile Types in YMM (disambiguation)
 *
 * 1. **User Profile (License Tier)** — access level (Basic/Pro/Company). Managed by TierProvider.
 * 2. **Assistant Profile (this)** — personality YAML. Managed by AssistantProfileService.
 * 3. **Provider Profile (API Connection)** — API key, base URL, model. Managed by AiProfilesConfigurable.
 *
 * ## Multi-Profile Future
 *
 * At launch: one global assistant profile (id = "active").
 * Future: multiple profiles selectable per provider or per conversation.
 * The data model uses a string id (not hardcoded singleton) so multi-profile
 * can be added without changing the data classes — only AssistantProfileService
 * and the SQLite table key need to evolve.
 */

/**
 * Parsed representation of a profile.yaml file.
 *
 * @property version Schema version (currently 1). Allows future format evolution.
 * @property sections Ordered list of profile sections. May be empty (valid but useless).
 */
data class AssistantProfileData(
    val version: Int,
    val sections: List<AssistantProfileSection>
) {
    /**
     * Concatenate all section content for summarization.
     *
     * Produces a labeled text block where each section is prefixed with its label.
     * This is what gets sent to the LLM for summarization — the structure is
     * preserved so the summary can reference sections by name.
     *
     * @return Full profile text with section labels, or empty string if no sections.
     */
    fun toFullText(): String {
        if (sections.isEmpty()) return ""
        return sections.joinToString("\n\n") { section ->
            "## ${section.label}\n${section.content}"
        }
    }

    companion object {
        /** Current schema version. Bump when the YAML format changes. */
        const val CURRENT_VERSION = 1

        /** Empty profile — valid but contains no directives. */
        val EMPTY = AssistantProfileData(version = CURRENT_VERSION, sections = emptyList())
    }
}

/**
 * A single section within the assistant profile.
 *
 * Sections are user-defined — there are no predefined categories. The user can
 * create any section they want with any tag. Tags are used internally for
 * identification and future modular selection (e.g., sending only relevant
 * sections based on context).
 *
 * @property tag Unique identifier, no spaces (kebab-case or camelCase). Used for programmatic access.
 * @property label Human-readable name displayed in the settings UI.
 * @property content The actual directive text sent to the AI for summarization.
 */
data class AssistantProfileSection(
    val tag: String,
    val label: String,
    val content: String
)

/**
 * Result of validating a profile.yaml file.
 *
 * Validation is strict: if any rule fails, the entire profile is rejected.
 * The plugin continues to work without a profile — it just won't attach
 * personality directives to API requests.
 *
 * @property isValid True if the file passed all validation rules.
 * @property profile The parsed profile data. Null if validation failed.
 * @property errors List of validation error messages. Empty if valid.
 */
data class AssistantProfileValidationResult(
    val isValid: Boolean,
    val profile: AssistantProfileData?,
    val errors: List<String>
) {
    companion object {
        fun success(profile: AssistantProfileData) = AssistantProfileValidationResult(
            isValid = true,
            profile = profile,
            errors = emptyList()
        )

        fun failure(errors: List<String>) = AssistantProfileValidationResult(
            isValid = false,
            profile = null,
            errors = errors
        )

        fun failure(error: String) = failure(listOf(error))
    }
}