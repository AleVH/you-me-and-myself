// File: src/main/kotlin/com/youmeandmyself/context/provider/SynopsisSettings.kt

package com.youmeandmyself.context.provider

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service

/**
 * Central place for provider configuration.
 * For now it reads from env vars if IDE storage isn't hooked up yet.
 * Later you can implement PersistentStateComponent to store securely in the IDE.
 */
@Service
class SynopsisSettings(
    val endpointUrl: String? = System.getenv("SYNOPSIS_ENDPOINT_URL"),
    val apiKey: String? = System.getenv("SYNOPSIS_API_KEY"),
    val requestTimeoutMs: Long = (System.getenv("SYNOPSIS_TIMEOUT_MS") ?: "20000").toLong(),
    val maxRetries: Int = (System.getenv("SYNOPSIS_MAX_RETRIES") ?: "3").toInt(),
    val initialBackoffMs: Long = (System.getenv("SYNOPSIS_BACKOFF_MS") ?: "300").toLong()
) {
    companion object {
        fun getInstance(): SynopsisSettings = service<SynopsisSettings>()
    }
}
