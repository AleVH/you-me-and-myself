// path: src/main/kotlin/com/youmeandmyself/ai/providers/azure/AzureOpenAIProvider.kt
// Purpose: Reachability for Azure OpenAI; lists deployments via API version; uses 'api-key' header (not Bearer).
package com.youmeandmyself.ai.providers.azure

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

class AzureOpenAIProvider(
    private val apiKey: String?,            // Azure key
    private val resourceUrl: String?,       // e.g., https://myresource.openai.azure.com
    private val apiVersion: String = "2024-02-15-preview"
) : AiProvider {
    override val id = "azure-openai"
    override val displayName = "Azure OpenAI"

    private val client by lazy {
        HttpClient(CIO) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
    }

    @Serializable private data class DeploymentInfo(
        val id: String = "",
        val model: String? = null
        // add other fields if/when you need them
    )

    @Serializable private data class Deployments(
        val value: List<DeploymentInfo> = emptyList()
    )

    override suspend fun ping(): String {
        val key = apiKey?.trim().orEmpty()
        if (key.isEmpty()) return "not configured (no API key)"
        val base = resourceUrl?.trim()?.ifBlank { null } ?: return "not configured (no resource URL)"
        val resp = client.get("${base.trimEnd('/')}/openai/deployments") {
            parameter("api-version", apiVersion)
            header("api-key", key)
            accept(ContentType.Application.Json)
        }
        if (!resp.status.isSuccess()) error("HTTP ${resp.status.value}")
        val body: Deployments = resp.body()
        return "ok (deployments=${body.value.size})"
    }

    // placeholder for now, will update later
    override suspend fun chat(prompt: String): String {
        throw UnsupportedOperationException("${displayName}: chat() not implemented yet")
    }
}
