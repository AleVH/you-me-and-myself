// File: src/main/kotlin/com/youmeandmyself/summary/cache/SummaryCache.kt
package com.youmeandmyself.summary.cache

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.youmeandmyself.dev.Dev
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.min

/**
 * Project-level in-memory cache for code summaries.
 *
 * ## Purpose
 *
 * Pure cache layer — no AI calls, no config checks, no pipeline logic.
 * Provides nanosecond lookups for summaries during context assembly,
 * and manages the summary lifecycle state machine to prevent duplicate
 * AI calls via single-flight claims.
 *
 * ## Three-Layer Storage Model
 *
 * This is the top (fastest) layer:
 * 1. **SummaryCache (this)** — ConcurrentHashMap, nanosecond reads
 * 2. **SQLite** — persistent index, survives IDE restart
 * 3. **JSONL** — source of truth, portable, never auto-deleted
 *
 * Read path: cache → SQLite miss → no summary exists
 * Write path: SummarizationService persists to JSONL + SQLite → updates this cache
 * Startup: warm from SQLite on first access
 *
 * ## State Machine
 *
 * Each entry tracks a [SummaryState]:
 * - MISSING → no summary, any caller may claim
 * - GENERATING → claim held, AI call in progress, other callers wait
 * - READY → summary available
 * - INVALIDATED → summary exists but code changed, stale synopsis still returned
 *
 * See [SummaryState] for the full transition diagram.
 *
 * ## Single-Flight Claims
 *
 * When a caller wants to generate a summary:
 * 1. [tryClaim] atomically sets state to GENERATING if eligible
 * 2. Other callers calling [awaitResult] get a CompletableFuture that resolves
 *    when the generating caller calls [completeClaim] or [failClaim]
 * 3. TTL on claims prevents permanent locks from crashes
 *
 * ## Thread Safety
 *
 * All state is in ConcurrentHashMaps. All public methods are safe from any thread.
 * State transitions use [ConcurrentHashMap.compute] for atomicity.
 *
 * @param project The IntelliJ project this cache belongs to
 */
@Service(Service.Level.PROJECT)
class SummaryCache(private val project: Project) {

    private val log = Logger.getInstance(SummaryCache::class.java)

    /**
     * A cached entry for a file's summary data.
     *
     * @param state Current lifecycle state (see [SummaryState])
     * @param claimedAt When the GENERATING claim was made (for TTL expiry)
     * @param dirtyDuringGeneration If true, the file changed while generation was
     *        in progress. The result will be immediately marked INVALIDATED on completion.
     */
    data class Entry(
        val path: String,
        /** Identifies a sub-file element (class or method). Null = file-level entry. */
        val elementSignature: String? = null,
        val languageId: String?,
        val state: SummaryState = SummaryState.MISSING,
        val summaryVersion: Int = 1,
        val contentHashAtSummary: String? = null,
        val lastSummarizedAt: Instant? = null,
        val headerSample: String? = null,
        val modelSynopsis: String? = null,
        val claimedAt: Instant? = null,
        val dirtyDuringGeneration: Boolean = false
    )

    /** In-memory cache of summaries, keyed by file path. */
    private val entries = ConcurrentHashMap<String, Entry>()

    /**
     * Futures for in-flight generation — keyed by file path.
     * Created when a claim is made, completed when generation finishes or fails.
     * Secondary callers await these instead of duplicating the AI call.
     */
    private val inflightFutures = ConcurrentHashMap<String, CompletableFuture<String?>>()

    /**
     * Default TTL for GENERATING claims (seconds).
     * If generation hasn't completed within this time, the claim expires
     * and state resets to MISSING. Generous default for large files on slow providers.
     */
    @Volatile
    var claimTtlSeconds: Long = 120L

    // ==================== Read API ====================

