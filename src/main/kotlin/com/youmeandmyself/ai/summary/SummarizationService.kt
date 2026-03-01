package com.youmeandmyself.ai.summary

import com.intellij.openapi.project.Project
import com.youmeandmyself.ai.providers.AiProvider
import com.youmeandmyself.ai.providers.ProviderResponse
import com.youmeandmyself.dev.Dev
import com.youmeandmyself.storage.LocalStorageFacade
import com.youmeandmyself.storage.model.AiExchange
import com.youmeandmyself.storage.model.ExchangePurpose
import com.youmeandmyself.storage.model.ExchangeRawResponse
import com.youmeandmyself.storage.model.ExchangeRequest
import com.youmeandmyself.storage.model.ExchangeTokenUsage
import java.time.Instant

/**
 * Shared service for generating AI-powered summaries.
 *
 * ## Why This Exists
 *
 * Summarization is used in multiple contexts:
 * - **Code summarization**: Generating file, class, method, module, and project summaries
 *   (Phase 5 hierarchical chain: METHOD → CLASS → FILE → MODULE → PROJECT)
 * - **Conversation summarization**: Compressing old conversation turns to save tokens
 *   when building context for the AI
 * - **Branching context packs**: Generating summary context for conversation branches
 *   (Smart Conversation Branching feature)
 *
 * All of these share the same pattern: take some text, apply a prompt template,
 * call the AI, store the result. This service provides that capability without
 * duplicating it across pipelines.
 *
 * ## What This Service Does
 *
 * 1. Accepts text content + a prompt template
 * 2. Calls the AI provider's summarize() endpoint
 * 3. Persists the exchange to storage
 * 4. Indexes token usage and assistant text
 * 5. Returns the result
 *
 * ## What This Service Does NOT Do
 *
 * - Decide WHAT to summarize (that's the pipeline's job)
 * - Decide WHEN to summarize (that's the pipeline's or scheduler's job)
 * - Manage the summary hierarchy (that's Phase 5's job)
 * - Handle staleness or refresh logic (that's the pipeline's job)
 *
 * ## Company Tier
 *
 * In the company tier, the code summarization pipeline will use this service
 * the same way — but the results may be stored in shared storage in addition
 * to local storage. The service itself doesn't need to change; the pipeline
 * controls where results go.
 *
 * @param project The IntelliJ project context
 * @param storage Persistence layer for exchanges
 */
