package com.youmeandmyself.dev

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.youmeandmyself.ai.chat.bridge.BridgeMessage
import com.youmeandmyself.ai.chat.bridge.BridgeMessage.ContextFileDetailDto
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Self-contained mock badge simulator for `/dev-mock-badges`.
 *
 * Generates timed [BridgeMessage.ContextProgressEvent] and [BridgeMessage.ContextBadgeUpdateEvent]
 * events that exercise the full badge tray UI without calling any AI provider,
 * writing to any database, or polluting any cache.
 *
 * ## Lifecycle & Cleanup
 *
 * Only ONE simulation can run at a time. Starting a new simulation cancels
 * any in-flight one immediately. The scheduler is a single-thread pool created
 * on first use and shared across invocations. All scheduled futures are tracked
 * and cancelled on re-entry.
 *
 * ## Scenarios
 *
 * | Name         | Description                                                    |
 * |--------------|----------------------------------------------------------------|
 * | `default`    | 4 files detected, 2 transition from RAW→SUMMARY               |
 * | `force`      | Ghost badge transitions to real badge during detection         |
 * | `force-only` | Context dial OFF, only forced element is gathered              |
 * | `many`       | 12 files — tests tray scrolling and overflow                   |
 *
 * ## Side Effects
 *
 * **None.** The simulator reads only `project.basePath` (for realistic file paths)
 * and the passed `sendEvent` function. It does NOT read or write:
 * - EditorElementResolver (no PSI access)
 * - SummaryPipeline / SummaryCache (no summaries)
 * - LocalStorageFacade / SQLite (no DB)
 * - AI providers (no tokens spent)
 *
 * @param project Used only for `project.basePath` to build realistic paths
 * @param sendEvent Bridge transport function — sends serialized JSON to the frontend
 */
