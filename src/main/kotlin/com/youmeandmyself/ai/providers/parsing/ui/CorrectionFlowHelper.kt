package com.youmeandmyself.ai.providers.parsing.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.youmeandmyself.ai.providers.parsing.ParsedResponse
import com.youmeandmyself.ai.providers.parsing.ParseMetadata
import com.youmeandmyself.ai.providers.parsing.ParseStrategy
import com.youmeandmyself.ai.providers.parsing.Confidence
import com.youmeandmyself.storage.StorageFacade
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Handles the correction flow for AI response parsing.
 *
 * This helper encapsulates all the logic for:
 * - Deciding when to show correction dialogs
 * - Fetching raw JSON from storage
 * - Showing dialogs and processing results
 * - Saving format hints for future use
 *
 * ## Three Scenarios
 *
 * **Scenario 1: Known Format**
 * - Confidence is HIGH, known schema detected
 * - Just display the response, no correction needed
 * - `needsUserInteraction()` returns false
 *
 * **Scenario 2: Heuristic with Good Confidence**
 * - Used JSON walk heuristic, confidence is MEDIUM or better
 * - Show response immediately, but offer "[Not right?]" option
 * - `shouldOfferPostCorrection()` returns true
 *
 * **Scenario 3: Low Confidence / Unknown**
 * - Heuristic used but confidence is LOW or NONE
 * - Show dialog BEFORE displaying anything
 * - `shouldAskImmediately()` returns true
 *
 * @param project Current project (for dialogs)
 * @param storageFacade Storage access for retrieving raw JSON
 * @param hintStorage Format hint persistence
 */
