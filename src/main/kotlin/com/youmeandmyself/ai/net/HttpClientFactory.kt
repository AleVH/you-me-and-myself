// (project-root)/src/main/kotlin/com/youmeandmyself/ai/net/HttpClientFactory.kt
// Creates a hardened Ktor client: JSON, timeouts, proxy support, and basic logging.

package com.youmeandmyself.ai.net

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.engine.ProxyBuilder
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.plugins.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.http.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.ExperimentalSerializationApi

object HttpClientFactory {
    // Single shared client instance for your plugin.
    @OptIn(ExperimentalSerializationApi::class)
    val client: HttpClient by lazy {
        HttpClient(CIO) {

            // --- JSON serialization -----------------------------------------------------------
            install(ContentNegotiation) {
                // Tolerate extra fields and keep payloads compact.
                json(Json {
                    ignoreUnknownKeys = true
                    encodeDefaults = true
                    explicitNulls = false
                })
            }

            // --- Timeouts (avoid "request_timeout=unknown ms") --------------------------------
            // These cover DNS/connect/request/socket phases at the client level.
            install(HttpTimeout) {
                connectTimeoutMillis = 20_000
                requestTimeoutMillis = 60_000
                socketTimeoutMillis  = 60_000
            }

            // --- Logging (helps pinpoint where it stalls) ------------------------------------
            install(Logging) {
                level = LogLevel.HEADERS   // keep it modest; upgrade to BODY only for debugging
            }

            // If server returns non-2xx, don't throw immediately; let callers read the body.
            expectSuccess = false

            // --- Engine tuning & proxy --------------------------------------------------------
            engine {
                // Respect JVM proxy properties if present (e.g., -Dhttp.proxyHost, -Dhttp.proxyPort)
                val proxyHost = System.getProperty("http.proxyHost")?.takeIf { it.isNotBlank() }
                val proxyPort = System.getProperty("http.proxyPort")?.toIntOrNull() ?: 80
                if (proxyHost != null) {
                    val proxyUrl = URLBuilder(
                        protocol = URLProtocol.HTTP,
                        host = proxyHost,
                        port = proxyPort
                    ).build()
                    proxy = ProxyBuilder.http(proxyUrl)
                }

                // Connection establishment guard at the engine level (in addition to HttpTimeout).
                endpoint {
                    connectTimeout = 20_000
                    connectAttempts = 2
                }
            }
        }
    }
}
