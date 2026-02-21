package com.youmeandmyself.ai.providers.generic

/*
 * PURPOSE
 * - Single, vendor-agnostic provider that talks to LLM endpoints using a tiny protocol switch:
 *   - ApiProtocol.OPENAI_COMPAT → POST {base}/v1/chat/completions  (Authorization: Bearer <apiKey>)
 *   - ApiProtocol.GEMINI        → POST {base}/v1beta/models/{model}:generateContent?key=<apiKey>
 *   - ApiProtocol.CUSTOM        → POST {base}{custom.chatPath} with user-defined auth header/value template
 *
 * DESIGN
 * - No hardcoded providers/vendors. Everything comes from the AiProfile (baseUrl, apiKey, model, protocol, custom).
 * - Uses the project's shared HttpClientFactory.client (timeouts, logging, proxy, JSON). No per-class clients.
 * - DTOs are minimal and private to avoid leaking vendor shapes.
 * - Response parsing is delegated to ResponseParser (universal format detection).
 * - Raw responses are saved to storage IMMEDIATELY before parsing.
 * - Separate methods for chat() and summarize() with different settings and purposes.
 *
 * NOTES
 * - Keys are still plain in AiProfile for now. Phase 2 will fetch them from PasswordSafe (Secrets helper).
 */

import com.intellij.openapi.project.Project
import com.youmeandmyself.ai.net.HttpClientFactory
import com.youmeandmyself.ai.providers.AiProvider
import com.youmeandmyself.ai.providers.parsing.ParsedResponse
import com.youmeandmyself.ai.providers.parsing.ResponseParser
import com.youmeandmyself.ai.providers.parsing.ErrorType
import com.youmeandmyself.ai.providers.parsing.ParseMetadata
import com.youmeandmyself.ai.providers.parsing.ParseStrategy
import com.youmeandmyself.ai.providers.parsing.Confidence
import com.youmeandmyself.ai.settings.ApiProtocol
import com.youmeandmyself.ai.settings.CustomProtocolConfig
import com.youmeandmyself.ai.settings.RequestSettings
import com.youmeandmyself.dev.Dev
import com.youmeandmyself.storage.LocalStorageFacade
import com.youmeandmyself.storage.model.AiExchange
import com.youmeandmyself.storage.model.ExchangePurpose
import com.youmeandmyself.storage.model.ExchangeRequest
import com.youmeandmyself.storage.model.ExchangeRawResponse
import com.youmeandmyself.storage.model.ExchangeTokenUsage
import com.youmeandmyself.storage.model.DerivedMetadata
import com.youmeandmyself.storage.model.IdeContext
import com.youmeandmyself.storage.model.IdeContextCapture
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.UUID

