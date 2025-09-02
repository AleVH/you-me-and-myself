// ==========================
// File: src/main/kotlin/com/youmeandmyself/ai/providers/AiProvider.kt
// ==========================
// path: src/main/kotlin/com/youmeandmyself/ai/providers/AiProvider.kt — Provider interface
package com.youmeandmyself.ai.providers

import kotlinx.coroutines.CancellationException

/**
 * Core contract every AI provider must implement.
 * Implementations should be side-effect free and throw exceptions with useful messages on failure.
 */
interface AiProvider {
    /** A short, unique id (e.g., "openai", "gemini", "mock"). */
    val id: String

    /** Human-friendly name for UI. */
    val displayName: String

    /**
     * Quick health check. Should be fast, low cost, and not consume significant quota.
     * Return a brief success message. Throw on failure.
     */
    suspend fun ping(): String

    /**
     * Return a model-generated reply for the given prompt.
     * This is used by the Chat UI; keep it simple and synchronous (no streaming yet).
     */
    suspend fun chat(prompt: String): String

    /**
     * Optional helper for UIs that list available model ids.
     * Providers that cannot enumerate models can just return emptyList().
     */
    suspend fun listModels(): List<String> = emptyList()
}