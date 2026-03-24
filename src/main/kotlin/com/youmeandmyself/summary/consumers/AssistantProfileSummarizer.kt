package com.youmeandmyself.summary.consumers

import com.youmeandmyself.ai.providers.AiProvider
import com.youmeandmyself.dev.Dev
import com.youmeandmyself.storage.model.ExchangePurpose
import com.youmeandmyself.summary.pipeline.SummarizationResult
import com.youmeandmyself.summary.pipeline.SummarizationService
import com.youmeandmyself.summary.pipeline.SummaryExtractor

/**
 * Summarizer for the Assistant Profile system.
 *
 * ## What This Does
 *
 * Takes the full profile text (all sections concatenated with labels),
 * applies the PROFILE_TEMPLATE prompt, and calls SummarizationService
 * to generate a concise summary preserving all directives.
 *
 * ## What This Does NOT Do
 *
 * - Decide WHEN to summarize (AssistantProfileService does that via hash comparison)
 * - Resolve WHICH provider to use (AssistantProfileService does that)
 * - Store the summary in the assistant_profile_summary table (AssistantProfileService does that)
 * - Manage retries or backoff (AssistantProfileService does that)
 *
 * This class is stateless. It receives everything it needs as parameters.
 *
 *
 * ## Multi-Profile Future
 *
 * When multiple assistant profiles are supported, this class won't change.
 * AssistantProfileService will call it once per profile with different content.
 * The profileId in metadata allows storage to distinguish them.
 */
object AssistantProfileSummarizer {

    private val log = Dev.logger(AssistantProfileSummarizer::class.java)

    /**
     * Summarize the assistant profile content.
     *
     * @param provider The AI provider to use for summarization
     * @param profileText The full profile text (from [AssistantProfileData.toFullText])
     * @param service The SummarizationService that handles the AI call and persistence
     * @param profileId The profile identifier (default "active" at launch).
     *   Stored in metadata for future multi-profile support.
     * @return SummarizationResult with the generated summary, or an error result
     */
    suspend fun summarize(
        provider: AiProvider,
        profileText: String,
        service: SummarizationService,
        profileId: String = "active"
    ): SummarizationResult {
        if (profileText.isBlank()) {
            Dev.info(log, "assistant_profile.summarizer.empty_profile")
            return SummarizationResult(
                summaryText = "",
                isError = true,
                errorMessage = "Profile has no content to summarize.",
                exchangeId = "",
                tokenUsage = null,
                metadata = mapOf("profileId" to profileId)
            )
        }

        val template = SummaryExtractor.getTemplate(ExchangePurpose.PROFILE_SUMMARY)

        Dev.info(log, "assistant_profile.summarizer.start",
            "profileId" to profileId,
            "contentLength" to profileText.length,
            "templateLength" to template.length
        )

        val result = service.summarize(
            provider = provider,
            content = profileText,
            promptTemplate = template,
            purpose = ExchangePurpose.PROFILE_SUMMARY,
            metadata = mapOf(
                "profileId" to profileId,
                "source" to "assistant_profile"
            )
        )

        if (result.isError) {
            Dev.warn(log, "assistant_profile.summarizer.failed", null,
                "profileId" to profileId,
                "error" to (result.errorMessage ?: "unknown")
            )
        } else {
            Dev.info(log, "assistant_profile.summarizer.success",
                "profileId" to profileId,
                "summaryLength" to result.summaryText.length,
                "exchangeId" to result.exchangeId
            )
        }

        return result
    }
}