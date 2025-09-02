// (project-root)/src/main/kotlin/com/youmeandmyself/context/orchestrator/OrchestratorMetrics.kt
package com.youmeandmyself.context.orchestrator

/**
 * Purpose: simple timing/error counters for dev visibility; wire to your existing logging later.
 */
data class OrchestratorMetrics(
    val totalMillis: Long,
    val detectorMillis: Map<String, Long>,
    val errors: Map<String, String>
)
