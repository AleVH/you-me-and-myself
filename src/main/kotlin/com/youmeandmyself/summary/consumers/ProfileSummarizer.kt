package com.youmeandmyself.summary.consumers

import com.intellij.openapi.diagnostic.Logger
import com.youmeandmyself.dev.Dev

/**
 * Stub: Summarizes a user's AI profile YAML into a concise representation.
 *
 * ## What This Will Do (Block 4)
 *
 * When a user edits their AI profile (provider settings, system prompts,
 * custom instructions), this summarizer produces a concise faithful summary
 * that preserves ALL directives. The summary is used by ContextAssembler
 * to inject profile context into chat prompts without sending the full
 * verbose YAML every time.
 *
 * ## Trigger
 *
 * Triggered by the Profile system when a profile is saved/edited.
 * The trigger point will be in the profile settings UI save handler
 * (Block 4 — Profile module).
 *
 * ## Exchange Purpose
 *
 * Uses [com.youmeandmyself.storage.model.ExchangePurpose.PROFILE_SUMMARY].
 * Routes to summaries/ folder via isSummaryType.
 *
 * ## Prompt Template
 *
 * Uses the PROFILE_TEMPLATE from [com.youmeandmyself.summary.pipeline.SummaryExtractor].
 * Template will take profile YAML sections and produce a concise summary
 * preserving all directives faithfully. The template is currently a placeholder
 * stub with TODO.
 *
 * ## Execution
 *
 * Delegates to [com.youmeandmyself.summary.pipeline.SummarizationService]
 * with PROFILE_SUMMARY purpose. Same single execution path as all other
 * summarization: SummarizationService → provider + JSONL + SQLite.
 *
 * ## Owner
 *
 * Block 4 (Profile system). This stub exists so the summary module's
 * multi-consumer architecture is visible in the code. When Block 4 starts,
 * the developer sees this stub and knows exactly where to wire in.
 *
 * ## Design Doc Reference
 *
 * YMM_Implementation_Plan_March2026.docx, Block 4 (Profile).
 */
object ProfileSummarizer {

    private val log = Logger.getInstance(ProfileSummarizer::class.java)

    /**
     * Summarize a user's AI profile YAML.
     *
     * NOT IMPLEMENTED YET — Block 4.
     *
     * When implemented, this will:
     * 1. Accept the profile YAML content
     * 2. Build a prompt using SummaryExtractor.getTemplate(PROFILE_SUMMARY)
     * 3. Call SummarizationService.summarize() with PROFILE_SUMMARY purpose
     * 4. Return the concise summary preserving all directives
     *
     * @param profileYaml The full profile YAML content to summarize
     * @param profileId Identifier for the profile (for cache key / metadata)
     * @return The summarized profile text, or null (stub — always returns null)
     */
    fun summarizeProfile(profileYaml: String, profileId: String): String? {
        Dev.info(log, "profile_summarizer.stub_called",
            "profileId" to profileId,
            "yamlLength" to profileYaml.length,
            "status" to "NOT_IMPLEMENTED",
            "message" to "PROFILE_SUMMARY: Will summarize user's AI profile YAML. " +
                    "Triggered on profile edit. Uses SummarizationService with PROFILE_SUMMARY " +
                    "purpose and profile template. Not implemented yet — see Block 4."
        )
        return null
    }
}