/**
 * Vendor-agnostic LLM provider that handles multiple API protocols.
 *
 * ## Purpose
 *
 * This class is the workhorse that actually talks to LLM APIs. It abstracts away the
 * differences between OpenAI, Gemini, and custom endpoints behind a common interface.
 *
 * ## Protocol Support
 *
 * - **OPENAI_COMPAT**: Standard OpenAI API format (also used by many other providers)
 * - **GEMINI**: Google's Gemini REST API
 * - **CUSTOM**: User-defined paths and auth headers for exotic providers
 *
 * ## Chat vs Summarize
 *
 * Both methods make LLM requests but use different settings:
 * - chat(): Uses chatSettings (higher temperature, longer responses)
 * - summarize(): Uses summarySettings (lower temperature, shorter responses, different purpose)
 *
 * ## Storage
 *
 * Every request/response is saved to storage BEFORE parsing. This ensures:
 * - Raw data is never lost, even if parsing fails
 * - We have a complete audit trail
 * - Responses can be re-parsed if the parsing logic improves
 *
 * @param id Unique identifier for this provider instance
 * @param displayName Human-friendly name for UI
 * @param baseUrl API endpoint base URL
 * @param apiKey Authentication secret
 * @param model Model identifier to use
 * @param protocol Which API format to use
 * @param custom Custom protocol settings (only used when protocol=CUSTOM)
 * @param chatSettings Request parameters for chat (temperature, maxTokens, etc.)
 * @param summarySettings Request parameters for summarization
 * @param project IntelliJ project context (for storage access)
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
    private val summarySettings: RequestSettings?,
    private val project: Project
) : AiProvider {

    private val log = Dev.logger(GenericLlmProvider::class.java)

    /** Storage facade for persisting exchanges. Lazy to avoid initialization order issues. */
    private val storage: LocalStorageFacade by lazy {
        LocalStorageFacade.getInstance(project)
    }

    /*
     * SESSION ID - PLACEHOLDER
     *
     * Purpose: sessionId is intended to group related messages into a "conversation" or "thread".
     * This enables:
     * - Reconstructing conversation history from storage
     * - Searching within a specific conversation
     * - Displaying conversation threads in UI
     *
     * Current state: Using a per-instance UUID as placeholder.
     *
     * TODO: Implement proper session management:
     * - Session should be created when user starts a new conversation
     * - Session should persist across messages in the same conversation
     * - Session should be passed in from ChatPanel or managed by a SessionManager
     * - Consider: should session reset on IDE restart? On project close? User-controlled?
     */
    private val sessionId: String = UUID.randomUUID().toString()

    /**
     * IDE context captured at the moment the user sends a chat prompt.
     *
     * Set in chat() BEFORE the HTTP request, read in handleResponse() AFTER.
     * This captures what the developer was looking at when they asked the question,
     * not what they switched to while waiting for the response.
     *
     * Only relevant for CHAT exchanges — summaries are background tasks
     * with no meaningful editor context.
     */
    private var capturedContext: IdeContext = IdeContext.empty()

    init {
        require(baseUrl.isNotBlank()) { "Base URL is required" }
    }

    /** Reuse the hardened, shared Ktor client (timeouts, logging, proxy, JSON configured centrally). */
    private val client get() = HttpClientFactory.client

    // ==================== Public API ====================

    /**
     * Quick health check to verify connectivity and credentials.
     * Calls the provider's model listing endpoint (fast, low cost).
     */
    override suspend fun ping(): String = when (protocol) {
        ApiProtocol.OPENAI_COMPAT -> pingOpenAiCompat()
        ApiProtocol.GEMINI        -> pingGemini()
        ApiProtocol.CUSTOM        -> pingCustom()
    }

    /**
     * Send a chat prompt and get a parsed response.
     *
     * Uses chatSettings for request parameters.
     * Saves to storage with ExchangePurpose.CHAT.
     *
     * @param prompt The user's message/question
     * @return ParsedResponse with extracted text or error info
     */
    override suspend fun chat(prompt: String): ParsedResponse {
        val settings = chatSettings ?: RequestSettings.chatDefaults()

        // Capture IDE state NOW, before the HTTP request.
        // The caller (ChatPanel) is on EDT, so editor state is accessible.
        // By the time the response comes back, the user may have switched files.
        capturedContext = try {
            IdeContextCapture.capture(project)
        } catch (e: Exception) {
            Dev.warn(log, "context.capture_failed", e)
            IdeContext.empty()
        }

        return when (protocol) {
            ApiProtocol.OPENAI_COMPAT -> requestOpenAiCompat(prompt, settings, ExchangePurpose.CHAT)
            ApiProtocol.GEMINI        -> requestGemini(prompt, settings, ExchangePurpose.CHAT)
            ApiProtocol.CUSTOM        -> requestCustom(prompt, settings, ExchangePurpose.CHAT)
        }
    }

    /**
     * Send a summarization prompt and get a parsed response.
     *
     * Uses summarySettings for request parameters (lower temperature, shorter max tokens).
     * Saves to storage with ExchangePurpose.SUMMARY.
     *
     * @param prompt The summarization prompt (built by SummaryExtractor)
     * @return ParsedResponse with extracted summary text or error info
     */
    override suspend fun summarize(prompt: String): ParsedResponse {
        val settings = summarySettings ?: RequestSettings.summaryDefaults()
        return when (protocol) {
            ApiProtocol.OPENAI_COMPAT -> requestOpenAiCompat(prompt, settings, ExchangePurpose.FILE_SUMMARY)
            ApiProtocol.GEMINI        -> requestGemini(prompt, settings, ExchangePurpose.FILE_SUMMARY)
            ApiProtocol.CUSTOM        -> requestCustom(prompt, settings, ExchangePurpose.FILE_SUMMARY)
        }
    }

    // ==================== Unified Response Handling ====================

    /**
     * Handle the raw HTTP response: save to storage, parse, then index metadata.
     *
     * This is the single point where all protocol responses converge.
     * Flow:
     * 1. Save raw data to storage IMMEDIATELY (safety net — nothing is lost)
     * 2. Parse the response (extracts content, tokens, metadata)
     * 3. Index extracted data back into SQLite:
     *    a. Token usage (prompt, completion, total)
     *    b. Assistant text (parsed response content)
     *    c. Derived metadata (code blocks, topics, file paths, duplicate hash)
     *
     * If step 3 fails, no data is lost — everything is in the raw JSONL
     * and can be backfilled during a database rebuild.
     */
    private suspend fun handleResponse(
        rawJson: String?,
        httpStatus: Int?,
        prompt: String,
        purpose: ExchangePurpose
    ): ParsedResponse {
        val exchangeId = UUID.randomUUID().toString()

        // Step 1: Save to storage IMMEDIATELY (before any parsing)
        saveToStorage(exchangeId, rawJson, httpStatus, prompt, purpose)

        // Step 2: Parse the response (extracts content, tokens, metadata)
        val parsed = ResponseParser.parse(rawJson, httpStatus, exchangeId)

        // Step 3a: Index tokens
        parsed.metadata.tokenUsage?.let { usage ->
            val tokenUsage = ExchangeTokenUsage(
                promptTokens = usage.promptTokens,
                completionTokens = usage.completionTokens,
                totalTokens = usage.totalTokens
            )
            try {
                storage.updateTokenUsage(exchangeId, tokenUsage)
            } catch (e: Exception) {
                Dev.warn(log, "tokens.update_failed", e, "exchangeId" to exchangeId)
            }
        }

        // Step 3b: Cache assistant text
        val assistantText = parsed.rawText
        if (!assistantText.isNullOrBlank()) {
            try {
                storage.cacheAssistantText(exchangeId, assistantText)
            } catch (e: Exception) {
                Dev.warn(log, "assistant_text.cache_failed", e, "exchangeId" to exchangeId)
            }
        }

        // Step 3c: Extract and store derived metadata
        try {
            val derived = DerivedMetadata.extract(assistantText, prompt)
            storage.updateDerivedMetadata(exchangeId, derived)
        } catch (e: Exception) {
            Dev.warn(log, "derived.update_failed", e, "exchangeId" to exchangeId)
        }

        // Step 3d: Store IDE context (only for CHAT — summaries have no editor context)
        if (purpose == ExchangePurpose.CHAT && !capturedContext.isEmpty) {
            try {
                storage.updateIdeContext(exchangeId, capturedContext)
            } catch (e: Exception) {
                Dev.warn(log, "context.store_failed", e, "exchangeId" to exchangeId)
            }
        }

        // Log the result
        val logPrefix = if (purpose == ExchangePurpose.CHAT) "chat" else "summary"
        if (parsed.isError) {
            Dev.warn(log, "$logPrefix.parsed_as_error", null,
                "exchangeId" to exchangeId,
                "errorType" to parsed.errorType?.name,
                "errorMessage" to parsed.errorMessage?.take(100)
            )
        } else {
            Dev.info(log, "$logPrefix.parsed_success",
                "exchangeId" to exchangeId,
                "strategy" to parsed.metadata.parseStrategy.name,
                "confidence" to parsed.metadata.confidence.name,
                "contentLength" to (parsed.rawText?.length ?: 0),
                "promptTokens" to parsed.metadata.tokenUsage?.promptTokens,
                "completionTokens" to parsed.metadata.tokenUsage?.completionTokens,
                "totalTokens" to parsed.metadata.tokenUsage?.totalTokens
            )
        }

        return parsed
    }

    /**
     * Save raw exchange to storage.
     *
     * This happens BEFORE parsing so we never lose raw data.
     * Even if parsing fails, the raw response is preserved.
     *
     * Token columns are NULL at this point — they get filled in
     * by updateTokenUsage() after parsing completes.
     *
     * @param exchangeId Unique ID for this exchange
     * @param rawJson The raw response JSON
     * @param httpStatus HTTP status code
     * @param prompt The original prompt
     * @param purpose CHAT or SUMMARY
     */
    private suspend fun saveToStorage(
        exchangeId: String,
        rawJson: String?,
        httpStatus: Int?,
        prompt: String,
        purpose: ExchangePurpose
    ) {
        try {
            val exchange = AiExchange(
                id = exchangeId,
                timestamp = Instant.now(),
                providerId = id,
                modelId = model ?: "unknown",
                purpose = purpose,
                request = ExchangeRequest(
                    input = prompt,
                    contextFiles = null // TODO: pass context files from ChatPanel
                ),
                rawResponse = ExchangeRawResponse(
                    json = rawJson ?: "",
                    httpStatus = httpStatus
                ),
                tokenUsage = null  // Filled in after parsing via updateTokenUsage()
            )

            val savedId = storage.saveExchange(exchange, storage.resolveProjectId())

            if (savedId != null) {
                Dev.info(log, "storage.saved",
                    "exchangeId" to exchangeId,
                    "purpose" to purpose.name,
                    "rawLength" to (rawJson?.length ?: 0)
                )
            } else {
                Dev.warn(log, "storage.not_saved", null,
                    "exchangeId" to exchangeId,
                    "reason" to "storage_disabled_or_failed"
                )
            }
        } catch (e: Exception) {
            // Don't let storage failures break the flow
            Dev.error(log, "storage.save_failed", e,
                "exchangeId" to exchangeId
            )
        }
    }

    /**
     * Create a ParsedResponse for network/request failures (before we even get a response).
     */
    private suspend fun handleRequestFailure(
        error: Exception,
        prompt: String,
        purpose: ExchangePurpose
    ): ParsedResponse {
        val exchangeId = UUID.randomUUID().toString()
        val errorJson = """{"error": "${error.message?.replace("\"", "'")}"}"""

        // Still save to storage so we have a record of the failure
        saveToStorage(exchangeId, errorJson, null, prompt, purpose)

        val logPrefix = if (purpose == ExchangePurpose.CHAT) "chat" else "summary"
        Dev.error(log, "$logPrefix.request_failed", error,
            "exchangeId" to exchangeId
        )

        return ParsedResponse.error(
            errorMessage = error.message,
            errorType = ErrorType.NETWORK_ERROR,
            exchangeId = exchangeId,
            metadata = ParseMetadata(
                parseStrategy = ParseStrategy.FAILED,
                confidence = Confidence.HIGH
            )
        )
    }

    // ==================== Protocol Implementations ====================

    // 1) OpenAI-compatible ----------------------------------------------------

    private suspend fun pingOpenAiCompat(): String {
        val url = normalizeBaseUrl(baseUrl) + "/v1/models"
        val resp = client.get(url) {
            header(HttpHeaders.Authorization, "Bearer $apiKey")
        }
        return "${resp.status.value}"
    }

    /**
     * Make an OpenAI-compatible request with the given settings.
     */
    private suspend fun requestOpenAiCompat(
        prompt: String,
        settings: RequestSettings,
        purpose: ExchangePurpose
    ): ParsedResponse {
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
            "url" to url,
            "model" to m,
            "purpose" to purpose.name,
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
            val httpStatus = resp.status.value

            Dev.info(log, "openai.raw_response",
                "status" to httpStatus,
                "length" to rawResponse.length,
                "preview" to Dev.preview(rawResponse, 500)
            )

            handleResponse(rawResponse, httpStatus, prompt, purpose)
        } catch (e: Exception) {
            handleRequestFailure(e, prompt, purpose)
        }
    }

    // 2) Gemini ---------------------------------------------------------------

    private suspend fun pingGemini(): String {
        val url = normalizeBaseUrl(baseUrl) + "/v1beta/models"
        val resp = client.get(url) {
            url { parameters.append("key", apiKey) }
        }
        val status = "${resp.status.value}"
        Dev.info(log, "gemini.ping.result", "status" to status)
        return status
    }

    /**
     * Make a Gemini request with the given settings.
     */
    private suspend fun requestGemini(
        prompt: String,
        settings: RequestSettings,
        purpose: ExchangePurpose
    ): ParsedResponse {
        val m = requireNotNull(model) { "Model required for GEMINI" }
        val url = normalizeBaseUrl(baseUrl) + "/v1beta/models/$m:generateContent"

        Dev.info(log, "gemini.request",
            "url" to url,
            "purpose" to purpose.name,
            "temperature" to settings.temperature,
            "maxTokens" to settings.maxTokens,
            "promptLength" to prompt.length,
            "promptPreview" to Dev.preview(prompt, 200)
        )

        // Check for unbalanced braces (Gemini can be sensitive to this)
        val openBraces = prompt.count { it == '{' }
        val closeBraces = prompt.count { it == '}' }
        if (openBraces != closeBraces) {
            Dev.warn(log, "gemini.prompt_unbalanced_braces", null,
                "openBraces" to openBraces,
                "closeBraces" to closeBraces
            )
        }

        // Build generation config from settings
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
            val httpStatus = resp.status.value

            Dev.info(log, "gemini.raw_response",
                "status" to httpStatus,
                "length" to rawResponse.length,
                "preview" to Dev.preview(rawResponse, 500)
            )

            handleResponse(rawResponse, httpStatus, prompt, purpose)
        } catch (e: Exception) {
            handleRequestFailure(e, prompt, purpose)
        }
    }

    // 3) CUSTOM (OpenAI-compatible body with user-defined path & header) -----

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
     * Make a custom protocol request (OpenAI-compatible body, custom auth).
     */
    private suspend fun requestCustom(
        prompt: String,
        settings: RequestSettings,
        purpose: ExchangePurpose
    ): ParsedResponse {
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
            "url" to url,
            "model" to m,
            "purpose" to purpose.name,
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
            val httpStatus = resp.status.value

            Dev.info(log, "custom.raw_response",
                "status" to httpStatus,
                "length" to rawResponse.length,
                "preview" to Dev.preview(rawResponse, 500)
            )

            handleResponse(rawResponse, httpStatus, prompt, purpose)
        } catch (e: Exception) {
            handleRequestFailure(e, prompt, purpose)
        }
    }

    // ==================== Helpers ====================

    /**
     * Normalize base URL: trim trailing slashes, handle protocol.
     *
     * Note: We preserve http:// for local models (Ollama, LM Studio, etc.)
     * that run without TLS. Only add https:// if no protocol specified.
     */
    private fun normalizeBaseUrl(url: String): String {
        val trimmed = url.trim().trimEnd('/')
        return when {
            trimmed.startsWith("http://") -> trimmed
            trimmed.startsWith("https://") -> trimmed
            else -> "https://$trimmed"
        }
    }

    // ==================== Request DTOs ====================

    /**
     * OpenAI-compatible chat request.
     *
     * Includes optional parameters that most OpenAI-compatible providers support.
     * Null values are not serialized (explicitNulls = false in JSON config).
     */
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

    /**
     * Gemini request with optional generation config.
     */
    @Serializable
    private data class GeminiRequest(
        val contents: List<GeminiContent>,
        val generationConfig: GeminiGenerationConfig? = null
    )

    @Serializable
    private data class GeminiContent(
        val parts: List<GeminiPart>
    )

    @Serializable
    private data class GeminiPart(
        val text: String? = null
    )

    /**
     * Gemini generation configuration.
     *
     * Maps our RequestSettings to Gemini's API format.
     */
    @Serializable
    private data class GeminiGenerationConfig(
        val temperature: Double? = null,
        val maxOutputTokens: Int? = null,
        val topP: Double? = null,
        val topK: Int? = null,
        val stopSequences: List<String>? = null
    ) {
        /** Check if any settings are configured (to avoid sending empty config). */
        fun hasSettings(): Boolean {
            return temperature != null ||
                    maxOutputTokens != null ||
                    topP != null ||
                    topK != null ||
                    !stopSequences.isNullOrEmpty()
        }
    }
}