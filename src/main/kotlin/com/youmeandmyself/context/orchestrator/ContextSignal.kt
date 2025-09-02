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

    data class RelevantFiles(
        val filePaths: List<String>,
        override val confidence: Confidence,
        override val source: String
    ) : ContextSignal
}
