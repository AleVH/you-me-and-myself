package com.youmeandmyself.ai.chat.context

import java.time.Instant

/**
 * Structured representation of the context block in a request.
 *
 * ## Purpose
 *
 * The context block holds all IDE-provided information that gets attached
 * to a request — file summaries, raw code, framework info, project structure.
 * It is one of the four independent blocks in [RequestBlocks], managed
 * separately from profile, history, and user message.
 *
 * ## Structure
 *
 * Entries are partitioned by kind:
 * - [summaries]: compressed representations of code files (produced by the summary pipeline)
 * - [raw]: full file contents included as-is (when summaries don't exist or aren't beneficial)
 * - [other]: non-file context — framework info, project structure, build system details
 *
 * ## Lifecycle
 *
 * - **Phase 1:** Populated synchronously by [ContextAssembler] at send time,
 *   wrapping the existing context gathering output.
 * - **Phase 2:** Populated by [ContextStagingService] from background gathering.
 *   Snapshotted at send time, cleared after send.
 * - **Phase 3:** Tracked for staleness via [SentContextManifest]. Entries flagged
 *   when their source files change after being sent.
 *
 * @see com.youmeandmyself.ai.chat.orchestrator.RequestBlocks
 */
data class ContextBlock(
    /** File summaries — compressed via the summarization pipeline. */
    val summaries: List<ContextEntry>,

    /** Raw file contents — full source code included without compression. */
    val raw: List<ContextEntry>,

    /** Non-file context — framework info, project structure, build system, etc. */
    val other: List<ContextEntry>
) {
    /** All entries across all categories, in order: summaries, raw, other. */
    val allEntries: List<ContextEntry> get() = summaries + raw + other

    /** True if there are no entries in any category. */
    val isEmpty: Boolean get() = summaries.isEmpty() && raw.isEmpty() && other.isEmpty()

    /** Total estimated token cost across all entries. */
    val totalTokenEstimate: Int get() = allEntries.sumOf { it.tokenEstimate }

    companion object {
        /** Create an empty context block (no context attached). */
        fun empty(): ContextBlock = ContextBlock(
            summaries = emptyList(),
            raw = emptyList(),
            other = emptyList()
        )
    }
}

/**
 * Serialize a [ContextBlock] into the prompt text format.
 *
 * ## Purpose
 *
 * Converts the structured context block into a text string that can be
 * prepended to the user message in the API request. The format matches
 * what [ContextAssembler] previously produced via its extension functions
 * ([ContextBundle.filesSection] and [formatContextNote]).
 *
 * ## Phase 1
 *
 * Used by [GenericLlmProvider.chat(RequestBlocks)] to serialize the context
 * block at send time. The output is identical to what the old pipeline produced.
 *
 * ## Phase 2
 *
 * Reused by the staging area when formatting the snapshot at send time.
 * Placed here (not as a private method on the provider) for shared access.
 *
 * @param contextBlock The structured context block to serialize
 * @return Formatted text string, or empty string if the block has no entries
 */
fun formatContextBlock(contextBlock: ContextBlock): String {
    if (contextBlock.isEmpty) return ""

    return buildString {
        // Other entries first (project-level context: language, frameworks, build system)
        for (entry in contextBlock.other) {
            appendLine(entry.content)
        }

        // File entries (summaries and raw)
        val fileEntries = contextBlock.summaries + contextBlock.raw
        if (fileEntries.isNotEmpty()) {
            appendLine()
            appendLine("### Files")
            for (entry in fileEntries) {
                appendLine()
                appendLine("```text")
                appendLine("// path: ${entry.path ?: "unknown"} | kind: ${entry.kind.name}")
                appendLine(entry.content)
                appendLine("```")
            }
        }
    }.trimEnd()
}

/**
 * A single entry in the context block.
 *
 * Represents one piece of context — a file summary, raw file content,
 * framework description, or other IDE-provided information — that gets
 * included in the request to the AI.
 *
 * ## Identity and Tracking
 *
 * Each entry has a unique [id] (UUID) for tracking across the staging area,
 * send pipeline, and sent context history. The [contentHash] enables the
 * early-exit optimization: if a file is already in the context block with
 * the same hash, skip gathering entirely.
 *
 * ## Staleness
 *
 * [isStale] is set by the staleness tracker (Phase 3) when the source file
 * changes after the entry was gathered. Stale entries are flagged in the
 * sidebar UI so the user can dismiss or refresh them.
 *
 * @property id Unique identifier for tracking across staging, send, and history
 * @property path File path (null for non-file entries like framework info)
 * @property name Display name (e.g., "UserService.kt", "Spring Boot 3.x")
 * @property content The actual text to inject into the request
 * @property kind Entry type: SUMMARY, RAW, or OTHER
 * @property contentHash Hash of [content] at gathering time (for staleness detection and dedup)
 * @property tokenEstimate Estimated token cost of this entry
 * @property source How this entry was produced: "auto" (heuristic-driven), "forced" (user explicit), "manual"
 * @property gatheredAt When this entry was gathered
 * @property isStale True if the source file changed since this entry was gathered
 * @property elementSignature PSI element signature for element-level entries (method, class)
 */
data class ContextEntry(
    val id: String,
    val path: String?,
    val name: String,
    val content: String,
    val kind: ContextKind,
    val contentHash: String?,
    val tokenEstimate: Int,
    val source: String,
    val gatheredAt: Instant,
    val isStale: Boolean = false,
    val elementSignature: String? = null
)

/**
 * Classification of a context entry.
 *
 * Determines which sub-list of [ContextBlock] the entry belongs to
 * and how it is rendered in the badge tray and sidebar.
 *
 * Note: A separate [ContextKind] enum exists in `context/orchestrator/ContextBundle.kt`
 * with RAW and SUMMARY values. That enum is used by the context orchestrator pipeline.
 * This enum is for the request blocks model. Phase 2 will unify them when the staging
 * area replaces the current assembly path.
 */
enum class ContextKind {
    /** Compressed representation produced by the summarization pipeline. */
    SUMMARY,

    /** Full file content included without compression. */
    RAW,

    /** Non-file context: framework info, project structure, build system, etc. */
    OTHER
}
