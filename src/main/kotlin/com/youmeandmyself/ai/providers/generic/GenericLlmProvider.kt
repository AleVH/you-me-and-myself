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
import com.youmeandmyself.dev.Dev
import com.youmeandmyself.storage.LocalStorageFacade
import com.youmeandmyself.storage.model.AiExchange
import com.youmeandmyself.storage.model.ExchangePurpose
import com.youmeandmyself.storage.model.ExchangeRequest
import com.youmeandmyself.storage.model.ExchangeRawResponse
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.UUID

class GenericLlmProvider(
    override val id: String,              // free text id (e.g., providerId or "generic")
    override val displayName: String,     // label shown to the user
    private val baseUrl: String,          // endpoint base (required by OPENAI_COMPAT, GEMINI, CUSTOM)
    private val apiKey: String,           // auth secret (Phase 2: PasswordSafe)
    private val model: String?,           // model name (required by GEMINI; default for OPENAI_COMPAT/CUSTOM)
    private val protocol: ApiProtocol,    // OPENAI_COMPAT | GEMINI | CUSTOM
    private val custom: CustomProtocolConfig?, // optional knobs for CUSTOM
    private val project: Project          // IntelliJ project context (for storage, etc.)
) : AiProvider {

    private val log = Dev.logger(GenericLlmProvider::class.java)

    // Storage facade for persisting exchanges
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

    init {
        require(baseUrl.isNotBlank()) { "Base URL is required" }
    }

    // Reuse the hardened, shared Ktor client (timeouts, logging, proxy, JSON configured centrally).
    private val client get() = HttpClientFactory.client

    // ---- Public API ----

    override suspend fun ping(): String = when (protocol) {
        ApiProtocol.OPENAI_COMPAT -> pingOpenAiCompat()
        ApiProtocol.GEMINI        -> pingGemini()
        ApiProtocol.CUSTOM        -> pingCustom()
    }

    /**
     * Send a chat prompt and get a parsed response.
     *
     * Flow:
     * 1. Make HTTP request based on protocol
     * 2. Save raw response to storage IMMEDIATELY (get exchangeId)
     * 3. Parse response using ResponseParser (universal format detection)
     * 4. Return ParsedResponse with extracted content or error info
     */
    override suspend fun chat(prompt: String): ParsedResponse = when (protocol) {
        ApiProtocol.OPENAI_COMPAT -> chatOpenAiCompat(prompt)
        ApiProtocol.GEMINI        -> chatGemini(prompt)
        ApiProtocol.CUSTOM        -> chatCustom(prompt)
    }

    // ---- Unified Response Handling ----

    /**
     * Handle the raw HTTP response: save to storage, then parse.
     *
     * This is the single point where all protocol responses converge.
     * Raw data is persisted BEFORE parsing to ensure nothing is lost.
     *
     * @param rawJson The raw response body (may be null on network error)
     * @param httpStatus The HTTP status code (may be null on network error)
     * @param prompt The original prompt (for storage)
     * @return ParsedResponse with extracted content or error information
     */
    private suspend fun handleResponse(
        rawJson: String?,
        httpStatus: Int?,
        prompt: String
    ): ParsedResponse {
        // Generate exchange ID for this request
        val exchangeId = UUID.randomUUID().toString()

        // Save to storage IMMEDIATELY (before any parsing)
        saveToStorage(exchangeId, rawJson, httpStatus, prompt)

        // Parse the response
        val parsed = ResponseParser.parse(rawJson, httpStatus, exchangeId)

        // Log the result
        if (parsed.isError) {
            Dev.warn(log, "chat.parsed_as_error", null,
                "exchangeId" to exchangeId,
                "errorType" to parsed.errorType?.name,
                "errorMessage" to parsed.errorMessage?.take(100)
            )
        } else {
            Dev.info(log, "chat.parsed_success",
                "exchangeId" to exchangeId,
                "strategy" to parsed.metadata.parseStrategy.name,
                "confidence" to parsed.metadata.confidence.name,
                "contentLength" to (parsed.rawText?.length ?: 0)
            )
        }

        // TODO: Update storage with parseMetadata after parsing
        // This would allow us to query "show me all responses parsed with heuristics"

        return parsed
    }

    /**
     * Save raw exchange to storage.
     *
     * This happens BEFORE parsing so we never lose raw data.
     * Even if parsing fails, the raw response is preserved.
     */
    private suspend fun saveToStorage(
        exchangeId: String,
        rawJson: String?,
        httpStatus: Int?,
        prompt: String
    ) {
        try {
            val exchange = AiExchange(
                id = exchangeId,
                timestamp = Instant.now(),
                providerId = id,
                modelId = model ?: "unknown",
                purpose = ExchangePurpose.CHAT,
                request = ExchangeRequest(
                    input = prompt,
                    contextFiles = null // TODO: pass context files from ChatPanel
                ),
                rawResponse = ExchangeRawResponse(
                    json = rawJson ?: "",
                    httpStatus = httpStatus
                ),
                tokensUsed = null  // Can be extracted from rawJson later if needed
                // TODO: Add sessionId and projectId when AiExchange model is updated
                // sessionId = sessionId,
                // projectId = project.name
            )

            val savedId = storage.saveExchange(exchange, storage.resolveProjectId())

            if (savedId != null) {
                Dev.info(log, "storage.saved",
                    "exchangeId" to exchangeId,
                    "rawLength" to (rawJson?.length ?: 0)
                )
            } else {
                Dev.warn(log, "storage.not_saved", null,
                    "exchangeId" to exchangeId,
                    "reason" to "storage_disabled_or_failed"
                )
            }
        } catch (e: Exception) {
            // Don't let storage failures break the chat flow
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
        prompt: String
    ): ParsedResponse {
        val exchangeId = UUID.randomUUID().toString()
        val errorJson = """{"error": "${error.message?.replace("\"", "'")}"}"""

        // Still save to storage so we have a record of the failure
        saveToStorage(exchangeId, errorJson, null, prompt)

        Dev.error(log, "chat.request_failed", error,
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

    // ---- Protocol Implementations ----
    // Each method just makes the HTTP request and delegates to handleResponse()

    // 1) OpenAI-compatible ----------------------------------------------------

    private suspend fun pingOpenAiCompat(): String {
        val url = normalizeBaseUrl(baseUrl) + "/v1/models"
        val resp = client.get(url) {
            header(HttpHeaders.Authorization, "Bearer $apiKey")
        }
        return "${resp.status.value}"
    }

    private suspend fun chatOpenAiCompat(prompt: String): ParsedResponse {
        val url = normalizeBaseUrl(baseUrl) + "/v1/chat/completions"
        val m = model ?: "gpt-4o-mini"
        val payload = OpenAiChatRequest(
            model = m,
            messages = listOf(OpenAiMessage(role = "user", content = prompt))
        )

        Dev.info(log, "openai.request",
            "url" to url,
            "model" to m,
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

            handleResponse(rawResponse, httpStatus, prompt)
        } catch (e: Exception) {
            handleRequestFailure(e, prompt)
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

    private suspend fun chatGemini(prompt: String): ParsedResponse {
        val m = requireNotNull(model) { "Model required for GEMINI" }
        val url = normalizeBaseUrl(baseUrl) + "/v1beta/models/$m:generateContent"

        Dev.info(log, "gemini.request",
            "url" to url,
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

        return try {
            val resp = client.post(url) {
                url { parameters.append("key", apiKey) }
                contentType(ContentType.Application.Json)
                setBody(GeminiRequest(
                    contents = listOf(GeminiContent(parts = listOf(GeminiPart(text = prompt))))
                ))
            }

            val rawResponse = resp.bodyAsText()
            val httpStatus = resp.status.value

            Dev.info(log, "gemini.raw_response",
                "status" to httpStatus,
                "length" to rawResponse.length,
                "preview" to Dev.preview(rawResponse, 500)
            )

            handleResponse(rawResponse, httpStatus, prompt)
        } catch (e: Exception) {
            handleRequestFailure(e, prompt)
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

    private suspend fun chatCustom(prompt: String): ParsedResponse {
        val cfg = custom ?: CustomProtocolConfig()
        val url = normalizeBaseUrl(baseUrl) + cfg.chatPath
        val m = model ?: "gpt-4o-mini"
        val payload = OpenAiChatRequest(
            model = m,
            messages = listOf(OpenAiMessage(role = "user", content = prompt))
        )

        Dev.info(log, "custom.request",
            "url" to url,
            "model" to m,
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

            handleResponse(rawResponse, httpStatus, prompt)
        } catch (e: Exception) {
            handleRequestFailure(e, prompt)
        }
    }

    // ---- Helpers ----

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

    // ---- Minimal DTOs (scoped/private) -------------------------------------
    // Request DTOs are still needed for constructing payloads.
    // Response DTOs can be removed once we confirm ResponseParser handles all cases.

    // OpenAI-compatible request
    @Serializable
    private data class OpenAiChatRequest(
        val model: String,
        val messages: List<OpenAiMessage>
    )
    @Serializable
    private data class OpenAiMessage(
        val role: String,
        val content: String
    )

    // Gemini request
    @Serializable
    private data class GeminiRequest(
        val contents: List<GeminiContent>
    )
    @Serializable
    private data class GeminiContent(
        val parts: List<GeminiPart>
    )
    @Serializable
    private data class GeminiPart(
        val text: String? = null
    )

    // ---- Legacy Response DTOs (TO BE REMOVED) ----
    // Keeping these temporarily for reference. ResponseParser now handles parsing.
    // TODO: Delete once confirmed working
    /*
    @Serializable
    private data class OpenAiChatResponse(
        val choices: List<OpenAiChoice> = emptyList()
    )
    @Serializable
    private data class OpenAiChoice(
        val message: OpenAiMessage
    )
    @Serializable
    private data class GeminiResponse(
        val candidates: List<GeminiCandidate> = emptyList()
    )
    @Serializable
    private data class GeminiCandidate(
        val content: GeminiContent? = null
    )
    */
}