    /**
     * Get a cached synopsis for a file.
     *
     * Returns the cached synopsis and staleness flag. Does NOT trigger generation.
     * If the cache doesn't have it, returns (null, false) — caller should
     * check SQLite next via LocalSummaryStoreProvider.
     *
     * State-aware behavior:
     * - READY: returns synopsis, stale=false (unless hash mismatch)
     * - INVALIDATED: returns synopsis, stale=true
     * - GENERATING: returns null (summary not yet available)
     * - MISSING: returns null
     *
     * @param path Absolute file path
     * @param currentContentHash Current hash of file content (for staleness check). Null = skip staleness check.
     * @return Pair of (synopsis text or null, is stale flag)
     */
    fun getCachedSynopsis(path: String, currentContentHash: String?): Pair<String?, Boolean> {
        val current = entries[path] ?: return null to false

        return when (current.state) {
            SummaryState.MISSING -> null to false

            SummaryState.GENERATING -> null to false

            SummaryState.READY -> {
                val isStale = currentContentHash != null &&
                        current.contentHashAtSummary != null &&
                        current.contentHashAtSummary != currentContentHash
                current.modelSynopsis to isStale
            }

            SummaryState.INVALIDATED -> {
                // Stale summary — still return it, but flag as stale
                current.modelSynopsis to true
            }
        }
    }

    /**
     * Get the current state for a file's summary entry.
     * Returns MISSING if no entry exists.
     *
     * Performs lazy TTL check: if GENERATING and claim has expired,
     * resets to MISSING and unblocks waiters.
     */
    fun getState(path: String): SummaryState {
        val entry = entries[path] ?: return SummaryState.MISSING

        // Lazy TTL check: if GENERATING and claim has expired, reset to MISSING
        if (entry.state == SummaryState.GENERATING && isClaimExpired(entry)) {
            expireClaim(path)
            return SummaryState.MISSING
        }

        return entry.state
    }

    /**
     * Check if we have any cached data (synopsis or header sample) for a file.
     */
    fun hasEntry(path: String): Boolean = entries.containsKey(path)

    // ==================== Header Samples ====================

    /**
     * Ensure a header sample exists for a file.
     *
     * Header samples are the first N characters of a file — a cheap, instant
     * fallback when we don't have an AI summary. Computed once and cached.
     * No config check needed — header samples are free (no API call).
     *
     * @param path Absolute file path
     * @param languageId Programming language identifier
     * @param maxChars Maximum characters to sample
     * @return Pair of (header sample or null, character count)
     */
    fun ensureHeaderSample(
        path: String,
        languageId: String?,
        maxChars: Int
    ): Pair<String?, Int> {
        val current = entries[path]
        if (current?.headerSample != null) {
            return current.headerSample to current.headerSample.length
        }

        val sample = computeHeaderSample(path, maxChars)
        val updated = (current ?: Entry(path = path, languageId = languageId)).copy(
            headerSample = sample
        )
        entries[path] = updated

        return sample to (sample?.length ?: 0)
    }

    // ==================== Single-Flight Claims ====================

    /**
     * Attempt to claim a file for summary generation.
     *
     * Atomically transitions the entry to GENERATING if the file is eligible
     * (state is MISSING, INVALIDATED, or GENERATING with expired TTL).
     *
     * Creates a CompletableFuture that secondary callers can await via [awaitResult].
     *
     * @param path Absolute file path
     * @param languageId Programming language (preserved in entry)
     * @return true if claim was acquired, false if already GENERATING (active) or READY
     */
    fun tryClaim(path: String, languageId: String?): Boolean {
        var claimed = false

        entries.compute(path) { _, curr ->
            val now = Instant.now()

            when {
                // No entry — create one in GENERATING state
                curr == null -> {
                    claimed = true
                    Entry(
                        path = path,
                        languageId = languageId,
                        state = SummaryState.GENERATING,
                        claimedAt = now
                    )
                }

                // MISSING or INVALIDATED — eligible for claim
                curr.state == SummaryState.MISSING || curr.state == SummaryState.INVALIDATED -> {
                    claimed = true
                    curr.copy(
                        state = SummaryState.GENERATING,
                        claimedAt = now,
                        dirtyDuringGeneration = false
                    )
                }

                // GENERATING but claim has expired — take over
                curr.state == SummaryState.GENERATING && isClaimExpired(curr) -> {
                    // Fail the old future so any waiters unblock
                    inflightFutures.remove(path)?.complete(null)

                    claimed = true
                    curr.copy(
                        claimedAt = now,
                        dirtyDuringGeneration = false
                    )
                }

                // GENERATING (active) or READY — cannot claim
                else -> {
                    claimed = false
                    curr
                }
            }
        }

        if (claimed) {
            // Create the future that waiters will subscribe to
            inflightFutures[path] = CompletableFuture()

            Dev.info(log, "cache.claimed",
                "path" to path,
                "ttlSeconds" to claimTtlSeconds
            )
        }

        return claimed
    }

