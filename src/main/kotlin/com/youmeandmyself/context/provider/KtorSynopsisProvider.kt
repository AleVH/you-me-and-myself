// File: src/main/kotlin/com/youmeandmyself/context/provider/KtorSynopsisProvider.kt
package com.youmeandmyself.context.provider

import com.intellij.openapi.project.Project
import com.youmeandmyself.ai.net.HttpClientFactory
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.coroutines.delay
import java.io.IOException
import kotlin.math.min

/**
 * SynopsisProvider implementation that reuses the shared Ktor HttpClient from HttpClientFactory.
 * No new HTTP stack; honors the timeouts, logging, and proxy settings you already configured.
 */
class KtorSynopsisProvider(
    private val endpointUrl: String,
    private val apiKey: String,
    private val maxRetries: Int = 3,
    private val initialBackoffMs: Long = 300L
) : SynopsisProvider {

    // Same JSON behavior as your factory (ignore unknowns, compact encodes).
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    @Serializable private data class RequestDTO(val languageId: String? = null, val sourceText: String, val maxTokens: Int)
    @Serializable private data class ResponseDTO(val text: String? = null, val error: String? = null)

    // + add import at top: import kotlinx.coroutines.delay

    override suspend fun generateSynopsis(
        project: Project,
        path: String,
        languageId: String?,
        sourceText: String,
        maxTokens: Int
    ): String {
//        com.intellij.openapi.diagnostic.Logger
//            .getInstance(KtorSynopsisProvider::class.java)
//            .info("synopsis.call path=$path lang=$languageId tokens=$maxTokens body.len=${sourceText.length}")

        val safeTokens = maxTokens.coerceIn(32, 2048)
        val bodyJson = json.encodeToString(RequestDTO.serializer(), RequestDTO(languageId, sourceText, safeTokens))

        val client = HttpClientFactory.client
        var attempt = 0
        var backoff = initialBackoffMs.coerceAtLeast(100L)

        while (true) {
            attempt++
            try {
                return "[mock summary] ${sourceText.take(120)}" // this is a mocked response, the summarize service needs to be sorted and wired correctly
                val respText: String = client.post(endpointUrl) {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer $apiKey")
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    }
                    setBody(bodyJson)
                }.body()

                val dto = json.decodeFromString(ResponseDTO.serializer(), respText)
                val text = dto.text?.trim()
                if (!text.isNullOrEmpty()) return text
                throw IOException("Empty 'text' in synopsis response. error=${dto.error ?: "unknown"}")
            } catch (ex: Exception) {
                if (attempt > maxRetries) {
                    throw IOException("Synopsis failed after $attempt attempts for $path: ${ex.message}", ex)
                }
                // non-blocking backoff
                delay(backoff)
                backoff = min(backoff * 2, 5_000L)
            }
        }
    }

}
