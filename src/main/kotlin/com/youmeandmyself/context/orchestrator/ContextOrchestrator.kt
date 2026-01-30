// (project-root)/src/main/kotlin/com/youmeandmyself/context/orchestrator/ContextOrchestrator.kt
package com.youmeandmyself.context.orchestrator

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.DumbService
import kotlinx.coroutines.*

/**
 * Purpose: run applicable detectors concurrently under a global timeout,
 * then merge results using MergePolicy.
 *
 * Concurrency model:
 * - Parent scope is caller-provided; children are supervisor jobs so one failure doesn’t nuke others.
 * - Respect IDE dumb mode; either skip heavy detectors or wait for smart mode based on detector impls.
 */
class ContextOrchestrator(
    private val registry: DetectorRegistry,
    private val logger: Logger
) {
    suspend fun gather(request: ContextRequest, scope: CoroutineScope): Pair<ContextBundle, OrchestratorMetrics> {
        val start = System.currentTimeMillis()
        val detectorTimes = mutableMapOf<String, Long>()
        val errors = mutableMapOf<String, String>()

        // Run everything under a hard deadline.
        val signals = withTimeoutOrNull(request.maxMillis) {
            supervisorScope {
                registry.all()
                    .filter { runCatching { it.isApplicable(request) }.getOrDefault(false) }
                    .map { detector ->
                        async {
                            val t0 = System.currentTimeMillis()
                            try {
                                // Detectors should internally check DumbService if needed.
                                val out = detector.detect(request.project, request)
                                detectorTimes[detector.name] = System.currentTimeMillis() - t0
                                out
                            } catch (pce: ProcessCanceledException) {
                                detectorTimes[detector.name] = System.currentTimeMillis() - t0
                                emptyList()
                            } catch (e: Throwable) {
                                detectorTimes[detector.name] = System.currentTimeMillis() - t0
                                errors[detector.name] = e.message ?: e::class.simpleName.orEmpty()
                                logger.warn("Detector ${detector.name} failed", e)
                                emptyList()
                            }
                        }
                    }
                    .awaitAll()
                    .flatten()
            }
        } ?: emptyList()

        val bundle = MergePolicy.merge(signals)
        // Pull counters from the merge result via a tiny helper.
        // We don't have direct access here, so add a small API on MergePolicy to expose them.

        val metrics = OrchestratorMetrics(
            totalMillis = System.currentTimeMillis() - start,
            detectorMillis = detectorTimes.toMap(),
            errors = errors.toMap(),
            filesRawAttached = MergePolicy.lastFilesRawAttached,
            filesSummarizedAttached = MergePolicy.lastFilesSummarizedAttached,
            staleSynopsesUsed = MergePolicy.lastStaleSynopsesUsed
        )
        return bundle to metrics
    }
}
