package com.youmeandmyself.ai.metrics

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.youmeandmyself.ai.chat.bridge.BridgeMessage
import com.youmeandmyself.ai.settings.AiProfilesState
import com.youmeandmyself.dev.Dev
import com.youmeandmyself.storage.LocalStorageFacade
import com.youmeandmyself.storage.model.ExchangeTokenUsage

/**
 * Central service for recording AI token usage metrics.
 *
 * ## Why This Exists
 *
 * Before this service, UpdateMetricsEvent was constructed directly in
 * BridgeDispatcher from ChatResult fields — a quick inline emit with
 * no persistence. The Metrics Module needs a proper write path:
 *
 * ```
 * Provider Response
 *     │
 *     ▼
 * ChatOrchestrator.send() → ChatResult (with tokenUsage + responseTimeMs)
 *     │
 *     ▼
 * BridgeDispatcher → MetricsService.recordExchange()
 *     │
 *     ├── Build MetricsRecord
 *     ├── Write to SQLite (metrics table)
 *     ├── Write to JSONL (embedded in exchange record)
 *     └── Return UpdateMetricsEvent for bridge to emit
 * ```
 *
 * ## What This Service Owns
 *
 * - Building MetricsRecord from ChatResult + profile context
 * - Persisting to the metrics SQLite table
 * - Building the enhanced UpdateMetricsEvent
 *
 * ## What This Service Does NOT Own
 *
 * - Token extraction from provider responses (that's the provider layer)
 * - The chat_exchanges table (metrics references it via foreign key)
 * - Bridge transport (caller emits the returned event)
 * - Cost calculation (that's the Pricing Module, post-launch)
 *
 * ## Thread Safety
 *
 * recordExchange() is called from BridgeDispatcher's coroutine scope
 * (Dispatchers.IO). LocalStorageFacade has its own write mutex, so
 * concurrent calls from different tabs are safe.
 *
 * @param project The IntelliJ project this service is scoped to
 */
@Service(Service.Level.PROJECT)
class MetricsService(private val project: Project) {

    private val log = Dev.logger(MetricsService::class.java)

    /**
     * Record metrics for a completed AI exchange.
     *
     * Builds a MetricsRecord, persists it to SQLite, and returns a
     * fully-populated UpdateMetricsEvent for the caller to emit via
     * the bridge.
     *
     * This is the single entry point for all metrics recording. Both
     * chat and summary exchanges go through here.
     *
     * ## Error Handling
     *
     * If SQLite write fails, the error is logged but the event is still
     * returned. The frontend gets its metrics update regardless — the
     * persistence failure can be recovered later from JSONL rebuild.
     * Metrics recording should never block or break the chat flow.
     *
     * @param exchangeId The exchange ID (links to chat_exchanges.id)
     * @param conversationId The conversation this exchange belongs to
     * @param providerId The AI profile ID that handled this exchange
     * @param tokenUsage Token breakdown from the provider response (nullable)
     * @param modelId Model identifier as reported by the provider
     * @param purpose Exchange purpose: "CHAT", "FILE_SUMMARY", etc.
     * @param responseTimeMs Wall-clock time for the AI call in ms (nullable)
     * @param tabId The UI tab ID (session-only, nullable for summary exchanges)
     * @return UpdateMetricsEvent ready to emit via the bridge, or null if
     *         no token data is available (nothing to display)
     */
    fun recordExchange(
        exchangeId: String,
        conversationId: String?,
        providerId: String,
        tokenUsage: ExchangeTokenUsage?,
        modelId: String?,
        purpose: String,
        responseTimeMs: Long? = null,
        tabId: String? = null
    ): BridgeMessage.UpdateMetricsEvent? {

        // If there's no token data at all, there's nothing to record or display.
        // This can happen with error responses or providers that don't report usage.
        if (tokenUsage == null) {
            Dev.info(log, "metrics.skip",
                "exchangeId" to exchangeId,
                "reason" to "no token data"
            )
            return null
        }

        try {
            // ── Resolve profile context ──────────────────────────────
            // We need the profile for: label, protocol, contextWindowSize.
            // If the profile is gone (deleted between send and now), we
            // use sensible fallbacks — metrics should never fail because
            // of a missing profile.
            val profileState = AiProfilesState.getInstance(project)
            val profile = profileState.profiles.find { it.id == providerId }

            val providerLabel = profile?.label?.ifBlank { "Unknown" } ?: "Unknown"
            val protocol = profile?.protocol?.name ?: "UNKNOWN"
            val contextWindowSize = DefaultContextWindows.resolve(
                profileContextWindowSize = profile?.contextWindowSize,
                modelName = modelId ?: profile?.model
            )

            // ── Build MetricsRecord ──────────────────────────────────
            val record = MetricsRecord(
                exchangeId = exchangeId,
                conversationId = conversationId ?: "unknown",
                tabId = tabId,
                providerId = providerId,
                providerLabel = providerLabel,
                protocol = protocol,
                model = modelId,
                promptTokens = tokenUsage.promptTokens,
                completionTokens = tokenUsage.completionTokens,
                totalTokens = tokenUsage.effectiveTotal,
                contextWindowSize = contextWindowSize,
                purpose = purpose,
                timestampMs = System.currentTimeMillis(),
                responseTimeMs = responseTimeMs,
                // Company tier fields — null at Individual launch
                userId = null,
                projectId = null
            )

            // ── Persist to SQLite ────────────────────────────────────
            // Non-blocking: failure is logged but doesn't prevent the
            // event from being returned to the frontend.
            try {
                val storage = LocalStorageFacade.getInstance(project)
                storage.insertMetricsRecord(record)

                Dev.info(log, "metrics.recorded",
                    "exchangeId" to exchangeId,
                    "model" to (modelId ?: "null"),
                    "prompt" to (tokenUsage.promptTokens ?: 0),
                    "completion" to (tokenUsage.completionTokens ?: 0),
                    "total" to (tokenUsage.effectiveTotal ?: 0),
                    "contextWindow" to (contextWindowSize ?: 0),
                    "responseTimeMs" to (responseTimeMs ?: 0)
                )
            } catch (e: Exception) {
                // SQLite failure is non-fatal. The data is still in JSONL
                // (written by ChatOrchestrator.persistExchange) and can be
                // backfilled during a database rebuild.
                Dev.warn(log, "metrics.persist_failed", e,
                    "exchangeId" to exchangeId
                )
            }

            // ── Build and return the bridge event ────────────────────
            return BridgeMessage.UpdateMetricsEvent(
                model = modelId,
                promptTokens = tokenUsage.promptTokens,
                completionTokens = tokenUsage.completionTokens,
                totalTokens = tokenUsage.effectiveTotal,
                contextWindowSize = contextWindowSize,
                responseTimeMs = responseTimeMs,
                purpose = purpose
            )

        } catch (e: Exception) {
            // Catch-all: metrics should NEVER break the chat flow.
            Dev.error(log, "metrics.record_failed", e,
                "exchangeId" to exchangeId
            )
            return null
        }
    }

    companion object {
        /**
         * Get the MetricsService instance for a project.
         *
         * Uses IntelliJ's service infrastructure — one instance per project.
         */
        fun getInstance(project: Project): MetricsService {
            return project.getService(MetricsService::class.java)
        }
    }
}