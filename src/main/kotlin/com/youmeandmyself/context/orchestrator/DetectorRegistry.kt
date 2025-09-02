// (project-root)/src/main/kotlin/com/youmeandmyself/context/orchestrator/DetectorRegistry.kt
package com.youmeandmyself.context.orchestrator

/**
 * Purpose: provides the detectors the orchestrator will run.
 * In M2 we can hardcode; in M3 we can load via EP or DI.
 */
class DetectorRegistry(
    private val detectors: List<Detector>
) {
    fun all(): List<Detector> = detectors
}
