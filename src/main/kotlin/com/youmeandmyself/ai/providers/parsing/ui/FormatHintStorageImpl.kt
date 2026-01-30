package com.youmeandmyself.ai.providers.parsing.ui

import com.intellij.ide.util.PropertiesComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Persists format hints at the application level using IntelliJ's PropertiesComponent.
 *
 * Hints are stored as JSON in application-level properties, making them available
 * across all projects. This is appropriate because provider response formats are
 * consistent regardless of which project you're working in.
 *
 * Storage key: "ymm.formatHints" → JSON array of FormatHint objects
 */
class FormatHintStorageImpl : FormatHintStorage {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    companion object {
        private const val STORAGE_KEY = "ymm.formatHints"

        @Volatile
        private var instance: FormatHintStorageImpl? = null

        fun getInstance(): FormatHintStorageImpl {
            return instance ?: synchronized(this) {
                instance ?: FormatHintStorageImpl().also { instance = it }
            }
        }
    }

    override suspend fun findHint(providerId: String, modelId: String?): FormatHint? {
        return withContext(Dispatchers.IO) {
            val hints = loadAllHints()

            // Find matching hints, preferring model-specific over provider-wide
            val matching = hints.filter { it.matches(providerId, modelId) }

            when {
                matching.isEmpty() -> null
                matching.size == 1 -> matching.first()
                else -> {
                    // Multiple matches: prefer model-specific, then highest reliability
                    matching
                        .sortedWith(
                            compareByDescending<FormatHint> { it.modelId != null } // model-specific first
                                .thenByDescending { it.reliabilityScore() }
                        )
                        .first()
                }
            }
        }
    }

    override suspend fun saveHint(hint: FormatHint) {
        withContext(Dispatchers.IO) {
            val hints = loadAllHints().toMutableList()

            // Remove existing hint for same provider/model combination
            hints.removeIf { it.providerId == hint.providerId && it.modelId == hint.modelId }

            // Add the new/updated hint
            hints.add(hint)

            // Remove retired hints while we're at it
            hints.removeIf { it.shouldRetire() }

            saveAllHints(hints)
        }
    }

    override suspend fun removeHint(providerId: String, modelId: String?) {
        withContext(Dispatchers.IO) {
            val hints = loadAllHints().toMutableList()
            hints.removeIf { it.providerId == providerId && it.modelId == modelId }
            saveAllHints(hints)
        }
    }

    override suspend fun getAllHints(): List<FormatHint> {
        return withContext(Dispatchers.IO) {
            loadAllHints()
        }
    }

    /**
     * Record a successful use of a hint (updates lastUsed and successCount).
     */
    suspend fun recordSuccess(providerId: String, modelId: String?) {
        withContext(Dispatchers.IO) {
            val hint = findHint(providerId, modelId) ?: return@withContext
            saveHint(hint.recordSuccess())
        }
    }

    /**
     * Record a failed use of a hint (updates failureCount).
     */
    suspend fun recordFailure(providerId: String, modelId: String?) {
        withContext(Dispatchers.IO) {
            val hint = findHint(providerId, modelId) ?: return@withContext
            saveHint(hint.recordFailure())
        }
    }

    // --- Internal helpers ---

    private fun loadAllHints(): List<FormatHint> {
        val properties = PropertiesComponent.getInstance()
        val stored = properties.getValue(STORAGE_KEY) ?: return emptyList()

        return try {
            json.decodeFromString<List<FormatHint>>(stored)
        } catch (e: Exception) {
            // Corrupted data - start fresh
            emptyList()
        }
    }

    private fun saveAllHints(hints: List<FormatHint>) {
        val properties = PropertiesComponent.getInstance()
        val serialized = json.encodeToString(hints)
        properties.setValue(STORAGE_KEY, serialized)
    }
}