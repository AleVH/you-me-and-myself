// ==========================
// File: src/main/kotlin/com/youmeandmyself/ai/providers/deepseek/DeepSeekProvider.kt
// ==========================
// path: src/main/kotlin/com/youmeandmyself/ai/providers/deepseek/DeepSeekProvider.kt — Minimal DeepSeek implementation
package com.youmeandmyself.ai.providers.deepseek

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

/**
 * Minimal DeepSeek provider using the models endpoint for a cheap connectivity check.
 * Base URL configurable; defaults to DeepSeek's public API.
 */
class DeepSeekProvider(private val apiKey: String, private val baseUrl: String?) : AiProvider {
    override val id = "deepseek"
    override val displayName = "DeepSeek"

    private val client by lazy {
        HttpClient(CIO) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
    }

    @Serializable private data class ModelList(val data: List<ModelInfo> = emptyList())
    @Serializable private data class ModelInfo(val id: String = "")

    override suspend fun ping(): String {
        val key = apiKey.trim()
        if (key.isEmpty()) return "not configured (no API key)"

        val base = (baseUrl?.ifBlank { null } ?: "https://api.deepseek.com").trimEnd('/')
        val url = "$base/v1/models"

        val resp = client.get(url) {
            header(HttpHeaders.Authorization, "Bearer $key")
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