    /**
     * Wait for an in-flight generation to complete.
     *
     * Called by secondary callers that find the state is GENERATING.
     * Returns the synopsis when the generating caller completes, or null on timeout.
     *
     * @param path Absolute file path
     * @param timeoutMs Maximum time to wait in milliseconds
     * @return The generated synopsis, or null if timed out or generation failed
     */
    fun awaitResult(path: String, timeoutMs: Long): String? {
        val future = inflightFutures[path] ?: return null

        return try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: Throwable) {
            // Timeout or interruption — caller can decide to retry later
            null
        }
    }

    /**
     * Complete a successful generation claim.
     *
     * Transitions state to READY (or INVALIDATED if the file changed during generation),
     * updates the synopsis, and broadcasts the result to any waiters.
     *
     * Replaces the old [updateSynopsis] for pipeline-generated summaries.
     *
     * @param path Absolute file path
     * @param languageId Programming language
     * @param synopsis The AI-generated summary text
     * @param contentHash The content hash at the time of summarization
     */
    fun completeClaim(
        path: String,
        languageId: String?,
        synopsis: String,
        contentHash: String?
    ) {
        val now = Instant.now()
        var wasDirty = false

        entries.compute(path) { _, curr ->
            if (curr == null) {
                // Entry disappeared during generation (file deleted?) — create a READY entry anyway
                Entry(
                    path = path,
                    languageId = languageId,
                    state = SummaryState.READY,
                    modelSynopsis = synopsis,
                    contentHashAtSummary = contentHash,
                    lastSummarizedAt = now
                )
            } else {
                wasDirty = curr.dirtyDuringGeneration
                curr.copy(
                    // If file changed during generation, mark as INVALIDATED immediately
                    state = if (wasDirty) SummaryState.INVALIDATED else SummaryState.READY,
                    modelSynopsis = synopsis,
                    contentHashAtSummary = contentHash,
                    lastSummarizedAt = now,
                    claimedAt = null,
                    dirtyDuringGeneration = false
                )
            }
        }

        // Broadcast result to waiters
        inflightFutures.remove(path)?.complete(synopsis)

        Dev.info(log, "cache.claim_completed",
            "path" to path,
            "synopsisLength" to synopsis.length,
            "wasDirty" to wasDirty,
            "resultState" to if (wasDirty) "INVALIDATED" else "READY"
        )
    }

    /**
     * Fail a generation claim (error or timeout).
     *
     * Resets state to MISSING (or INVALIDATED if there was a previous synopsis),
     * and broadcasts null to any waiters so they unblock.
     *
     * @param path Absolute file path
     */
    fun failClaim(path: String) {
        entries.compute(path) { _, curr ->
            if (curr == null) return@compute null

            curr.copy(
                // If we had a synopsis before, go to INVALIDATED so the stale one is still usable.
                // If no previous synopsis, go to MISSING.
                state = if (curr.modelSynopsis != null) SummaryState.INVALIDATED else SummaryState.MISSING,
                claimedAt = null,
                dirtyDuringGeneration = false
            )
        }

        // Unblock waiters with null (generation failed)
        inflightFutures.remove(path)?.complete(null)

        Dev.info(log, "cache.claim_failed", "path" to path)
    }

    // ==================== Write API ====================

    /**
     * Update the cache with a newly generated summary.
     *
     * Called by non-pipeline paths (e.g., storage warm-up corrections).
     * For pipeline-generated summaries, use [completeClaim] instead.
     *
     * @param path Absolute file path
     * @param languageId Programming language
     * @param synopsis The AI-generated summary text
     * @param contentHash The content hash at the time of summarization
     */
    fun updateSynopsis(
        path: String,
        languageId: String?,
        synopsis: String,
        contentHash: String?
    ) {
        val now = Instant.now()
        entries.compute(path) { _, curr ->
            val base = curr ?: Entry(path = path, languageId = languageId)
            base.copy(
                state = SummaryState.READY,
                modelSynopsis = synopsis,
                contentHashAtSummary = contentHash,
                lastSummarizedAt = now,
                claimedAt = null,
                dirtyDuringGeneration = false
            )
        }

        Dev.info(log, "cache.updated",
            "path" to path,
            "synopsisLength" to synopsis.length
        )
    }

    /**
     * Populate the cache from a SQLite query result.
     *
     * Called during warm-up (startup) or on SQLite hit after cache miss.
     * Does NOT overwrite existing entries — if the cache already has a fresher
     * version (e.g., just generated), keep it.
     *
     * @param path Absolute file path
     * @param languageId Programming language
     * @param synopsis The summary text from SQLite
     * @param contentHash The content hash stored with the summary
     * @param summarizedAt When the summary was generated
     */
    fun populateFromStorage(
        path: String,
        languageId: String?,
        synopsis: String,
        contentHash: String?,
        summarizedAt: Instant?
    ) {
        entries.putIfAbsent(path, Entry(
            path = path,
            languageId = languageId,
            state = SummaryState.READY,
            modelSynopsis = synopsis,
            contentHashAtSummary = contentHash,
            lastSummarizedAt = summarizedAt
        ))
    }

    // ==================== Staleness ====================

    /**
     * Update staleness tracking when a file's content changes.
     *
     * Called by VfsSummaryWatcher when it detects file modifications.
     *
     * State transitions:
     * - READY → INVALIDATED (code changed, summary is stale)
     * - GENERATING → stays GENERATING but sets dirtyDuringGeneration=true
     *   (don't interrupt in-progress generation; result will be marked INVALIDATED on completion)
     * - MISSING → no-op (nothing to invalidate)
     * - INVALIDATED → no-op (already stale)
     *
     * The summary isn't deleted — stale summaries are still useful.
     *
     * @param path Absolute file path
     * @param newHash The new content hash from VfsSummaryWatcher
     */
    fun onHashChange(path: String, newHash: String) {
        entries.compute(path) { _, curr ->
            if (curr == null) return@compute null

            // If the hash matches, file hasn't actually changed (e.g., save without modification)
            if (curr.contentHashAtSummary == newHash) return@compute curr

            when (curr.state) {
                SummaryState.READY -> {
                    Dev.info(log, "cache.stale",
                        "path" to path,
                        "oldHash" to (curr.contentHashAtSummary?.take(8) ?: "NONE"),
                        "newHash" to newHash.take(8)
                    )
                    curr.copy(state = SummaryState.INVALIDATED)
                }

                SummaryState.GENERATING -> {
                    // Don't interrupt generation — mark dirty so completeClaim
                    // will set the result to INVALIDATED instead of READY
                    Dev.info(log, "cache.dirty_during_generation",
                        "path" to path,
                        "newHash" to newHash.take(8)
                    )
                    curr.copy(dirtyDuringGeneration = true)
                }

                // MISSING or INVALIDATED — nothing to do
                else -> curr
            }
        }
    }

    // ==================== Cleanup ====================

    /**
     * Remove all cached data for a deleted file.
     * Also fails any in-flight claim so waiters unblock.
     */
    fun onFileDeleted(path: String) {
        entries.remove(path)
        inflightFutures.remove(path)?.complete(null)
        Dev.info(log, "cache.removed", "path" to path)
    }

    /**
     * Pre-compute header samples for multiple files.
     */
    fun warmUp(paths: List<String>, languageId: String?, maxChars: Int) {
        paths.forEach { ensureHeaderSample(it, languageId, maxChars) }
    }

    /**
     * Clear the entire cache. Used for testing or full reset.
     * Also fails all in-flight claims.
     */
    fun clear() {
        val size = entries.size
        entries.clear()

        // Unblock all waiters
        inflightFutures.values.forEach { it.complete(null) }
        inflightFutures.clear()

        Dev.info(log, "cache.cleared", "entriesRemoved" to size)
    }

    // ==================== TTL Helpers ====================

    /**
     * Check if a GENERATING claim has exceeded its TTL.
     */
    private fun isClaimExpired(entry: Entry): Boolean {
        val claimed = entry.claimedAt ?: return true // No timestamp = treat as expired
        return Duration.between(claimed, Instant.now()).seconds >= claimTtlSeconds
    }

    /**
     * Expire a stale GENERATING claim — reset to MISSING/INVALIDATED and unblock waiters.
     * Called lazily from [getState] when a TTL violation is detected.
     */
    private fun expireClaim(path: String) {
        entries.compute(path) { _, curr ->
            if (curr == null || curr.state != SummaryState.GENERATING) return@compute curr

            Dev.info(log, "cache.claim_expired",
                "path" to path,
                "claimedAt" to curr.claimedAt.toString()
            )

            curr.copy(
                state = if (curr.modelSynopsis != null) SummaryState.INVALIDATED else SummaryState.MISSING,
                claimedAt = null,
                dirtyDuringGeneration = false
            )
        }

        inflightFutures.remove(path)?.complete(null)
    }

    // ==================== File Helpers ====================

    /**
     * Read the first N characters of a file.
     */
    private fun computeHeaderSample(path: String, maxChars: Int): String? {
        return try {
            val p = Path.of(path)
            if (!Files.isRegularFile(p)) return null
            val raw = Files.readString(p)
            raw.substring(0, min(raw.length, maxChars))
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Count lines in a file (for scope evaluation).
     * Returns 0 if file can't be read.
     */
    fun countLines(path: String): Int {
        return try {
            Files.lines(Path.of(path)).use { it.count().toInt() }
        } catch (_: Throwable) {
            0
        }
    }

    // ==================== Element-Level Cache API ====================
    //
    // These methods support sub-file granularity (individual methods, classes).
    // Keys use the format "path::elementSignature" to distinguish from
    // file-level entries which use just the path.

    /**
     * Build the map key for an element-level entry.
     * Uses "::" as separator — safe because it doesn't appear in file paths or FQNs.
     */
    private fun elementKey(path: String, elementSignature: String): String = "$path::$elementSignature"

    /**
     * Get cached synopsis for a specific element within a file.
     *
     * Checks state — returns null if MISSING or GENERATING.
     * Returns (synopsis, isStale) where isStale is true if INVALIDATED
     * or if the hash has changed.
     *
     * @param path Absolute file path
     * @param elementSignature The element's unique signature (e.g., "com.foo.Bar#doThing(String,Int)")
     * @param currentElementHash Current semantic hash of the element (for staleness check). Null = skip.
     * @return Pair of (synopsis text or null, is stale flag)
     */
    fun getCachedElementSynopsis(
        path: String,
        elementSignature: String,
        currentElementHash: String?
    ): Pair<String?, Boolean> {
        val key = elementKey(path, elementSignature)
        val current = entries[key] ?: return null to false

        return when (current.state) {
            SummaryState.MISSING -> null to false
            SummaryState.GENERATING -> null to false
            SummaryState.READY -> {
                val isStale = currentElementHash != null &&
                        current.contentHashAtSummary != null &&
                        current.contentHashAtSummary != currentElementHash
                current.modelSynopsis to isStale
            }
            SummaryState.INVALIDATED -> {
                // Stale summary — still return it, but flag as stale
                current.modelSynopsis to true
            }
        }
    }

    /**
     * Claim a specific element for generation (single-flight).
     *
     * Same semantics as [tryClaim] but for element-level entries.
     *
     * @return true if claim was acquired
     */
    fun tryClaimElement(
        path: String,
        elementSignature: String,
        languageId: String?
    ): Boolean {
        val key = elementKey(path, elementSignature)
        var claimed = false

        entries.compute(key) { _, curr ->
            val now = Instant.now()
            when {
                curr == null -> {
                    claimed = true
                    Entry(
                        path = path,
                        elementSignature = elementSignature,
                        languageId = languageId,
                        state = SummaryState.GENERATING,
                        claimedAt = now
                    )
                }
                curr.state == SummaryState.MISSING || curr.state == SummaryState.INVALIDATED -> {
                    claimed = true
                    curr.copy(
                        state = SummaryState.GENERATING,
                        claimedAt = now,
                        dirtyDuringGeneration = false
                    )
                }
                curr.state == SummaryState.GENERATING && isClaimExpired(curr) -> {
                    inflightFutures.remove(key)?.complete(null)
                    claimed = true
                    curr.copy(claimedAt = now, dirtyDuringGeneration = false)
                }
                else -> curr
            }
        }

        if (claimed) {
            inflightFutures[key] = CompletableFuture()
            Dev.info(log, "cache.element.claimed",
                "path" to path,
                "element" to elementSignature
            )
        }

        return claimed
    }

    /**
     * Complete a claim for a specific element.
     *
     * Transitions state to READY (or INVALIDATED if dirty during generation).
     * Broadcasts the result to waiters.
     *
     * @return true if the claim was completed successfully
     */
    fun completeElementClaim(
        path: String,
        elementSignature: String,
        languageId: String?,
        synopsis: String,
        contentHash: String?
    ): Boolean {
        val key = elementKey(path, elementSignature)
        var completed = false

        entries.compute(key) { _, curr ->
            if (curr == null || curr.state != SummaryState.GENERATING) return@compute curr

            completed = true
            val finalState = if (curr.dirtyDuringGeneration) SummaryState.INVALIDATED else SummaryState.READY

            Dev.info(log, "cache.element.completed",
                "path" to path,
                "element" to elementSignature,
                "state" to finalState.name,
                "synopsisLength" to synopsis.length,
                "wasDirty" to curr.dirtyDuringGeneration
            )

            curr.copy(
                state = finalState,
                modelSynopsis = synopsis,
                contentHashAtSummary = contentHash,
                lastSummarizedAt = Instant.now(),
                claimedAt = null,
                dirtyDuringGeneration = false
            )
        }

        // Broadcast to any waiters
        inflightFutures.remove(key)?.complete(synopsis)
        return completed
    }

    /**
     * Invalidate specific elements within a file based on hash comparison.
     *
     * Called by the watcher when a file changes. For each element, compares
     * the new hash against the stored hash. Only entries whose hash actually
     * changed are invalidated.
     *
     * Elements not in [elementHashes] are left untouched.
     *
     * @param path Absolute file path
     * @param elementHashes Map of elementSignature → newSemanticHash
     */
    fun onElementHashChanges(
        path: String,
        elementHashes: Map<String, String>
    ) {
        for ((signature, newHash) in elementHashes) {
            val key = elementKey(path, signature)
            entries.compute(key) { _, curr ->
                if (curr == null) return@compute null

                // Hash matches — element hasn't changed semantically
                if (curr.contentHashAtSummary == newHash) return@compute curr

                when (curr.state) {
                    SummaryState.READY -> {
                        Dev.info(log, "cache.element.stale",
                            "path" to path,
                            "element" to signature,
                            "oldHash" to (curr.contentHashAtSummary?.take(8) ?: "NONE"),
                            "newHash" to newHash.take(8)
                        )
                        curr.copy(state = SummaryState.INVALIDATED)
                    }
                    SummaryState.GENERATING -> {
                        Dev.info(log, "cache.element.dirty_during_generation",
                            "path" to path,
                            "element" to signature
                        )
                        curr.copy(dirtyDuringGeneration = true)
                    }
                    else -> curr
                }
            }
        }
    }

    /**
     * Get all element entries for a file, optionally filtered by state.
     *
     * Scans all entries whose key starts with the file path followed by "::".
     * Used by the pipeline to check what's valid before cascading.
     *
     * @param path Absolute file path
     * @param stateFilter If provided, only return entries in these states. Null = all entries.
     * @return Map of elementSignature → Entry
     */
    fun getElementEntries(
        path: String,
        stateFilter: Set<SummaryState>? = null
    ): Map<String, Entry> {
        val prefix = "$path::"
        val result = mutableMapOf<String, Entry>()

        for ((key, entry) in entries) {
            if (key.startsWith(prefix)) {
                if (stateFilter == null || entry.state in stateFilter) {
                    val signature = key.removePrefix(prefix)
                    result[signature] = entry
                }
            }
        }

        return result
    }

    companion object {
        fun getInstance(project: Project): SummaryCache =
            project.getService(SummaryCache::class.java)
    }

    /** Whether we've warmed from storage yet. */
    @Volatile
    private var warmedFromStorage = false

    /**
     * Check if the cache has been warmed from storage.
     *
     * Used by LocalSummaryStoreProvider.ensureWarmed() to skip the
     * warm-up query if it's already been done this session.
     */
    fun isWarmed(): Boolean = warmedFromStorage

    /**
     * Warm the cache from external storage results.
     *
     * Called once on first access. The caller (e.g., LocalSummaryStoreProvider
     * or an initializer) provides the data — SummaryCache doesn't know
     * where it came from.
     *
     * All warmed entries arrive as READY — they have valid summaries from storage.
     *
     * @param summaries Map of filePath → Entry data from storage
     */
    fun warmFromStorage(summaries: Map<String, Entry>) {
        if (warmedFromStorage) return
        synchronized(this) {
            if (warmedFromStorage) return
            for ((path, entry) in summaries) {
                // Ensure warmed entries have READY state
                val readyEntry = if (entry.state != SummaryState.READY) {
                    entry.copy(state = SummaryState.READY)
                } else {
                    entry
                }
                entries.putIfAbsent(path, readyEntry)
            }
            warmedFromStorage = true
            Dev.info(log, "cache.warmed", "entries" to summaries.size)
        }
    }
}