class MockBadgeSimulator(
    private val project: Project,
    private val sendEvent: (String) -> Unit
) {
    private val log = Dev.logger(MockBadgeSimulator::class.java)

    companion object {
        /**
         * Shared scheduler. Single thread is enough — events are lightweight
         * and the staggering is purely for visual effect.
         */
        private val scheduler: ScheduledExecutorService by lazy {
            Executors.newSingleThreadScheduledExecutor { r ->
                Thread(r, "ymm-mock-badge-sim").apply { isDaemon = true }
            }
        }

        /**
         * Currently scheduled futures. Tracked so we can cancel on re-entry.
         * Accessed only from the scheduler thread + the caller thread, but
         * the operations are idempotent (cancel is safe to call multiple times).
         */
        private val activeFutures = mutableListOf<ScheduledFuture<*>>()

        /**
         * Whether a simulation is currently in progress.
         * Checked at the start of [run] to log cleanup.
         */
        @Volatile
        private var running = false
    }

    // ═══════════════════════════════════════════════════════════════════
    //  PUBLIC API
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Run a mock badge simulation for the given scenario.
     *
     * Cancels any previously running simulation before starting.
     * All events are dispatched on the EDT via [ApplicationManager.invokeLater].
     *
     * @param scenario One of: "default", "force", "force-only", "many"
     * @param tabId The tab that issued the command — events are tagged so the frontend ignores them on other tabs
     */
    fun run(scenario: String, tabId: String? = null) {
        // ── Cleanup previous run ────────────────────────────────
        if (running) {
            Dev.info(log, "mock.badges.cancel_previous",
                "reason" to "new simulation requested",
                "scenario" to scenario
            )
        }
        cancelAll()

        running = true
        Dev.info(log, "mock.badges.start", "scenario" to scenario)

        val basePath = project.basePath ?: "/project"
        val timeline = when (scenario.lowercase()) {
            "force" -> buildForceScenario(basePath)
            "force-only" -> buildForceOnlyScenario(basePath)
            "many" -> buildManyScenario(basePath)
            else -> buildDefaultScenario(basePath)
        }

        // ── Schedule each event ────────────────────────────────
        for ((delayMs, rawEvent) in timeline) {
            // Stamp tabId onto the event so the frontend can filter by tab
            val event = when (rawEvent) {
                is BridgeMessage.ContextProgressEvent -> rawEvent.copy(tabId = tabId)
                is BridgeMessage.ContextBadgeUpdateEvent -> rawEvent.copy(tabId = tabId)
                else -> rawEvent
            }
            val future = scheduler.schedule({
                ApplicationManager.getApplication().invokeLater {
                    try {
                        val json = BridgeMessage.serializeEvent(event)
                        sendEvent(json)
                    } catch (e: Exception) {
                        Dev.warn(log, "mock.badges.send_failed", e)
                    }
                }
            }, delayMs, TimeUnit.MILLISECONDS)
            activeFutures.add(future)
        }

        // ── Schedule cleanup marker ────────────────────────────
        val lastDelay = timeline.maxOfOrNull { it.first } ?: 0L
        val cleanupFuture = scheduler.schedule({
            running = false
            activeFutures.clear()
            Dev.info(log, "mock.badges.complete", "scenario" to scenario)
        }, lastDelay + 100, TimeUnit.MILLISECONDS)
        activeFutures.add(cleanupFuture)
    }

    // ═══════════════════════════════════════════════════════════════════
    //  CLEANUP
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Cancel all pending events from the current simulation.
     *
     * Called at the start of [run] to ensure only one simulation is active.
     * Safe to call multiple times — cancelled futures are removed.
     */
    private fun cancelAll() {
        for (future in activeFutures) {
            future.cancel(false)
        }
        activeFutures.clear()
        running = false
    }

    // ═══════════════════════════════════════════════════════════════════
    //  SCENARIO BUILDERS
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Default scenario: 4 files detected, 2 transition from RAW to SUMMARY.
     *
     * Timeline:
     * - 0–1.5s: detection phase (badges appear one by one, all RAW initially)
     * - 1.5–3s: enrichment phase (badges 3 and 4 transition RAW→SUMMARY)
     * - 3s: complete
     */
    private fun buildDefaultScenario(basePath: String): List<Pair<Long, BridgeMessage.Event>> {
        val badge1 = badge("MetricsService.kt", "$basePath/src/main/kotlin/metrics/MetricsService.kt",
            "file", "RAW", 1950, "kotlin")
        val badge2 = badge("ChatOrchestrator.kt", "$basePath/src/main/kotlin/chat/ChatOrchestrator.kt",
            "file", "RAW", 3200, "kotlin")
        val badge3Raw = badge("ContextAssembler.kt", "$basePath/src/main/kotlin/context/ContextAssembler.kt",
            "file", "RAW", 4800, "kotlin")
        val badge3Summary = badge3Raw.copy(kind = "SUMMARY", tokens = 400, freshness = "fresh")
        val badge4Raw = badge("SummaryPipeline.kt", "$basePath/src/main/kotlin/summary/SummaryPipeline.kt",
            "file", "RAW", 2900, "kotlin")
        val badge4Summary = badge4Raw.copy(kind = "SUMMARY", tokens = 350, freshness = "fresh")

        return listOf(
            0L to progress("detecting", 0, "Starting context detection…"),
            200L to progress("detecting", 15),
            400L to progress("detecting", 25, "Running LanguageDetector…"),
            400L to badges(listOf(badge1)),
            700L to progress("detecting", 40, "Running FrameworkDetector…"),
            800L to badges(listOf(badge1, badge2)),
            1100L to progress("detecting", 60, "Running RelevantFilesDetector…"),
            1300L to badges(listOf(badge1, badge2, badge3Raw)),
            1500L to badges(listOf(badge1, badge2, badge3Raw, badge4Raw)),
            1500L to progress("detecting", 75, "Detection complete"),
            1800L to progress("summarizing", 80, "Enriching with summaries…"),
            2200L to badges(listOf(badge1, badge2, badge3Summary, badge4Raw)),
            2500L to badges(listOf(badge1, badge2, badge3Summary, badge4Summary)),
            2500L to progress("summarizing", 95, "Summaries applied"),
            3000L to progress("complete", 100, "Context ready"),
            3000L to badgesFinal(listOf(badge1, badge2, badge3Summary, badge4Summary)),
        )
    }

    /**
     * Force scenario: ghost badge transitions to real badge during detection.
     *
     * Simulates: user clicks Force Method, then sends message. The forced
     * element appears in detection results and the ghost badge transitions
     * to a real badge (RAW initially, then SUMMARY).
     */
    private fun buildForceScenario(basePath: String): List<Pair<Long, BridgeMessage.Event>> {
        // The forced element — same name/signature pattern as what RESOLVE_FORCE_CONTEXT_RESULT would return
        val forcedRaw = badge("processRefund", "$basePath/src/main/kotlin/payment/PaymentService.kt",
            "method", "RAW", 850, "kotlin", forced = true,
            elementSignature = "PaymentService#processRefund(String, Int)")
        val forcedSummary = forcedRaw.copy(kind = "SUMMARY", tokens = 120, freshness = "fresh")

        val badge1 = badge("PaymentService.kt", "$basePath/src/main/kotlin/payment/PaymentService.kt",
            "file", "RAW", 2100, "kotlin")
        val badge2 = badge("RefundPolicy.kt", "$basePath/src/main/kotlin/payment/RefundPolicy.kt",
            "file", "RAW", 1400, "kotlin")

        return listOf(
            0L to progress("detecting", 0, "Starting context detection…"),
            300L to progress("detecting", 20),
            600L to progress("detecting", 40, "Resolving forced element…"),
            // Forced element appears — ghost badge should transition here
            1000L to badges(listOf(forcedRaw)),
            1000L to progress("detecting", 55, "Forced element resolved"),
            1400L to badges(listOf(forcedRaw, badge1)),
            1400L to progress("detecting", 70),
            1800L to badges(listOf(forcedRaw, badge1, badge2)),
            1800L to progress("detecting", 75, "Detection complete"),
            2000L to progress("summarizing", 80, "Summarizing forced element…"),
            2300L to badges(listOf(forcedSummary, badge1, badge2)),
            2500L to progress("summarizing", 95),
            3000L to progress("complete", 100, "Context ready"),
            3000L to badgesFinal(listOf(forcedSummary, badge1, badge2)),
        )
    }

    /**
     * Force-only scenario: context dial is OFF, only the forced element is gathered.
     *
     * Timeline is shorter since there's no automatic detection.
     */
    private fun buildForceOnlyScenario(basePath: String): List<Pair<Long, BridgeMessage.Event>> {
        val forcedRaw = badge("calculateTotal", "$basePath/src/main/kotlin/billing/InvoiceService.kt",
            "method", "RAW", 640, "kotlin", forced = true,
            elementSignature = "InvoiceService#calculateTotal(List)")
        val forcedSummary = forcedRaw.copy(kind = "SUMMARY", tokens = 95, freshness = "fresh")

        return listOf(
            0L to progress("detecting", 0, "Context dial OFF — forced element only"),
            500L to progress("detecting", 40, "Resolving forced element…"),
            800L to badges(listOf(forcedRaw)),
            800L to progress("detecting", 60, "Forced element resolved"),
            1200L to progress("summarizing", 75, "Summarizing…"),
            1500L to badges(listOf(forcedSummary)),
            1500L to progress("summarizing", 90),
            2000L to progress("complete", 100, "Context ready"),
            2000L to badgesFinal(listOf(forcedSummary)),
        )
    }

    /**
     * Many scenario: 12 files — tests tray scrolling/wrapping and badge truncation.
     *
     * Mix of RAW and SUMMARY badges, different scopes, different languages.
     * Longer timeline (0–8s) to simulate a large project scan.
     */
    private fun buildManyScenario(basePath: String): List<Pair<Long, BridgeMessage.Event>> {
        val files = listOf(
            badge("UserService.kt", "$basePath/src/user/UserService.kt", "class", "RAW", 3400, "kotlin"),
            badge("AuthController.kt", "$basePath/src/auth/AuthController.kt", "class", "RAW", 2100, "kotlin"),
            badge("DatabaseConfig.kt", "$basePath/src/config/DatabaseConfig.kt", "config", "RAW", 800, "kotlin"),
            badge("ApiRouter.ts", "$basePath/react-frontend/src/api/ApiRouter.ts", "file", "RAW", 1600, "typescript"),
            badge("useBridge.ts", "$basePath/react-frontend/src/hooks/useBridge.ts", "file", "RAW", 5200, "typescript"),
            badge("ChatApp.tsx", "$basePath/react-frontend/src/components/ChatApp.tsx", "file", "RAW", 1900, "typescript"),
            badge("build.gradle.kts", "$basePath/build.gradle.kts", "config", "RAW", 900, "kotlin"),
            badge("PaymentService.kt", "$basePath/src/payment/PaymentService.kt", "class", "RAW", 2800, "kotlin"),
        )

        // Summaries for 4 of the files
        val summaries = listOf(
            files[0].copy(kind = "SUMMARY", tokens = 380, freshness = "fresh"),
            files[1].copy(kind = "SUMMARY", tokens = 290, freshness = "fresh"),
            files[4].copy(kind = "SUMMARY", tokens = 520, freshness = "cached"),
            files[7].copy(kind = "SUMMARY", tokens = 340, freshness = "fresh"),
        )

        val timeline = mutableListOf<Pair<Long, BridgeMessage.Event>>()

        // Detection phase: badges appear one by one over 5 seconds
        timeline.add(0L to progress("detecting", 0, "Starting context detection…"))
        for (i in files.indices) {
            val delay = (500 + i * 550).toLong()
            val percent = 10 + (i * 60 / files.size)
            timeline.add(delay to badges(files.subList(0, i + 1)))
            timeline.add(delay to progress("detecting", percent))
        }

        // Enrichment phase: 4 summaries applied over 5–8s
        timeline.add(5000L to progress("summarizing", 75, "Enriching with summaries…"))
        val enriched = files.toMutableList()
        for ((j, summary) in summaries.withIndex()) {
            val idx = files.indexOf(files.find { it.path == summary.path })
            if (idx >= 0) enriched[idx] = summary
            val delay = 5500L + j * 700
            val percent = 80 + (j * 15 / summaries.size)
            timeline.add(delay to badges(enriched.toList()))
            timeline.add(delay to progress("summarizing", percent))
        }

        // Complete
        timeline.add(8000L to progress("complete", 100, "Context ready"))
        timeline.add(8000L to badgesFinal(enriched.toList()))

        return timeline
    }

    // ═══════════════════════════════════════════════════════════════════
    //  HELPERS
    // ═══════════════════════════════════════════════════════════════════

    /** Build a mock badge DTO. */
    private fun badge(
        name: String,
        path: String,
        scope: String,
        kind: String,
        tokens: Int,
        lang: String,
        forced: Boolean = false,
        isStale: Boolean = false,
        freshness: String = "cached",
        elementSignature: String? = null
    ): ContextFileDetailDto = ContextFileDetailDto(
        name = name,
        path = path,
        scope = scope,
        kind = kind,
        tokens = tokens,
        lang = lang,
        forced = forced,
        isStale = isStale,
        freshness = freshness,
        elementSignature = elementSignature
    )

    /** Build a CONTEXT_PROGRESS event. */
    private fun progress(stage: String, percent: Int, message: String? = null): BridgeMessage.ContextProgressEvent =
        BridgeMessage.ContextProgressEvent(stage = stage, percent = percent, message = message)

    /** Build a CONTEXT_BADGE_UPDATE event (not complete). */
    private fun badges(list: List<ContextFileDetailDto>): BridgeMessage.ContextBadgeUpdateEvent =
        BridgeMessage.ContextBadgeUpdateEvent(badges = list, complete = false)

    /** Build a final CONTEXT_BADGE_UPDATE event (complete = true). */
    private fun badgesFinal(list: List<ContextFileDetailDto>): BridgeMessage.ContextBadgeUpdateEvent =
        BridgeMessage.ContextBadgeUpdateEvent(badges = list, complete = true)
}
