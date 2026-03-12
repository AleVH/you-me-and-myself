package com.youmeandmyself.profile

import com.youmeandmyself.dev.Dev
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.error.YAMLException

/**
 * Strict validator for assistant profile YAML files.
 *
 * ## Validation Rules (from Feature Spec §3.1)
 *
 * 1. The file must be valid YAML.
 * 2. The `version` field must be present and must be a supported version number.
 * 3. The `sections` field must be present and must be an array.
 * 4. Each section must contain `tag`, `label`, and `content` fields, all non-empty strings.
 * 5. All `tag` values must be unique within the file.
 * 6. `tag` values must contain no spaces.
 *
 * ## Failure Behaviour (§3.2)
 *
 * If validation fails, the plugin displays a non-blocking warning. API requests
 * continue without a profile attached. The user is never blocked.
 *
 * ## SnakeYAML
 *
 * Uses SnakeYAML which is bundled with IntelliJ — no additional dependency needed.
 */
object AssistantProfileValidator {

    private val log = Dev.logger(AssistantProfileValidator::class.java)

    /**
     * Validate a YAML string and return a parsed [AssistantProfileData] if valid.
     *
     * @param yamlContent The raw YAML file content
     * @return Validation result with parsed profile or error messages
     */
    fun validate(yamlContent: String): AssistantProfileValidationResult {
        if (yamlContent.isBlank()) {
            return AssistantProfileValidationResult.failure("Profile file is empty.")
        }

        // ── Step 1: Parse YAML ──────────────────────────────────────────
        val parsed: Any?
        try {
            parsed = Yaml().load<Any>(yamlContent)
        } catch (e: YAMLException) {
            Dev.warn(log, "assistant_profile.validation.yaml_parse_error", e)
            return AssistantProfileValidationResult.failure(
                "Invalid YAML syntax: ${e.message?.lines()?.firstOrNull() ?: "unknown error"}"
            )
        }

        if (parsed == null || parsed !is Map<*, *>) {
            return AssistantProfileValidationResult.failure(
                "Profile must be a YAML mapping (key-value pairs) at the top level."
            )
        }

        @Suppress("UNCHECKED_CAST")
        val root = parsed as Map<String, Any?>

        // ── Step 2: Validate version ────────────────────────────────────
        val version = root["version"]
        if (version == null) {
            return AssistantProfileValidationResult.failure("Missing required field: 'version'.")
        }
        if (version !is Int) {
            return AssistantProfileValidationResult.failure(
                "Field 'version' must be an integer, got: ${version::class.simpleName}."
            )
        }
        if (version != AssistantProfileData.CURRENT_VERSION) {
            return AssistantProfileValidationResult.failure(
                "Unsupported profile version: $version. Current supported version: ${AssistantProfileData.CURRENT_VERSION}."
            )
        }

        // ── Step 3: Validate sections ───────────────────────────────────
        val sectionsRaw = root["sections"]
        if (sectionsRaw == null) {
            return AssistantProfileValidationResult.failure("Missing required field: 'sections'.")
        }
        if (sectionsRaw !is List<*>) {
            return AssistantProfileValidationResult.failure(
                "Field 'sections' must be an array, got: ${sectionsRaw::class.simpleName}."
            )
        }

        // Empty sections is valid (profile exists but has no directives yet)
        if (sectionsRaw.isEmpty()) {
            return AssistantProfileValidationResult.success(AssistantProfileData.EMPTY)
        }

        // ── Step 4: Validate each section ───────────────────────────────
        val errors = mutableListOf<String>()
        val sections = mutableListOf<AssistantProfileSection>()
        val seenTags = mutableSetOf<String>()

        for ((index, sectionRaw) in sectionsRaw.withIndex()) {
            val sectionNum = index + 1

            if (sectionRaw == null || sectionRaw !is Map<*, *>) {
                errors.add("Section $sectionNum: must be a mapping with 'tag', 'label', and 'content' fields.")
                continue
            }

            @Suppress("UNCHECKED_CAST")
            val section = sectionRaw as Map<String, Any?>

            // tag — required, non-empty string, no spaces, unique
            val tag = section["tag"]
            if (tag == null || tag !is String || tag.isBlank()) {
                errors.add("Section $sectionNum: missing or empty 'tag' field.")
                continue
            }
            if (tag.contains(' ')) {
                errors.add("Section $sectionNum: tag '$tag' must not contain spaces. Use kebab-case or camelCase.")
            }
            if (!seenTags.add(tag)) {
                errors.add("Section $sectionNum: duplicate tag '$tag'. All tags must be unique.")
            }

            // label — required, non-empty string
            val label = section["label"]
            if (label == null || label !is String || label.isBlank()) {
                errors.add("Section $sectionNum (tag='$tag'): missing or empty 'label' field.")
                continue
            }

            // content — required, non-empty string
            val content = section["content"]
            if (content == null || content !is String || content.isBlank()) {
                errors.add("Section $sectionNum (tag='$tag'): missing or empty 'content' field.")
                continue
            }

            sections.add(AssistantProfileSection(
                tag = tag,
                label = label,
                content = content.trim()
            ))
        }

        if (errors.isNotEmpty()) {
            return AssistantProfileValidationResult.failure(errors)
        }

        val profile = AssistantProfileData(
            version = version,
            sections = sections
        )

        Dev.info(log, "assistant_profile.validation.success",
            "version" to version,
            "sectionCount" to sections.size
        )

        return AssistantProfileValidationResult.success(profile)
    }
}