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
 *
 * PHASE A3 — CONVERSATION HISTORY
 * - chat() now accepts List<ConversationTurn> for multi-turn context.
 * - Each protocol method builds a messages/contents array from history + current prompt.
 * - Helper methods buildMessageArray() and buildContentArray() centralize
 *   the history-to-protocol conversion so protocol methods stay clean.
 * - IDE context is ONLY on the current prompt (last message). Historical turns
 *   carry whatever was stored at the time.
 *
 * BLOCK 4 PLACEHOLDER — SYSTEM PROMPT
 * - When Block 4 is implemented, a systemPrompt parameter will be added to chat().
 * - The system prompt will be injected as the FIRST message in the array:
 *   - OpenAI/Custom: role="system"
 *   - Gemini: role="user" with "[System] " prefix (Gemini has no system role)
 * - Search for "BLOCK 4" comments to find all injection points.
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
import com.youmeandmyself.storage.model.ConversationTurn
import com.youmeandmyself.storage.model.ExchangeTokenUsage
import com.youmeandmyself.storage.model.TurnRole
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
     * Send a chat prompt with optional conversation history.
     *
     * Uses [chatSettings] for request parameters. When [history] is non-empty,
     * builds a multi-message request with previous turns followed by the current prompt.
     *
     * @param prompt The current user message (may include IDE context from ContextAssembler)
     * @param history Previous conversation turns. Empty = single-message request.
     */
    override suspend fun chat(
        prompt: String,
        history: List<ConversationTurn>
    ): ProviderResponse {
        val settings = chatSettings ?: RequestSettings.chatDefaults()
        return when (protocol) {
            ApiProtocol.OPENAI_COMPAT -> requestOpenAiCompat(prompt, settings, history)
            ApiProtocol.GEMINI        -> requestGemini(prompt, settings, history)
            ApiProtocol.CUSTOM        -> requestCustom(prompt, settings, history)
        }
    }

    /**
     * Send a summarization prompt. Uses [summarySettings] for request parameters.
     *
     * Summarization is always single-shot — no history needed. Each summary
     * request is self-contained (file content + prompt template).
     */
    override suspend fun summarize(prompt: String): ProviderResponse {
        val settings = summarySettings ?: RequestSettings.summaryDefaults()
        return when (protocol) {
            ApiProtocol.OPENAI_COMPAT -> requestOpenAiCompat(prompt, settings, emptyList())
            ApiProtocol.GEMINI        -> requestGemini(prompt, settings, emptyList())
            ApiProtocol.CUSTOM        -> requestCustom(prompt, settings, emptyList())
        }
    }

    // ==================== History → Protocol Conversion ====================

    // ── Private role mapping ────────────────────────────────────────────
    //
    // TurnRole is a provider-agnostic enum in the shared model layer.
    // Protocol-specific role strings are mapped HERE — the only place in
    // the codebase that knows about specific API formats.
    //
    // If a new protocol is added (e.g., Cohere, Mistral), add a mapping
    // method here. TurnRole itself never changes for protocol reasons.
    // ────────────────────────────────────────────────────────────────────

    /**
     * Map a [TurnRole] to the OpenAI protocol role string.
     *
     * OpenAI (and OpenAI-compatible providers like Together, Groq, Ollama)
     * use: "user", "assistant", "system".
     */
    private fun TurnRole.toOpenAiRoleString(): String = when (this) {
        TurnRole.USER -> "user"
        TurnRole.ASSISTANT -> "assistant"
        TurnRole.SYSTEM -> "system"
    }

    /**
     * Map a [TurnRole] to the Gemini protocol role string.
     *
     * Gemini uses: "user", "model". There is NO "system" role —
     * SYSTEM turns are mapped to "user" and the caller is responsible
     * for adding a "[System] " prefix to the content so the model can
     * distinguish system instructions from actual user messages.
     *
     * @see buildContentArray for how SYSTEM turns are handled
     */
    private fun TurnRole.toGeminiRoleString(): String = when (this) {
        TurnRole.USER -> "user"
        TurnRole.ASSISTANT -> "model"
        TurnRole.SYSTEM -> "user"  // Gemini has no system role; framed as user with prefix
    }

    // ── Message/content array builders ──────────────────────────────────

    /**
     * Build the message array for OpenAI-compatible protocols.
     *
     * Used by both OPENAI_COMPAT and CUSTOM protocols (Custom uses OpenAI's
     * request body format with different auth/URL).
     *
     * Message order:
     *   1. [BLOCK 4 — system prompt will go here as role="system"]
     *   2. History turns mapped via [toOpenAiRoleString]
     *   3. Current prompt as final "user" message
     *
     * ## Why IDE context is only on the last message
     *
     * IDE context (from ContextAssembler) reflects the CURRENT state of the editor —
     * what file is open, cursor position, selected text. Historical turns already have
     * whatever context was attached when they were originally sent. Re-attaching
     * current IDE context to old turns would be misleading (the user wasn't looking
     * at the same file back then).
     *
     * ## BLOCK 4 PLACEHOLDER — System Prompt
     *
     * When AiProfile.systemPrompt is wired through (Block 4), insert it as the
     * first message: OpenAiMessage(role = "system", content = systemPrompt).
     * This gives the AI its persona/instructions before any conversation turns.
     * The system prompt comes from the user's profile settings and is NOT part
     * of conversation history — it's injected fresh on every request.
     *
     * @param history Previous conversation turns (may be empty)
     * @param currentPrompt The current user message with fresh IDE context
     * @return Complete messages list ready for the request body
     */
    private fun buildMessageArray(
        history: List<ConversationTurn>,
        currentPrompt: String
    ): List<OpenAiMessage> {
        val messages = mutableListOf<OpenAiMessage>()

        // ── BLOCK 4 PLACEHOLDER: System prompt injection point ──────────
        // When Block 4 is implemented, the system prompt from AiProfile will be
        // passed as a parameter to chat() and then to this method. Insert it here:
        //
        //   if (!systemPrompt.isNullOrBlank()) {
        //       messages.add(OpenAiMessage(role = "system", content = systemPrompt))
        //   }
        //
        // The system prompt defines the AI's persona and instructions. It goes
        // BEFORE history so the AI processes it as its foundational context.
        // Connected to: AiProfile.systemPrompt (settings UI), ChatOrchestrator (passes it through)
        // ────────────────────────────────────────────────────────────────────

        // History turns — each ConversationTurn maps to one message
        for (turn in history) {
            messages.add(OpenAiMessage(
                role = turn.role.toOpenAiRoleString(),
                content = turn.content
            ))
        }

        // Current prompt — always the final "user" message with fresh IDE context
        messages.add(OpenAiMessage(role = "user", content = currentPrompt))

        return messages
    }

    /**
     * Build the content array for the Gemini protocol.
     *
     * Message order:
     *   1. [BLOCK 4 — system prompt will go here as role="user" with "[System] " prefix]
     *   2. History turns mapped via [toGeminiRoleString]
     *   3. Current prompt as final "user" content
     *
     * ## Gemini-Specific Constraints
     *
     * - Gemini has NO "system" role. SYSTEM turns are sent as "user" with a
     *   "[System] " prefix so the AI can distinguish them from actual user messages.
     * - Gemini requires alternating user/model turns. If history has consecutive
     *   same-role turns (e.g., two user messages), they are merged into one content
     *   block to avoid API errors. This can happen when SYSTEM turns (mapped to "user")
     *   are adjacent to USER turns.
     *
     * ## BLOCK 4 PLACEHOLDER — System Prompt
     *
     * When AiProfile.systemPrompt is wired through (Block 4), insert it as the
     * first content with role="user" and prefix "[System Instructions] ".
     * Gemini has no native system role, so we frame it clearly for the model.
     * Connected to: AiProfile.systemPrompt (settings UI), ChatOrchestrator (passes it through)
     *
     * @param history Previous conversation turns (may be empty)
     * @param currentPrompt The current user message with fresh IDE context
     * @return Complete contents list ready for the Gemini request body
     */
    private fun buildContentArray(
        history: List<ConversationTurn>,
        currentPrompt: String
    ): List<GeminiContent> {
        // Build a flat list of (role, text) pairs first, then merge consecutive same-role entries
        val rawPairs = mutableListOf<Pair<String, String>>()

        // ── BLOCK 4 PLACEHOLDER: System prompt injection point ──────────
        // When Block 4 is implemented, insert the system prompt here:
        //
        //   if (!systemPrompt.isNullOrBlank()) {
        //       rawPairs.add("user" to "[System Instructions] $systemPrompt")
        //   }
        //
        // Gemini has no "system" role — we use "user" with a clear prefix so the
        // model treats it as instructions, not conversation. The prefix is important
        // because the first history turn might also be "user", and Gemini requires
        // alternating roles — the merge logic below handles this automatically.
        // Connected to: AiProfile.systemPrompt (settings UI), ChatOrchestrator (passes it through)
        // ────────────────────────────────────────────────────────────────────

        // History turns
        for (turn in history) {
            val role = turn.role.toGeminiRoleString()
            val text = if (turn.role == TurnRole.SYSTEM) {
                // Gemini has no system role — prefix so the model knows this is a system instruction
                "[System] ${turn.content}"
            } else {
                turn.content
            }
            rawPairs.add(role to text)
        }

        // Current prompt — final "user" entry
        rawPairs.add("user" to currentPrompt)

        // Merge consecutive same-role entries (Gemini requires alternating user/model)
        // This happens when a SYSTEM turn (mapped to "user") is followed by a USER turn,
        // or when buildHistory returns adjacent same-role turns for any reason.
        val mergedContents = mutableListOf<GeminiContent>()
        var currentRole: String? = null
        var currentTexts = mutableListOf<String>()

        for ((role, text) in rawPairs) {
            if (role == currentRole) {
                // Same role as previous — accumulate text
                currentTexts.add(text)
            } else {
                // New role — flush previous
                if (currentRole != null && currentTexts.isNotEmpty()) {
                    mergedContents.add(GeminiContent(
                        role = currentRole,
                        parts = listOf(GeminiPart(text = currentTexts.joinToString("\n\n")))
                    ))
                }
                currentRole = role
                currentTexts = mutableListOf(text)
            }
        }
        // Flush the last group
        if (currentRole != null && currentTexts.isNotEmpty()) {
            mergedContents.add(GeminiContent(
                role = currentRole,
                parts = listOf(GeminiPart(text = currentTexts.joinToString("\n\n")))
            ))
        }

        return mergedContents
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
     *
     * When history is provided, builds a multi-message request via [buildMessageArray].
     * When history is empty, sends a single "user" message (backwards compatible).
     */
    private suspend fun requestOpenAiCompat(
        prompt: String,
        settings: RequestSettings,
        history: List<ConversationTurn>
    ): ProviderResponse {
        val url = normalizeBaseUrl(baseUrl) + "/v1/chat/completions"
        val m = model ?: "gpt-4o-mini"

        val messages = buildMessageArray(history, prompt)

        val payload = OpenAiChatRequest(
            model = m,
            messages = messages,
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
            "historyTurns" to history.size,
            "totalMessages" to messages.size,
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
     *
     * When history is provided, builds a multi-turn contents array via [buildContentArray].
     * Handles Gemini's alternating-role requirement by merging consecutive same-role turns.
     */
    private suspend fun requestGemini(
        prompt: String,
        settings: RequestSettings,
        history: List<ConversationTurn>
    ): ProviderResponse {
        val m = requireNotNull(model) { "Model required for GEMINI" }
        val url = normalizeBaseUrl(baseUrl) + "/v1beta/models/$m:generateContent"

        val contents = buildContentArray(history, prompt)

        Dev.info(log, "gemini.request",
            "url" to url,
            "temperature" to settings.temperature,
            "maxTokens" to settings.maxTokens,
            "promptLength" to prompt.length,
            "historyTurns" to history.size,
            "totalContents" to contents.size,
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
                    contents = contents,
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
     *
     * Uses the same [buildMessageArray] helper as the OpenAI path since the
     * request body format is OpenAI-compatible.
     */
    private suspend fun requestCustom(
        prompt: String,
        settings: RequestSettings,
        history: List<ConversationTurn>
    ): ProviderResponse {
        val cfg = custom ?: CustomProtocolConfig()
        val url = normalizeBaseUrl(baseUrl) + cfg.chatPath
        val m = model ?: "gpt-4o-mini"

        val messages = buildMessageArray(history, prompt)

        val payload = OpenAiChatRequest(
            model = m,
            messages = messages,
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
            "historyTurns" to history.size,
            "totalMessages" to messages.size,
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
    private data class GeminiContent(
        val role: String? = null,
        val parts: List<GeminiPart>
    )

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