package com.youmeandmyself.ai.chat.context

import com.youmeandmyself.summary.model.CodeElement

/**
 * Interface for reading code summaries during context assembly.
 *
 * ## Why This Exists
 *
 * When the [ContextAssembler] builds a prompt for the AI, it needs to include
 * relevant code summaries alongside raw file content. The source of those
 * summaries depends on the user's tier:
 *
 * - **Individual tier**: Summaries come from the local storage only.
 *   The developer generated them, they stay on their machine.
 *
 * - **Company tier**: Summaries come from BOTH local AND shared storage.
 *   If another developer on the team already summarized a file, that summary
 *   is available to everyone — avoiding redundant AI calls.
 *
 * This interface abstracts the source so [ContextAssembler] doesn't need to
 * know which tier the user is on. It just asks for summaries and gets them.
 *
 * ## Claim Awareness (Company Tier)
 *
 * In the company tier, two developers might try to summarize the same file
 * simultaneously. The claim system prevents this waste:
 *
 * - Before generating a summary, check if someone else has "claimed" that file
 * - If claimed, wait for their result instead of making a duplicate AI call
 * - [ClaimStatus] communicates this state to the context assembler
 *
 * For the individual tier, claims are always [ClaimStatus.NOT_CLAIMED] — there's
 * no one else to coordinate with.
 *
 * ## Thread Safety
 *
 * Implementations must be thread-safe. Multiple context assemblies can run
 * concurrently (e.g., user sends messages in quick succession, or background
 * summarization triggers alongside a chat).
 */
interface SummaryStoreProvider {

    /**
     * Retrieve the best available summary for a file.
     *
     * "Best available" means:
     * 1. Check local storage first (developer's own summaries)
     * 2. If not found locally AND company tier is active, check shared storage
     * 3. Return null if no summary exists anywhere
     *
     * @param filePath The project-relative path to the source file (e.g., "src/main/kotlin/Foo.kt")
     * @param projectId The project these summaries belong to
     * @return The summary if available, null if the file hasn't been summarized yet
     */
    suspend fun getSummary(filePath: String, projectId: String): CodeSummary?

    /**
     * Retrieve summaries for multiple files in a batch.
     *
     * More efficient than calling [getSummary] in a loop because implementations
     * can optimize with batch queries. Returns only files that have summaries —
     * files without summaries are simply absent from the result map.
     *
     * @param filePaths Project-relative paths to look up
     * @param projectId The project these summaries belong to
     * @return Map of filePath → summary. Missing entries = no summary available.
     */
    suspend fun getSummaries(filePaths: List<String>, projectId: String): Map<String, CodeSummary>

    /**
     * Check the claim status of a file in the shared summary system.
     *
     * Only meaningful for company tier. Individual tier always returns [ClaimStatus.NOT_CLAIMED].
     *
     * Used by [ContextAssembler] to decide whether to:
     * - Include a stale/missing summary as-is (not claimed, no one generating)
     * - Wait briefly for an in-progress summary (claimed, being generated)
     * - Signal that a summary should be generated (not claimed, not generated)
     *
     * @param filePath The file to check
     * @param projectId The project context
     * @return The current claim status for this file
     */
    suspend fun getClaimStatus(filePath: String, projectId: String): ClaimStatus

    /**
     * Signal that a summary is missing and should be generated.
     *
     * This is a fire-and-forget hint to the summarization pipeline.
     * The pipeline decides whether and when to actually generate the summary
     * based on queue priority, budget limits, etc.
     *
     * In company tier, this may also create a claim to prevent duplicate work.
     *
     * @param filePath The file that needs a summary
     * @param projectId The project context
     */
    suspend fun suggestSummarization(filePath: String, projectId: String)

    /**
     * Retrieve the summary for a specific code element (method, class, property).
     *
     * Element-level summaries are more precise than file-level — they describe
     * what a single method or class does, not the entire file. When the user is
     * looking at a specific method, attaching the method summary (50-200 tokens)
     * is much cheaper and more relevant than the whole file summary (500-5000 tokens).
     *
     * ## Lookup
     *
     * The element is identified by its PSI signature (e.g.,
     * "MyService#processRefund(String, Int)"). This signature is stable across
     * renames of local variables, formatting changes, etc. — only structural
     * changes (parameter types, return type, visibility) affect it.
     *
     * ## Staleness
     *
     * The returned summary includes an [CodeSummary.isStale] flag. The caller
     * MUST check this at every level before using a cached summary. If stale,
     * the summary should be regenerated (Phase B.1) or skipped.
     *
     * @param filePath Absolute file path
     * @param elementSignature PSI element signature (e.g., "MyClass#doThing(String)")
     * @param projectId The project context
     * @return Element summary if available, null if not summarized yet
     */
    suspend fun getElementSummary(
        filePath: String,
        elementSignature: String,
        projectId: String,
        currentElementHash: String? = null
    ): CodeSummary?

