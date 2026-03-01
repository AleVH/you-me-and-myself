package com.youmeandmyself.ai.providers.generic

/*
 * PURPOSE
 * - Pure HTTP client for LLM endpoints. Protocol switch:
 *   - ApiProtocol.OPENAI_COMPAT → POST {base}/v1/chat/completions
 *   - ApiProtocol.GEMINI        → POST {base}/v1beta/models/{model}:generateContent
 *   - ApiProtocol.CUSTOM        → POST {base}{custom.chatPath}
 *
 * POST-REFACTORING NOTES
 * - This class is now ONLY responsible for HTTP + response parsing.
 * - All storage writes, token indexing, context capture, metadata extraction
 *   have been moved to ChatOrchestrator and SummarizationService.
 * - The mutable `capturedContext` field is GONE (was a concurrency hazard).
 * - The `project` constructor parameter is GONE (no storage access needed).
 * - Returns ProviderResponse instead of ParsedResponse.
 */

import com.youmeandmyself.ai.net.HttpClientFactory
import com.youmeandmyself.ai.providers.AiProvider
import com.youmeandmyself.ai.providers.ProviderResponse
import com.youmeandmyself.ai.providers.parsing.Confidence
import com.youmeandmyself.ai.providers.parsing.ErrorType
import com.youmeandmyself.ai.providers.parsing.ParseMetadata
import com.youmeandmyself.ai.providers.parsing.ParseStrategy
import com.youmeandmyself.ai.providers.parsing.ParsedResponse
import com.youmeandmyself.ai.providers.parsing.ResponseParser
import com.youmeandmyself.ai.settings.ApiProtocol
import com.youmeandmyself.ai.settings.CustomProtocolConfig
import com.youmeandmyself.ai.settings.RequestSettings
import com.youmeandmyself.dev.Dev
import com.youmeandmyself.storage.model.ExchangeTokenUsage
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Vendor-agnostic LLM provider — pure HTTP client.
 *
 * ## Responsibilities (after refactoring)
 *
 * 1. Build protocol-specific HTTP requests (OpenAI, Gemini, Custom)
 * 2. Send them to the configured API endpoint
 * 3. Parse raw responses via [ResponseParser]
 * 4. Return [ProviderResponse] with raw JSON + parsed result
 *
 * ## NOT Responsible For (moved to orchestrator)
 *
 * - Storage persistence (→ ChatOrchestrator.persistExchange)
 * - Token indexing (→ ChatOrchestrator.indexTokenUsage)
 * - Assistant text caching (→ ChatOrchestrator.indexAssistantText)
 * - Derived metadata (→ ChatOrchestrator.indexDerivedMetadata)
 * - IDE context capture (→ ChatOrchestrator.captureIdeContext)
 * - Session/conversation management (→ ChatOrchestrator.updateConversation)
 *
 * @param id Unique identifier matching AiProfile.id
 * @param displayName Human-friendly name for logging and UI
 * @param baseUrl API endpoint base URL
 * @param apiKey Authentication secret
 * @param model Model identifier (e.g., "gpt-4o", "gemini-1.5-pro")
 * @param protocol API format (OPENAI_COMPAT, GEMINI, CUSTOM)
 * @param custom Custom protocol settings (only when protocol=CUSTOM)
 * @param chatSettings Request params for chat (temperature, maxTokens, etc.)
 * @param summarySettings Request params for summarization
 */
