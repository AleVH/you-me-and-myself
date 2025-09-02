// path: src/main/kotlin/com/youmeandmyself/ai/providers/anthropic/AnthropicProvider.kt
// Purpose: Reachability check for Anthropic's public API via /v1/models; shows ok / not configured / HTTP <code>.
package com.youmeandmyself.ai.providers.anthropic

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

class AnthropicProvider(private val apiKey: String?, private val baseUrl: String?) : AiProvider {
    override val id = "anthropic"
    override val displayName = "Anthropic"

    private val client by lazy {
        HttpClient(CIO) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
    }

    @Serializable private data class ModelList(val data: List<ModelInfo> = emptyList())
    @Serializable private data class ModelInfo(val id: String = "")

    override suspend fun ping(): String {
        val key = apiKey?.trim().orEmpty()
        if (key.isEmpty()) return "not configured (no API key)"
        val base = (baseUrl?.trim()?.ifBlank { null } ?: "https://api.anthropic.com").trimEnd('/')
        val resp = client.get("$base/v1/models") {
            header(HttpHeaders.Authorization, "Bearer $key")
            header("anthropic-version", "2023-06-01")
            accept(ContentType.Application.Json)
        }
        if (!resp.status.isSuccess()) error("HTTP ${resp.status.value}")
        val body: ModelList = resp.body()
        return "ok (models=${body.data.size})"
    }

    // placeholder for now, will update later
    override suspend fun chat(prompt: String): String {
        throw UnsupportedOperationException("${displayName}: chat() not implemented yet")
    }

}