    // ==================== Demand-Driven Synchronous Generation ====================
    //
    // These methods generate summaries synchronously and return the synopsis text.
    // Called by ContextAssembler when the current request needs a summary that
    // doesn't exist or is stale. The summary is generated NOW and attached to
    // THIS request — not fire-and-forget for the next one.
    //
    // See: Summarization — Agreed Direction.md

    /**
     * Generate a method summary synchronously and return the synopsis text.
     *
     * Called when ContextAssembler detects the user is asking about a method
     * that has no valid cached summary. Generates, caches (memory + SQLite),
     * and returns the text for immediate attachment.
     *
     * Returns null if generation is not possible (config, no provider, dumb mode, error).
     *
     * @param filePath Absolute file path containing the method
     * @param element The method element (from PSI detection)
     * @param parentClassName The containing class name
     * @return Synopsis text, or null
     */
    suspend fun generateMethodSummaryNow(
        filePath: String,
        element: CodeElement,
        parentClassName: String
    ): String?

    /**
     * Generate a class summary synchronously via bottom-up cascade.
     *
     * Triggers the full cascade: detect methods → validate/generate each →
     * build class summary from method summaries. Only generates what's missing.
     *
     * Returns null if generation is not possible.
     *
     * @param filePath Absolute file path containing the class
     * @param element The class element (from PSI detection)
     * @return Class synopsis text, or null
     */
    suspend fun generateClassSummaryNow(
        filePath: String,
        element: CodeElement
    ): String?
}

/**
 * A code summary retrieved from storage.
 *
 * Contains both the AI-generated synopsis and metadata about when/how
 * it was generated. The staleness flag is critical for the context assembler:
 * stale summaries are still useful (better than nothing) but the AI should
 * be informed that the summary may not reflect the current code.
 *
 * @property filePath Project-relative path to the source file
 * @property synopsis The AI-generated summary text
 * @property headerSample First ~20 lines of the file at the time of summarization.
 *                        Helps the AI understand the file structure even if the synopsis is stale.
 * @property isStale True if the source file has been modified since this summary was generated.
 *                   The context assembler annotates stale summaries so the AI knows they may be outdated.
 * @property generatedAt When this summary was created (ISO timestamp)
 * @property providerId Which AI provider generated this summary
 * @property modelId Which model generated this summary
 * @property isShared True if this summary came from the shared/company store (not generated locally).
 *                    Useful for UI display ("shared summary" badge) and analytics.
 */
data class CodeSummary(
    val filePath: String,
    val synopsis: String,
    val headerSample: String?,
    val isStale: Boolean,
    val generatedAt: String,
    val providerId: String,
    val modelId: String,
    val isShared: Boolean = false,
    /**
     * The semantic hash of the code element at the time this summary was generated.
     * Used to validate freshness: if the current code hash differs from this,
     * the summary is stale and must be regenerated.
     *
     * Null for legacy summaries that were stored without a hash (pre-fix).
     * Null hashes are treated as stale on retrieval — the safe default.
     */
    val contentHashAtGen: String? = null
)

/**
 * Claim status for a file in the shared summary system.
 *
 * ## What Are Claims?
 *
 * In the company tier, when developer A starts generating a summary for Foo.kt,
 * they "claim" it. If developer B asks for a summary of Foo.kt while A is still
 * generating, B should wait for A's result instead of making a duplicate AI call.
 *
 * Claims have a TTL (time-to-live) to handle cases where the claiming developer's
 * IDE crashes or disconnects.
 *
 * ## Individual Tier
 *
 * Individual tier always returns [NOT_CLAIMED] — there's no shared state to coordinate.
 */
enum class ClaimStatus {
    /** No one is generating a summary for this file. Safe to generate or skip. */
    NOT_CLAIMED,

    /** Another developer is currently generating a summary. Consider waiting. */
    CLAIMED_BY_OTHER,

    /** This developer already claimed this file (e.g., from a previous context assembly). */
    CLAIMED_BY_SELF,

    /** A claim existed but expired (claimer likely crashed). Safe to re-claim. */
    CLAIM_EXPIRED
}