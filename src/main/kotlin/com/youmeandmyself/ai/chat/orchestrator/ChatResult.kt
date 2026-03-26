package com.youmeandmyself.ai.chat.orchestrator

import com.youmeandmyself.ai.providers.parsing.Confidence
import com.youmeandmyself.ai.providers.parsing.ParseStrategy
import com.youmeandmyself.ai.providers.parsing.ui.TextCandidate
import com.youmeandmyself.storage.model.ExchangeTokenUsage

/**
 * The single return type from [ChatOrchestrator] to any UI layer.
 *
 * ## Why This Exists
 *
 * This is the **hard boundary** between backend logic and frontend rendering.
 * The UI layer (React, Swing, or any future tech) receives this object and
 * renders it — nothing more. The UI never calls storage, never talks to
 * providers, never decides correction flow. All of that happens in the
 * orchestrator; this class carries the result.
 *
 * ## Design Contract
 *
 * - No UI framework imports (no Swing, no JCEF, no React types)
 * - No mutable state — this is a snapshot of what happened
 * - All fields needed by any UI to render a complete response
 * - The UI can be swapped (React → something else) without changing this class
 *
 * ## Correction Flow Fields
 *
 * The three correction scenarios from [CorrectionFlowHelper] are encoded here
 * so the UI knows what to show:
 *
 * - **Scenario 1** (known format): [correctionAvailable] = false, just render [displayText]
 * - **Scenario 2** (heuristic + confident): [correctionAvailable] = true, show "Not right?" hint
 * - **Scenario 3** (low confidence): handled inside orchestrator before this object is created.
 *   If user cancelled the dialog, [correctionAvailable] = true (they can retry with /correct)
 *
 * @property displayText The final text to show the user. Already corrected if Scenario 3 dialog was used.
 * @property isError True if this represents an error (provider failure, no provider selected, etc.)
 * @property exchangeId Storage ID for this exchange. Null if storage failed or was unavailable.
 *                      Used by the UI to link correction commands back to the right exchange.
 * @property conversationId The conversation this exchange belongs to. Null for first message
 *                          (conversation is created during orchestration) or if storage failed.
 * @property tokenUsage Token counts extracted from the provider response. Null if provider
 *                      didn't return usage data or if the request failed before getting a response.
 * @property modelId The AI model that generated this response. Used for metrics display.
 *                   Null if no provider was selected or request failed early.
 * @property correctionAvailable True if the user can type /correct to fix this response.
 *                               Only true for Scenario 2 (heuristic with good confidence) or
 *                               Scenario 3 where user cancelled the immediate dialog.
 * @property parseStrategy How the response was parsed (known schema, JSON walk heuristic, etc.)
 *                         Useful for debugging and metrics.
 * @property confidence How confident the parser was in extracting the right content.
 *                      HIGH = known format, MEDIUM = good heuristic guess, LOW = uncertain.
 * @property candidates Alternative text extractions the parser found. Only populated when
 *                       heuristic parsing was used. Needed by the correction dialog.
 * @property providerId The provider that handled this request. Used for display and correction flow.
 * @property contextSummary Human-readable summary of what context was attached (e.g., "3 files, ~2400 chars").
 *                          Null if no context was gathered. Shown as a system message by the UI.
 * @property contextTimeMs How long context gathering took in milliseconds. Null if no context was gathered.
 */
data class ChatResult(
    val displayText: String,
    val isError: Boolean,
    val exchangeId: String?,
    val conversationId: String?,
    val tokenUsage: ExchangeTokenUsage?,
    val modelId: String?,
    val correctionAvailable: Boolean,
    val parseStrategy: ParseStrategy,
    val confidence: Confidence,
    val candidates: List<TextCandidate>,
    val providerId: String?,
    val contextSummary: String?,
    val contextTimeMs: Long?,
    /**
     * Structured context metadata for the badge tray and sidebar.
     *
     * Each entry represents one piece of context (method, class, file)
     * that was attached to the request. Flows through to ChatResultEvent
     * and then to the React frontend for badge rendering.
     *
     * Phase D.2 — Stub: Currently always emptyList().
     *
     * @see com.youmeandmyself.ai.chat.context.ContextFileDetail
     */
    val contextFiles: List<com.youmeandmyself.ai.chat.context.ContextFileDetail> = emptyList(),
    /**
     * Wall-clock time for the AI provider call in milliseconds.
     *
     * Measured in [ChatOrchestrator.send] from before provider.chat()
     * to after. Used by MetricsService to record response time in the
     * metrics table and send to the frontend.
     *
     * Null for error results or when timing wasn't captured.
     */
    val responseTimeMs: Long? = null,

    // ── Per-block token estimates (Phase 1 — RequestBlocks) ──────────
    //
    // Estimated token counts for each section of the structured request.
    // Computed in ChatOrchestrator using the content.length / 4 heuristic.
    // These are ESTIMATES — the provider-reported promptTokens is the truth.
    // Used by MetricsService to persist per-block breakdown and by the
    // frontend stacked bar (Phase 4 UI).

    /** Estimated tokens for the system prompt (assistant profile). Null if no profile. */
    val profileTokens: Int? = null,

    /** Estimated tokens for conversation history (compacted + verbatim). */
    val historyTokens: Int? = null,

    /** Estimated tokens for the context block (summaries + raw + other). */
    val contextTokens: Int? = null,

    /** Estimated tokens for the user's message. */
    val messageTokens: Int? = null
) {
    companion object {

        /**
         * Factory for error results that happen before any provider call.
         *
         * Used when:
         * - No provider is selected or configured
         * - IDE is indexing and context is required
         * - Any pre-flight check fails
         *
         * @param message The error message to display to the user
         * @return A ChatResult marked as error with no exchange data
         */
        fun error(message: String): ChatResult = ChatResult(
            displayText = message,
            isError = true,
            exchangeId = null,
            conversationId = null,
            tokenUsage = null,
            modelId = null,
            correctionAvailable = false,
            parseStrategy = ParseStrategy.FAILED,
            confidence = Confidence.HIGH, // We're confident it's an error
            candidates = emptyList(),
            providerId = null,
            contextSummary = null,
            contextTimeMs = null,
            responseTimeMs = null
        )
    }
}