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
    // NEW: materialized/capped files for prompt building and transcript transparency
    val files: List<ContextFile> = emptyList(),
    val totalChars: Int = 0,
    val truncatedCount: Int = 0,
    val rawSignals: List<ContextSignal> = emptyList()
)

/**
 * FINAL item that will be sent downstream (prompt builder).
 * kind: RAW for now; SUMMARY reserved for M4 (indexer/summaries).
 * truncated: true if estChars > maxCharsPerFile (slicing happens in PromptBuilder).
 */
data class ContextFile(
    val path: String,
    val languageId: String? = null,
    val kind: ContextKind = ContextKind.RAW,
    val reason: String,
    val charCount: Int = 0,
    val truncated: Boolean = false
)

enum class ContextKind { RAW, SUMMARY }