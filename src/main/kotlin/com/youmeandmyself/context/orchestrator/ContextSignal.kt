// (project-root)/src/main/kotlin/com/youmeandmyself/context/orchestrator/ContextSignal.kt
package com.youmeandmyself.context.orchestrator

/**
 * Purpose: normalized signals detectors emit (extensible).
 * Each carries a confidence and provenance for auditing/merging.
 */
sealed interface ContextSignal {
    val confidence: Confidence
    val source: String // detector simpleName

    data class Language(
        val languageId: String,
        override val confidence: Confidence,
        override val source: String
    ) : ContextSignal

    data class Framework(
        val name: String,
        val version: String?,
        override val confidence: Confidence,
        override val source: String
    ) : ContextSignal

    data class ProjectStructure(
        val modules: List<String>,
        val buildSystem: String?, // e.g., Gradle, Maven, npm, pnpm
        override val confidence: Confidence,
        override val source: String
    ) : ContextSignal

    /**
     * Files the detectors deem relevant. Each candidate carries a path plus a reason and a score
     * so MergePolicy can rank deterministically.
     *
     * - score: higher = more relevant (detector-defined scale).
     * - estChars: best-effort char count to inform caps/truncation (null = unknown).
     */
    data class RelevantFiles(
        val candidates: List<RelevantCandidate>,
        override val confidence: Confidence,
        override val source: String
    ) : ContextSignal

    data class RelevantCandidate(
        val path: String,
        val reason: String,
        val score: Int,
        val estChars: Int? = null
    )
}
