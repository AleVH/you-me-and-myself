// ==========================
// File: src/main/kotlin/com/youmeandmyself/ai/providers/openai/OpenAIProvider.kt
// ==========================
// path: src/main/kotlin/com/youmeandmyself/ai/providers/openai/OpenAIProvider.kt — Minimal OpenAI implementation
package com.youmeandmyself.ai.providers.openai

import com.youmeandmyself.ai.providers.AiProvider
import io.ktor.client.*
import io.ktor.client.call.body
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import io.ktor.client.statement.*

/**
 * Minimal OpenAI provider using a lightweight HTTP call.
 * NOTE:
 *  - We hit GET /v1/models to validate credentials/connectivity.
 *  - String interpolation must NOT be escaped, or you’ll see ${...} literally in the UI.
 */
class OpenAIProvider (
    private val apiKey: String,
    private val baseUrl: String?,
    private val modelFromSettings: String?
) : AiProvider {
    override val id = "openai"
    override val displayName = "OpenAI"

    // Ktor HTTP client with JSON support; CIO engine is fine for this simple ping.
    private val client by lazy {
        HttpClient(CIO) {
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    encodeDefaults = true      // ensure defaults are included
                    explicitNulls = false      // don’t send nulls unless needed
                })
            }
        }
    }

    @Serializable private data class ModelList(val data: List<ModelInfo> = emptyList())
    @Serializable private data class ModelInfo(val id: String = "")

    // --- DTOs for chat completions ---
    @Serializable private data class ChatMessage(val role: String, val content: String)
    @Serializable private data class ChatRequest(
        val model: String,
        val temperature: Double = 0.0,
        val messages: List<ChatMessage>
    )
    @Serializable private data class ChatChoice(val index: Int = 0, val message: ChatMessage? = null)
    @Serializable private data class ChatResponse(val choices: List<ChatChoice> = emptyList())

    private fun rooted(path: String): String =
        (baseUrl?.ifBlank { null } ?: "https://api.openai.com").trimEnd('/') + path

    private fun auth(builder: HttpRequestBuilder) {
        builder.header(HttpHeaders.Authorization, "Bearer $apiKey")
        builder.accept(ContentType.Application.Json)
        builder.contentType(ContentType.Application.Json)
    }

    override suspend fun ping(): String {
        // Build the /v1/models URL using either baseUrl or the official endpoint
        val url = (baseUrl?.ifBlank { null } ?: "https://api.openai.com").trimEnd('/') + "/v1/models"

        // Minimal GET just to validate auth & connectivity
        val resp = client.get(url) {
            header(HttpHeaders.Authorization, "Bearer $apiKey") // required
            accept(ContentType.Application.Json)
        }

        // If not success, surface the error body so you see *why* (e.g., invalid_api_key)
        if (!resp.status.isSuccess()) {
            val err = runCatching { resp.bodyAsText() }.getOrNull().orEmpty()
            error("HTTP ${resp.status.value} at GET /v1/models — ${err.take(500)}")
        }

        val body: ModelList = resp.body()
        return "ok (models=${body.data.size})"
    }

    override suspend fun listModels(): List<String> {
        if (apiKey.isBlank()) return emptyList()
        val resp = client.get(rooted("/v1/models")) { auth(this) }
        if (!resp.status.isSuccess()) {
            val err = runCatching { resp.bodyAsText() }.getOrNull().orEmpty()
            error("HTTP ${resp.status.value} at GET /v1/models — ${err.take(500)}")
        }
        val body: ModelList = resp.body()
        return body.data.mapNotNull { it.id }.sorted()
    }

    /** Real chat call via POST /v1/chat/completions */
    override suspend fun chat(prompt: String): String {
        if (apiKey.isBlank()) error("OpenAI: not configured (missing API key)")
        val model = (modelFromSettings?.ifBlank { null } ?: "gpt-4o-mini") // safe default

        val req = ChatRequest(
            model = model,
            temperature = 0.0,
            messages = listOf(ChatMessage("user", prompt))
        )

        val resp = client.post(rooted("/v1/chat/completions")) {
            auth(this)
            contentType(ContentType.Application.Json)
            setBody(req)
        }

        if (!resp.status.isSuccess()) {
            val err = runCatching { resp.bodyAsText() }.getOrNull().orEmpty()
            error("HTTP ${resp.status.value} at POST /v1/chat/completions — ${err.take(500)}")
        }

        val body: ChatResponse = resp.body()
        val content = body.choices.firstOrNull()?.message?.content?.trim().orEmpty()
        if (content.isBlank()) error("OpenAI: empty response")
        return content
    }
}