class GenericLlmProvider(
    override val id: String,
    override val displayName: String,
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String?,
    private val protocol: ApiProtocol,
    private val custom: CustomProtocolConfig?,
    private val chatSettings: RequestSettings?,
    private val summarySettings: RequestSettings?
    // NOTE: `project` parameter REMOVED — no storage access needed anymore
) : AiProvider {

    private val log = Dev.logger(GenericLlmProvider::class.java)

    init {
        require(baseUrl.isNotBlank()) { "Base URL is required" }
    }

    /** Shared Ktor client with timeouts, logging, proxy, JSON. */
    private val client get() = HttpClientFactory.client

    // ==================== Public API ====================

    /**
     * Health check — calls the models listing endpoint.
     */
    override suspend fun ping(): String = when (protocol) {
        ApiProtocol.OPENAI_COMPAT -> pingOpenAiCompat()
        ApiProtocol.GEMINI        -> pingGemini()
        ApiProtocol.CUSTOM        -> pingCustom()
    }

    /**
     * Send a chat prompt. Uses [chatSettings] for request parameters.
     */
    override suspend fun chat(prompt: String): ProviderResponse {
        val settings = chatSettings ?: RequestSettings.chatDefaults()
        return when (protocol) {
            ApiProtocol.OPENAI_COMPAT -> requestOpenAiCompat(prompt, settings)
            ApiProtocol.GEMINI        -> requestGemini(prompt, settings)
            ApiProtocol.CUSTOM        -> requestCustom(prompt, settings)
        }
    }

    /**
     * Send a summarization prompt. Uses [summarySettings] for request parameters.
     */
    override suspend fun summarize(prompt: String): ProviderResponse {
        val settings = summarySettings ?: RequestSettings.summaryDefaults()
        return when (protocol) {
            ApiProtocol.OPENAI_COMPAT -> requestOpenAiCompat(prompt, settings)
            ApiProtocol.GEMINI        -> requestGemini(prompt, settings)
            ApiProtocol.CUSTOM        -> requestCustom(prompt, settings)
        }
    }

    // ==================== Response Handling ====================

    /**
     * Parse a raw HTTP response into [ProviderResponse].
     *
     * Single convergence point for all protocols. ONLY parses — no storage,
     * no indexing, no side effects.
     */
    private fun handleResponse(
        rawJson: String?,
        httpStatus: Int?,
        prompt: String
    ): ProviderResponse {
        val exchangeId = UUID.randomUUID().toString()
        val parsed = ResponseParser.parse(rawJson, httpStatus, exchangeId)

        // Logging for debugging
        if (parsed.isError) {
            Dev.warn(log, "provider.parsed_as_error", null,
                "exchangeId" to exchangeId,
                "errorType" to parsed.errorType?.name,
                "errorMessage" to parsed.errorMessage?.take(100)
            )
        } else {
            Dev.info(log, "provider.parsed_success",
                "exchangeId" to exchangeId,
                "strategy" to parsed.metadata.parseStrategy.name,
                "confidence" to parsed.metadata.confidence.name,
                "contentLength" to (parsed.rawText?.length ?: 0)
            )
        }

        // Attach token usage to ParsedResponse for the orchestrator
        val tokenUsage = parsed.metadata.tokenUsage?.let {
            ExchangeTokenUsage(
                promptTokens = it.promptTokens,
                completionTokens = it.completionTokens,
                totalTokens = it.totalTokens
            )
        }

        return ProviderResponse(
            parsed = parsed.copy(tokenUsage = tokenUsage),
            rawJson = rawJson,
            httpStatus = httpStatus,
            prompt = prompt
        )
    }

    /**
     * Create [ProviderResponse] for network failures (before any response body).
     */
    private fun handleRequestFailure(error: Exception, prompt: String): ProviderResponse {
        val exchangeId = UUID.randomUUID().toString()
        val errorJson = """{"error": "${error.message?.replace("\"", "'")}"}"""

        Dev.error(log, "provider.request_failed", error, "exchangeId" to exchangeId)

        return ProviderResponse(
            parsed = ParsedResponse.error(
                errorMessage = error.message,
                errorType = ErrorType.NETWORK_ERROR,
                exchangeId = exchangeId,
                metadata = ParseMetadata(
                    parseStrategy = ParseStrategy.FAILED,
                    confidence = Confidence.HIGH
                )
            ),
            rawJson = errorJson,
            httpStatus = null,
            prompt = prompt
        )
    }

    // ==================== Protocol: OpenAI-Compatible ====================

    private suspend fun pingOpenAiCompat(): String {
        val url = normalizeBaseUrl(baseUrl) + "/v1/models"
        val resp = client.get(url) {
            header(HttpHeaders.Authorization, "Bearer $apiKey")
        }
        return "${resp.status.value}"
    }

    /**
     * OpenAI-compatible chat completion request.
     *
     * Works with: OpenAI, Together, Groq, Anyscale, local Ollama with OpenAI adapter,
     * and any provider that implements the OpenAI chat completions endpoint.
     */
    private suspend fun requestOpenAiCompat(
        prompt: String,
        settings: RequestSettings
    ): ProviderResponse {
        val url = normalizeBaseUrl(baseUrl) + "/v1/chat/completions"
        val m = model ?: "gpt-4o-mini"

        val payload = OpenAiChatRequest(
            model = m,
            messages = listOf(OpenAiMessage(role = "user", content = prompt)),
            temperature = settings.temperature,
            max_tokens = settings.maxTokens,
            top_p = settings.topP,
            frequency_penalty = settings.frequencyPenalty,
            presence_penalty = settings.presencePenalty,
            stop = settings.stopSequences?.takeIf { it.isNotEmpty() }
        )

        Dev.info(log, "openai.request",
            "url" to url, "model" to m,
            "temperature" to settings.temperature,
            "maxTokens" to settings.maxTokens,
            "promptLength" to prompt.length,
            "promptPreview" to Dev.preview(prompt, 200)
        )

        return try {
            val resp: HttpResponse = client.post(url) {
                header(HttpHeaders.Authorization, "Bearer $apiKey")
                contentType(ContentType.Application.Json)
                setBody(payload)
            }
            val rawResponse = resp.bodyAsText()

            Dev.info(log, "openai.raw_response",
                "status" to resp.status.value,
                "length" to rawResponse.length,
                "preview" to Dev.preview(rawResponse, 500)
            )

            handleResponse(rawResponse, resp.status.value, prompt)
        } catch (e: Exception) {
            handleRequestFailure(e, prompt)
        }
    }

    // ==================== Protocol: Gemini ====================

    private suspend fun pingGemini(): String {
        val url = normalizeBaseUrl(baseUrl) + "/v1beta/models"
        val resp = client.get(url) {
            url { parameters.append("key", apiKey) }
        }
        Dev.info(log, "gemini.ping.result", "status" to "${resp.status.value}")
        return "${resp.status.value}"
    }

    /**
     * Google Gemini generateContent request.
     *
     * Gemini uses a different request shape (contents/parts) and auth mechanism
     * (API key in query parameter instead of Bearer token).
     */
    private suspend fun requestGemini(
        prompt: String,
        settings: RequestSettings
    ): ProviderResponse {
        val m = requireNotNull(model) { "Model required for GEMINI" }
        val url = normalizeBaseUrl(baseUrl) + "/v1beta/models/$m:generateContent"

        Dev.info(log, "gemini.request",
            "url" to url,
            "temperature" to settings.temperature,
            "maxTokens" to settings.maxTokens,
            "promptLength" to prompt.length,
            "promptPreview" to Dev.preview(prompt, 200)
        )

        // Gemini can be sensitive to unbalanced braces in prompts
        val openBraces = prompt.count { it == '{' }
        val closeBraces = prompt.count { it == '}' }
        if (openBraces != closeBraces) {
            Dev.warn(log, "gemini.prompt_unbalanced_braces", null,
                "openBraces" to openBraces, "closeBraces" to closeBraces
            )
        }

        val generationConfig = GeminiGenerationConfig(
            temperature = settings.temperature,
            maxOutputTokens = settings.maxTokens,
            topP = settings.topP,
            topK = settings.topK,
            stopSequences = settings.stopSequences?.takeIf { it.isNotEmpty() }
        )

        return try {
            val resp = client.post(url) {
                url { parameters.append("key", apiKey) }
                contentType(ContentType.Application.Json)
                setBody(GeminiRequest(
                    contents = listOf(GeminiContent(parts = listOf(GeminiPart(text = prompt)))),
                    generationConfig = generationConfig.takeIf { it.hasSettings() }
                ))
            }
            val rawResponse = resp.bodyAsText()

            Dev.info(log, "gemini.raw_response",
                "status" to resp.status.value,
                "length" to rawResponse.length,
                "preview" to Dev.preview(rawResponse, 500)
            )

            handleResponse(rawResponse, resp.status.value, prompt)
        } catch (e: Exception) {
            handleRequestFailure(e, prompt)
        }
    }

    // ==================== Protocol: Custom ====================

    /** Resolve auth header value from template (replaces {key} placeholder). */
    private fun authValueFromTemplate(template: String, key: String): String =
        template.replace("{key}", key)

    private suspend fun pingCustom(): String {
        val cfg = custom ?: CustomProtocolConfig()
        val url = normalizeBaseUrl(baseUrl) + "/v1/models"
        val resp = client.get(url) {
            header(cfg.authHeaderName, authValueFromTemplate(cfg.authHeaderValueTemplate, apiKey))
        }
        return "${resp.status.value}"
    }

    /**
     * Custom protocol request — OpenAI-compatible body with user-defined path & auth.
     *
     * For providers that use OpenAI's request format but have custom
     * authentication (different header name, different URL path, etc.)
     */
    private suspend fun requestCustom(
        prompt: String,
        settings: RequestSettings
    ): ProviderResponse {
        val cfg = custom ?: CustomProtocolConfig()
        val url = normalizeBaseUrl(baseUrl) + cfg.chatPath
        val m = model ?: "gpt-4o-mini"

        val payload = OpenAiChatRequest(
            model = m,
            messages = listOf(OpenAiMessage(role = "user", content = prompt)),
            temperature = settings.temperature,
            max_tokens = settings.maxTokens,
            top_p = settings.topP,
            frequency_penalty = settings.frequencyPenalty,
            presence_penalty = settings.presencePenalty,
            stop = settings.stopSequences?.takeIf { it.isNotEmpty() }
        )

        Dev.info(log, "custom.request",
            "url" to url, "model" to m,
            "temperature" to settings.temperature,
            "maxTokens" to settings.maxTokens,
            "promptLength" to prompt.length,
            "promptPreview" to Dev.preview(prompt, 200)
        )

        return try {
            val resp = client.post(url) {
                header(cfg.authHeaderName, authValueFromTemplate(cfg.authHeaderValueTemplate, apiKey))
                contentType(ContentType.Application.Json)
                setBody(payload)
            }
            val rawResponse = resp.bodyAsText()

            Dev.info(log, "custom.raw_response",
                "status" to resp.status.value,
                "length" to rawResponse.length,
                "preview" to Dev.preview(rawResponse, 500)
            )

            handleResponse(rawResponse, resp.status.value, prompt)
        } catch (e: Exception) {
            handleRequestFailure(e, prompt)
        }
    }

    // ==================== Helpers ====================

    /**
     * Normalize base URL: trim trailing slashes, ensure protocol.
     *
     * Preserves http:// for local models (Ollama, LM Studio) that run without TLS.
     * Only adds https:// if no protocol is specified.
     */
    private fun normalizeBaseUrl(url: String): String {
        val trimmed = url.trim().trimEnd('/')
        return when {
            trimmed.startsWith("http://") -> trimmed
            trimmed.startsWith("https://") -> trimmed
            else -> "https://$trimmed"
        }
    }

    // ==================== Request DTOs (private, never leak) ====================

    /** OpenAI-compatible chat request body. */
    @Serializable
    private data class OpenAiChatRequest(
        val model: String,
        val messages: List<OpenAiMessage>,
        val temperature: Double? = null,
        val max_tokens: Int? = null,
        val top_p: Double? = null,
        val frequency_penalty: Double? = null,
        val presence_penalty: Double? = null,
        val stop: List<String>? = null
    )

    @Serializable
    private data class OpenAiMessage(
        val role: String,
        val content: String
    )

    /** Gemini request body. */
    @Serializable
    private data class GeminiRequest(
        val contents: List<GeminiContent>,
        val generationConfig: GeminiGenerationConfig? = null
    )

    @Serializable
    private data class GeminiContent(val parts: List<GeminiPart>)

    @Serializable
    private data class GeminiPart(val text: String? = null)

    /**
     * Gemini generation config — maps our [RequestSettings] to Gemini's API format.
     */
    @Serializable
    private data class GeminiGenerationConfig(
        val temperature: Double? = null,
        val maxOutputTokens: Int? = null,
        val topP: Double? = null,
        val topK: Int? = null,
        val stopSequences: List<String>? = null
    ) {
        /** True if any parameter is set (avoid sending empty config). */
        fun hasSettings(): Boolean =
            temperature != null || maxOutputTokens != null ||
                    topP != null || topK != null || !stopSequences.isNullOrEmpty()
    }
}