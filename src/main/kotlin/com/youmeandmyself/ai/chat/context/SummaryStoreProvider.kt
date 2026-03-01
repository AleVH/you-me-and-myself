package com.youmeandmyself.ai.chat.context

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
    val isShared: Boolean = false
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