class CorrectionFlowHelper(
    private val project: Project,
    private val storageFacade: StorageFacade,
    private val hintStorage: FormatHintStorage = FormatHintStorageImpl.getInstance()
) {
    private val log = Logger.getInstance(CorrectionFlowHelper::class.java)

    private val projectId: String = project.locationHash

    /**
     * State for tracking the last response that can be corrected.
     * Used when user clicks "Not right?" after the fact.
     */
    data class CorrectionContext(
        val exchangeId: String,
        val providerId: String,
        val modelId: String?,
        val candidates: List<TextCandidate>,
        val originalDisplayText: String
    )

    // Track the last correctable response (for Scenario 2 post-correction)
    // Internal so ChatPanel can access for /raw command
    internal var lastCorrectionContext: CorrectionContext? = null

    /**
     * Test raw JSON override for dev mode testing.
     * When set, fetchRawJson() returns this instead of querying storage.
     * Cleared when correction context is cleared.
     */
    private var testRawJsonOverride: String? = null

    /**
     * Check if we should ask the user immediately (Scenario 3).
     */
    fun shouldAskImmediately(result: ParsedResponse): Boolean {
        return result.metadata.shouldAskUserImmediately
    }

    /**
     * Check if we should offer post-correction option (Scenario 2).
     */
    fun shouldOfferPostCorrection(result: ParsedResponse): Boolean {
        return result.metadata.shouldOfferCorrection && !shouldAskImmediately(result)
    }

    /**
     * Check if this is a clean known-format response (Scenario 1).
     */
    fun isKnownFormat(result: ParsedResponse): Boolean {
        return !result.metadata.wasHeuristicUsed && result.metadata.confidence == Confidence.HIGH
    }

    /**
     * Handle Scenario 3: Show dialog immediately before displaying response.
     *
     * Shows CorrectionDialog on IntelliJ's EDT (Event Dispatch Thread) and
     * waits for user selection. Must be called from a background thread.
     *
     * @param result The parsed response with low confidence
     * @param providerId Provider that generated this response
     * @param modelId Model that generated this response
     * @return CorrectedResponse with the user's selection, or null if cancelled
     */
    suspend fun handleImmediateCorrection(
        result: ParsedResponse,
        providerId: String,
        modelId: String?
    ): CorrectedResponse? {
        if (result.metadata.candidates.isEmpty()) {
            log.warn("handleImmediateCorrection called but no candidates available")
            return null
        }

        val rawJson = fetchRawJson(result.exchangeId)
        if (rawJson == null) {
            log.warn("Could not fetch raw JSON for exchange ${result.exchangeId}")
            return null
        }

        // Show dialog on EDT using IntelliJ's threading utilities
        // CompletableFuture bridges the EDT callback to our coroutine
        val future = CompletableFuture<CorrectionResult?>()

        ApplicationManager.getApplication().invokeLater({
            try {
                val correctionResult = CorrectionDialog.show(
                    project = project,
                    candidates = result.metadata.candidates,
                    rawJson = rawJson,
                    providerId = providerId,
                    modelId = modelId,
                    isCorrection = false // Scenario 3 = initial selection
                )
                future.complete(correctionResult)
            } catch (e: Exception) {
                log.error("Error showing correction dialog", e)
                future.complete(null)
            }
        }, ModalityState.defaultModalityState())

        // Wait for dialog result (with timeout to prevent hanging)
        val correctionResult = withContext(Dispatchers.IO) {
            try {
                future.get(5, TimeUnit.MINUTES) // User has time to think
            } catch (e: Exception) {
                log.warn("Dialog wait interrupted or timed out", e)
                null
            }
        }

        if (correctionResult == null) {
            // User cancelled - they'll see the best guess
            return null
        }

        // Save hint if requested
        if (correctionResult.shouldRemember && correctionResult.hint != null) {
            hintStorage.saveHint(correctionResult.hint)
            log.info("Saved format hint for $providerId/${modelId ?: "all"}: ${correctionResult.hint.contentPath}")
        }

        return CorrectedResponse(
            displayText = correctionResult.selectedCandidate.text,
            rawText = correctionResult.selectedCandidate.text,
            wasUserCorrected = true
        )
    }

    /**
     * Store context for potential post-correction (Scenario 2).
     * Call this after displaying a heuristic-based response.
     *
     * @param result The parsed response
     * @param providerId Provider that generated this response
     * @param modelId Model that generated this response (for format hints)
     * @param force If true, store even if shouldOfferPostCorrection returns false.
     *              Used by dev mode testing to test correction UI with any scenario.
     */
    fun storeForPostCorrection(
        result: ParsedResponse,
        providerId: String,
        modelId: String?,
        force: Boolean = false
    ) {
        if (!force && !shouldOfferPostCorrection(result)) return

        lastCorrectionContext = CorrectionContext(
            exchangeId = result.exchangeId,
            providerId = providerId,
            modelId = modelId,
            candidates = result.metadata.candidates,
            originalDisplayText = result.displayText
        )
    }

    /**
     * Check if there's a response available for post-correction.
     */
    fun hasCorrectableResponse(): Boolean {
        return lastCorrectionContext != null
    }

    /**
     * Handle Scenario 2: User clicked "Not right?" after seeing response.
     *
     * Shows CorrectionDialog on IntelliJ's EDT and waits for user selection.
     * Must be called from a background thread.
     *
     * @return CorrectedResponse with user's selection, or null if cancelled/unavailable
     */
    suspend fun handlePostCorrection(): CorrectedResponse? {
        val context = lastCorrectionContext ?: return null

        val rawJson = fetchRawJson(context.exchangeId)
        if (rawJson == null) {
            log.warn("Could not fetch raw JSON for exchange ${context.exchangeId}")
            clearCorrectionContext()
            return null
        }

        // Show dialog on EDT using IntelliJ's threading utilities
        val future = CompletableFuture<CorrectionResult?>()

        ApplicationManager.getApplication().invokeLater({
            try {
                val correctionResult = CorrectionDialog.show(
                    project = project,
                    candidates = context.candidates,
                    rawJson = rawJson,
                    providerId = context.providerId,
                    modelId = context.modelId,
                    isCorrection = true // Scenario 2 = correcting previous guess
                )
                future.complete(correctionResult)
            } catch (e: Exception) {
                log.error("Error showing correction dialog", e)
                future.complete(null)
            }
        }, ModalityState.defaultModalityState())

        // Wait for dialog result
        val correctionResult = withContext(Dispatchers.IO) {
            try {
                future.get(5, TimeUnit.MINUTES)
            } catch (e: Exception) {
                log.warn("Dialog wait interrupted or timed out", e)
                null
            }
        }

        if (correctionResult == null) {
            // User cancelled
            return null
        }

        // Save hint if requested
        if (correctionResult.shouldRemember && correctionResult.hint != null) {
            hintStorage.saveHint(correctionResult.hint)
            log.info("Saved format hint for ${context.providerId}/${context.modelId ?: "all"}: ${correctionResult.hint.contentPath}")
        }

        // Note: We intentionally do NOT clear the correction context here.
        // This allows the user to /correct again if they picked the wrong option.
        // Context is only cleared when a new message is sent.

        return CorrectedResponse(
            displayText = correctionResult.selectedCandidate.text,
            rawText = correctionResult.selectedCandidate.text,
            wasUserCorrected = true
        )
    }

    /**
     * Show raw JSON dialog for debugging.
     *
     * Displays RawResponseDialog on IntelliJ's EDT. Non-blocking since
     * we don't need to wait for any result.
     */
    suspend fun showRawResponse(exchangeId: String, providerId: String?, modelId: String?) {
        val rawJson = fetchRawJson(exchangeId)
        if (rawJson == null) {
            log.warn("Could not fetch raw JSON for exchange $exchangeId")
            return
        }

        // Show dialog on EDT - fire and forget, no need to wait
        ApplicationManager.getApplication().invokeLater({
            try {
                RawResponseDialog.show(project, rawJson, providerId, modelId)
            } catch (e: Exception) {
                log.error("Error showing raw response dialog", e)
            }
        }, ModalityState.defaultModalityState())
    }

    /**
     * Clear stored correction context (e.g., when new message is sent).
     */
    fun clearCorrectionContext() {
        lastCorrectionContext = null
        testRawJsonOverride = null
    }

    /**
     * Store raw JSON for dev mode testing.
     *
     * Test responses don't persist to real storage, so this allows
     * the /raw command to work during dev testing.
     *
     * Called by DevCommandHandler after storing test correction context.
     *
     * @param rawJson The fake raw JSON to return for this test context
     */
    fun storeTestRawJson(rawJson: String) {
        testRawJsonOverride = rawJson
    }

    // --- Internal helpers ---

    /**
     * Fetch raw JSON for an exchange.
     *
     * Checks test override first (for dev mode), then falls back to storage.
     */
    private suspend fun fetchRawJson(exchangeId: String): String? {
        // Check for dev mode test override first
        testRawJsonOverride?.let { return it }

        return withContext(Dispatchers.IO) {
            try {
                storageFacade.getExchange(exchangeId, projectId)?.rawResponse?.json
            } catch (e: Exception) {
                log.error("Failed to fetch exchange $exchangeId", e)
                null
            }
        }
    }
}

/**
 * Result of a user correction.
 */
data class CorrectedResponse(
    val displayText: String,
    val rawText: String,
    val wasUserCorrected: Boolean
)