package com.youmeandmyself.ai.providers.parsing.ui

import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * A user-provided hint about how to parse responses from a specific provider.
 *
 * When the heuristic guesses wrong and the user corrects it, we save their
 * choice as a FormatHint. Next time we see a response from the same provider,
 * we try their hint first before falling back to heuristics.
 *
 * ## Learning Flow
 *
 * 1. Unknown response arrives → heuristic guesses → shows gibberish
 * 2. User clicks "Not right?" → sees ranked candidates → picks correct one
 * 3. We save FormatHint with the path they chose
 * 4. Next response from same provider → try their path first
 * 5. If path works → instant correct result (Scenario 1)
 * 6. If path fails → fall back to heuristic (structure may have changed)
 *
 * ## Matching Strategy
 *
 * Hints are matched by providerId first, then optionally by modelId for
 * providers that have different response formats per model.
 *
 * @property providerId The provider this hint applies to (e.g., "deepseek", "custom-llm")
 * @property modelId Optional model ID for model-specific hints
 * @property contentPath The JSON path where content was found (e.g., ".data.response.text")
 * @property createdAt When the user provided this hint
 * @property lastUsed When this hint was last successfully used
 * @property successCount How many times this hint worked
 * @property failureCount How many times this hint failed (path didn't exist or was empty)
 */
@Serializable
data class FormatHint(
    val providerId: String,
    val modelId: String? = null,
    val contentPath: String,
    val createdAt: Long = System.currentTimeMillis(),
    val lastUsed: Long = System.currentTimeMillis(),
    val successCount: Int = 0,
    val failureCount: Int = 0
) {
    /**
     * Check if this hint matches a given provider/model combination.
     */
    fun matches(targetProviderId: String, targetModelId: String?): Boolean {
        if (providerId != targetProviderId) return false
        // If hint has no modelId, it applies to all models from this provider
        // If hint has modelId, it only applies to that specific model
        return modelId == null || modelId == targetModelId
    }

    /**
     * Create an updated hint after successful use.
     */
    fun recordSuccess(): FormatHint = copy(
        lastUsed = System.currentTimeMillis(),
        successCount = successCount + 1
    )

    /**
     * Create an updated hint after failed use.
     */
    fun recordFailure(): FormatHint = copy(
        failureCount = failureCount + 1
    )

    /**
     * Calculate reliability score (for choosing between multiple hints).
     * Higher = more reliable.
     */
    fun reliabilityScore(): Double {
        val total = successCount + failureCount
        if (total == 0) return 0.5 // New hint, neutral score
        return successCount.toDouble() / total
    }

    /**
     * Check if this hint should be retired (too many failures).
     */
    fun shouldRetire(): Boolean {
        // Retire if >5 failures and <50% success rate
        return failureCount > 5 && reliabilityScore() < 0.5
    }

    companion object {
        /**
         * Create a new hint from user selection.
         */
        fun fromUserSelection(
            providerId: String,
            modelId: String?,
            selectedCandidate: TextCandidate
        ): FormatHint = FormatHint(
            providerId = providerId,
            modelId = modelId,
            contentPath = selectedCandidate.path
        )
    }
}

/**
 * Storage interface for format hints.
 *
 * Hints are stored at the application level (not per-project) since
 * provider response formats are consistent across projects.
 */
interface FormatHintStorage {
    /**
     * Find the best hint for a provider/model combination.
     * Returns the most reliable matching hint, or null if none found.
     */
    suspend fun findHint(providerId: String, modelId: String?): FormatHint?

    /**
     * Save or update a hint.
     */
    suspend fun saveHint(hint: FormatHint)

    /**
     * Remove a hint (e.g., when user wants to reset).
     */
    suspend fun removeHint(providerId: String, modelId: String?)

    /**
     * Get all hints (for debugging/settings UI).
     */
    suspend fun getAllHints(): List<FormatHint>
}