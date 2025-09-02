// (project-root)/src/main/kotlin/com/youmeandmyself/ai/net/HttpClientFactory.kt
// Creates a Ktor client configured for JSON using kotlinx.serialization.

package com.youmeandmyself.ai.net

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.ExperimentalSerializationApi

object HttpClientFactory {
    // Single shared client instance for your plugin.
    @OptIn(ExperimentalSerializationApi::class)
    val client: HttpClient by lazy {
        HttpClient(CIO) {
            install(ContentNegotiation) {
                // Configure Kotlinx JSON. ignoreUnknownKeys = tolerate extra fields from API.
                json(Json {
                    ignoreUnknownKeys = true
                    encodeDefaults = true
                    explicitNulls = false
                })
            }
        }
    }
}
