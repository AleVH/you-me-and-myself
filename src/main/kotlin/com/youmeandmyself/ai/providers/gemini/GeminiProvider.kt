// ==========================
// File: src/main/kotlin/com/youmeandmyself/ai/providers/gemini/GeminiProvider.kt
// ==========================
// path: src/main/kotlin/com/youmeandmyself/ai/providers/gemini/GeminiProvider.kt — Minimal Gemini implementation
package com.youmeandmyself.ai.providers.gemini

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
 * Minimal Gemini provider. We hit the public models list as a low-cost ping.
 * Base URL customizable; default targets a common REST surface.
 */
class GeminiProvider(private val apiKey: String, private val baseUrl: String?) : AiProvider {
    override val id = "gemini"
    override val displayName = "Gemini"

    private val client by lazy {
        HttpClient(CIO) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
    }

    @Serializable private data class ModelsResponse(val models: List<Model> = emptyList())
    @Serializable private data class Model(val name: String = "")
    // Request/response shapes for Gemini generateContent
    @Serializable private data class Part(val text: String)
    @Serializable private data class Content(val parts: List<Part>)
    @Serializable private data class GenerateContentRequest(val contents: List<Content>)
    @Serializable private data class Candidate(val content: Content? = null)
    @Serializable private data class GenerateContentResponse(val candidates: List<Candidate> = emptyList())


    override suspend fun ping(): String {
        if (apiKey.isBlank()) return "not configured (no API key)"

        val root = (baseUrl?.ifBlank { null } ?: "https://generativelanguage.googleapis.com").trimEnd('/')
        val url = "$root/v1beta/models?key=${apiKey.trim()}"

        val resp = client.get(url) { accept(ContentType.Application.Json) }
        if (resp.status.value !in 200..299) error("HTTP ${resp.status.value}")
        val body: ModelsResponse = resp.body()
        return "ok (models=${body.models.size})"
    }

    /**
     * Sends a single-turn prompt to Gemini via `models/{model}:generateContent`.
     * Default model is "gemini-1.5-flash" (fast & cheap). You can later surface this
     * as a user setting if needed.
     */
    override suspend fun chat(prompt: String): String {
        if (apiKey.isBlank()) return "Gemini: not configured (no API key)"

        // Choose base + model
        val root = (baseUrl?.ifBlank { null } ?: "https://generativelanguage.googleapis.com").trimEnd('/')
        val model = "gemini-1.5-flash" // TODO: make configurable if you want
        val url = "$root/v1beta/models/$model:generateContent?key=${apiKey.trim()}"

        // Build request payload
        val payload = GenerateContentRequest(
            contents = listOf(
                Content(parts = listOf(Part(text = prompt)))
            )
        )

        // Send request
        val resp = client.post(url) {
            contentType(ContentType.Application.Json)
            setBody(payload)
            accept(ContentType.Application.Json)
        }

        // Basic error surface
        if (resp.status.value !in 200..299) {
            error("HTTP ${resp.status.value}")
        }

        // Parse and extract the first candidate’s text
        val body: GenerateContentResponse = resp.body()
        val text = body.candidates
            .firstOrNull()
            ?.content
            ?.parts
            ?.joinToString(separator = "") { it.text }
            ?.takeIf { it.isNotBlank() }

        return text ?: "Gemini: empty response"
    }

}