class SummarizationService(
    private val project: Project,
    private val storage: LocalStorageFacade
) {
    private val log = Dev.logger(SummarizationService::class.java)

    /**
     * Generate a summary using the given AI provider and prompt template.
     *
     * ## Flow
     *
     * 1. Combine the content with the prompt template
     * 2. Call provider.summarize() (HTTP only)
     * 3. Save the exchange to storage (JSONL + SQLite)
     * 4. Index token usage for cost tracking
     * 5. Return the result
     *
     * ## Error Handling
     *
     * Provider failures are captured in the response (isError=true with error message).
     * Storage failures are logged but don't block the result — the summary text is
     * still returned to the caller even if persistence failed.
     *
     * @param provider The AI provider to use for summarization
     * @param content The text content to summarize (code, conversation history, etc.)
     * @param promptTemplate The template that frames the summarization request.
     *                       Should contain a {content} placeholder that gets replaced.
     * @param purpose The exchange purpose for storage routing (FILE_SUMMARY, etc.)
     *                Determines which storage folder the exchange goes to.
     * @param metadata Optional key-value pairs stored alongside the exchange
     *                 (e.g., filePath for code summaries, conversationId for conversation summaries)
     * @return The summarization result with the generated summary text and metadata
     */
    suspend fun summarize(
        provider: AiProvider,
        content: String,
        promptTemplate: String,
        purpose: ExchangePurpose,
        metadata: Map<String, String> = emptyMap()
    ): SummarizationResult {
        // Build the effective prompt by inserting content into the template
        val effectivePrompt = if (promptTemplate.contains("{content}")) {
            promptTemplate.replace("{content}", content)
        } else {
            // If no placeholder, append content after the template
            "$promptTemplate\n\n$content"
        }

        Dev.info(log, "summarization.start",
            "purpose" to purpose.name,
            "contentLength" to content.length,
            "promptLength" to effectivePrompt.length,
            "metadata" to metadata.toString()
        )

        // Call the AI provider (HTTP only — provider returns ProviderResponse)
        val response = provider.summarize(effectivePrompt)

        Dev.info(log, "summarization.response",
            "exchangeId" to response.exchangeId,
            "isError" to response.isError,
            "summaryLength" to (response.parsed.rawText?.length ?: 0)
        )

        // Persist the exchange to storage
        persistSummaryExchange(response, provider, purpose)

        // Index token usage for cost tracking
        indexTokens(response)

        // Build the result
        return SummarizationResult(
            summaryText = response.parsed.rawText ?: "",
            isError = response.isError,
            errorMessage = response.parsed.errorMessage,
            exchangeId = response.exchangeId,
            tokenUsage = response.parsed.tokenUsage?.let { usage ->
                ExchangeTokenUsage(
                    promptTokens = usage.promptTokens,
                    completionTokens = usage.completionTokens,
                    totalTokens = usage.totalTokens
                )
            },
            metadata = metadata
        )
    }

    // ── Private: Storage ─────────────────────────────────────────────────

    /**
     * Persist the summary exchange to storage.
     *
     * Same "save immediately" pattern as ChatOrchestrator — raw data is
     * preserved before any indexing.
     */
    private suspend fun persistSummaryExchange(
        response: ProviderResponse,
        provider: AiProvider,
        purpose: ExchangePurpose
    ) {
        try {
            val exchange = AiExchange(
                id = response.exchangeId,
                timestamp = Instant.now(),
                providerId = provider.id,
                modelId = provider.displayName,
                purpose = purpose,
                request = ExchangeRequest(
                    input = response.prompt,
                    contextFiles = null
                ),
                rawResponse = ExchangeRawResponse(
                    json = response.rawJson ?: "",
                    httpStatus = response.httpStatus
                ),
                tokenUsage = null
            )

            val projectId = storage.resolveProjectId()
            storage.saveExchange(exchange, projectId)

            Dev.info(log, "summarization.saved",
                "exchangeId" to response.exchangeId,
                "purpose" to purpose.name
            )
        } catch (e: Exception) {
            Dev.error(log, "summarization.save_failed", e,
                "exchangeId" to response.exchangeId
            )
        }
    }

    /**
     * Index token usage from the summary response.
     */
    private suspend fun indexTokens(response: ProviderResponse) {
        val usage = response.parsed.metadata.tokenUsage ?: return

        try {
            val tokenUsage = ExchangeTokenUsage(
                promptTokens = usage.promptTokens,
                completionTokens = usage.completionTokens,
                totalTokens = usage.totalTokens
            )
            storage.updateTokenUsage(response.exchangeId, tokenUsage)
        } catch (e: Exception) {
            Dev.warn(log, "summarization.token_index_failed", e,
                "exchangeId" to response.exchangeId
            )
        }
    }

    companion object {
        /**
         * Factory for creating a SummarizationService with standard dependencies.
         *
         * @param project The IntelliJ project
         * @return A wired SummarizationService ready for use
         */
        fun getInstance(project: Project): SummarizationService {
            return SummarizationService(
                project = project,
                storage = LocalStorageFacade.getInstance(project)
            )
        }
    }
}

/**
 * Result of a summarization request.
 *
 * Contains the generated summary and metadata about the operation.
 * Callers (pipelines, context builders) use this to store, display,
 * or chain summaries.
 *
 * @property summaryText The AI-generated summary. Empty string if the request failed.
 * @property isError True if the summarization failed (provider error, network issue, etc.)
 * @property errorMessage Human-readable error description. Null if successful.
 * @property exchangeId Storage ID for this exchange. Used for audit trail and debugging.
 * @property tokenUsage Token counts from the provider. Null if not available.
 * @property metadata Caller-provided metadata that was passed through (e.g., filePath, level).
 */
data class SummarizationResult(
    val summaryText: String,
    val isError: Boolean,
    val errorMessage: String?,
    val exchangeId: String,
    val tokenUsage: ExchangeTokenUsage?,
    val metadata: Map<String, String>
)