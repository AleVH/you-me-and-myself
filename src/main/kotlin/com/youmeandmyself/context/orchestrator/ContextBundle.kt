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
//data class ContextFile(
//    val path: String,
//    val languageId: String? = null,
//    val kind: ContextKind = ContextKind.RAW,
//    val reason: String,
//    val charCount: Int = 0,
//    val truncated: Boolean = false
//)

// Purpose: carries either RAW (snippet of real content) or SUMMARY (header + synopsis).
data class ContextFile(
    val path: String,
    val languageId: String? = null,
    val kind: ContextKind = ContextKind.RAW, // RAW | SUMMARY
    val reason: String,                      // why included (resolver reason, etc.)
    val charCount: Int = 0,                  // budgeted chars that we plan to attach
    val truncated: Boolean = false,          // RAW only: true if over per-file cap and sliced

    // --- Summary payload (only used when kind == SUMMARY) ---
    // Short raw slice from file head (cheap, computed locally; ~2k chars)
    val headerSample: String? = null,

    // Short semantic blurb (expensive; may be missing initially or stale)
    val modelSynopsis: String? = null,

    // True if the file hash changed since synopsis was generated (still usable, but warn)
    val isStale: Boolean = false,

    // Debug/telemetry: where this came from (e.g., "summary-cache")
    val source: String? = null
)
enum class ContextKind { RAW, SUMMARY }