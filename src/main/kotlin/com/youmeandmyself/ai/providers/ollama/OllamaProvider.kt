// path: src/main/kotlin/com/youmeandmyself/ai/providers/ollama/OllamaProvider.kt
// Purpose: Reachability for local Ollama; GET /api/tags; no key; base defaults to http://localhost:11434.
package com.youmeandmyself.ai.providers.ollama

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

class OllamaProvider(private val baseUrl: String?) : AiProvider {
    override val id = "ollama"
    override val displayName = "Ollama (local)"

    private val client by lazy {
        HttpClient(CIO) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
    }

    @Serializable private data class Tags(val models: List<Model> = emptyList())
    @Serializable private data class Model(val name: String = "")

    override suspend fun ping(): String {
        val base = (baseUrl?.trim()?.ifBlank { null } ?: "http://localhost:11434").trimEnd('/')
        val resp = client.get("$base/api/tags")
        if (!resp.status.isSuccess()) error("HTTP ${resp.status.value}")
        val tags: Tags = resp.body()
        return "ok (models=${tags.models.size})"
    }

    // placeholder for now, will update later
    override suspend fun chat(prompt: String): String {
        throw UnsupportedOperationException("${displayName}: chat() not implemented yet")
    }
}
