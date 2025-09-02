// (project-root)/src/main/kotlin/com/youmeandmyself/context/orchestrator/ContextBundle.kt
package com.youmeandmyself.context.orchestrator

/**
 * Purpose: merged outcome the rest of the plugin consumes.
 * Notes:
 * - Keep raw signals for traceability.
 */
data class ContextBundle(
    val language: ContextSignal.Language? = null,
    val frameworks: List<ContextSignal.Framework> = emptyList(),
    val projectStructure: ContextSignal.ProjectStructure? = null,
    val relevantFiles: ContextSignal.RelevantFiles? = null,
    val rawSignals: List<ContextSignal> = emptyList()
)
