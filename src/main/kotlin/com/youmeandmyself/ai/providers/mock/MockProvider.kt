// ==========================
// File: src/main/kotlin/com/youmeandmyself/ai/providers/mock/MockProvider.kt
// ==========================
// path: src/main/kotlin/com/youmeandmyself/ai/providers/mock/MockProvider.kt — Offline/dev mock provider
package com.youmeandmyself.ai.providers

import kotlinx.coroutines.delay

/**
 * Mock provider for offline/dev testing. Always succeeds after a short delay.
 */
object MockProvider : AiProvider {
    override val id = "mock"
    override val displayName = "Mock"
    override suspend fun ping(): String {
        delay(150) // Simulate work
        return "ok (simulated)"
    }

    // placeholder for now, will update later
    override suspend fun chat(prompt: String): String {
        throw UnsupportedOperationException("${displayName}: chat() not implemented yet")
    }
}