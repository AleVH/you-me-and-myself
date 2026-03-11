// File: src/main/kotlin/com/youmeandmyself/summary/cache/SummaryCache.kt
package com.youmeandmyself.summary.cache

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.youmeandmyself.dev.Dev
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

/**
 * Project-level in-memory cache for code summaries.
 *
 * ## Purpose
 *
 * Pure cache layer — no AI calls, no config checks, no pipeline logic.
 * Provides nanosecond lookups for summaries during context assembly.
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
 * ## Staleness
 *
 * A summary is "stale" when the file's content hash has changed since summarization.
 * Stale summaries are still returned (better than nothing) but marked as stale
 * so the prompt can annotate them as "may be outdated."
 *
 * ## Thread Safety
 *
 * All state is in a ConcurrentHashMap. All public methods are safe from any thread.
 *
 * @param project The IntelliJ project this cache belongs to
 */
@Service(Service.Level.PROJECT)
class SummaryCache(private val project: Project) {

    private val log = Logger.getInstance(SummaryCache::class.java)

    /**
     * A cached entry for a file's summary data.
     */
    data class Entry(
        val path: String,
        val languageId: String?,
        val summaryVersion: Int = 1,
        val contentHashAtSummary: String? = null,
        val lastSummarizedAt: Instant? = null,
        val headerSample: String? = null,
        val modelSynopsis: String? = null
    )

    /** In-memory cache of summaries, keyed by file path. */
    private val entries = ConcurrentHashMap<String, Entry>()

    // ==================== Read API ====================

    /**
     * Get a cached synopsis for a file.
     *
     * Returns the cached synopsis and staleness flag. Does NOT trigger generation.
     * If the cache doesn't have it, returns (null, false) — caller should
     * check SQLite next via LocalSummaryStoreProvider.
     *
     * @param path Absolute file path
     * @param currentContentHash Current hash of file content (for staleness check). Null = skip staleness check.
     * @return Pair of (synopsis text or null, is stale flag)
     */
    fun getCachedSynopsis(path: String, currentContentHash: String?): Pair<String?, Boolean> {
        val current = entries[path]

        val isStale = currentContentHash != null &&
                current?.contentHashAtSummary != null &&
                current.contentHashAtSummary != currentContentHash

        return current?.modelSynopsis to isStale
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
        val updated = (current ?: Entry(path, languageId)).copy(headerSample = sample)
        entries[path] = updated

        return sample to (sample?.length ?: 0)
    }

    // ==================== Write API ====================

    /**
     * Update the cache with a newly generated summary.
     *
     * Called by SummaryPipeline after SummarizationService successfully generates a summary.
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
                modelSynopsis = synopsis,
                contentHashAtSummary = contentHash,
                lastSummarizedAt = now
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
     * The summary isn't deleted — stale summaries are still useful.
     * Marks the entry as stale by clearing the contentHashAtSummary
     * so the next getCachedSynopsis() call with the new hash will
     * detect the mismatch.
     *
     * @param path Absolute file path
     * @param newHash The new content hash from VfsSummaryWatcher
     */
    fun onHashChange(path: String, newHash: String) {
        val current = entries[path] ?: return

        // If the hash matches what we have, file hasn't actually changed
        // (e.g., save without modification)
        if (current.contentHashAtSummary == newHash) return

        // Don't clear the synopsis — stale is better than nothing.
        // The staleness is detected by getCachedSynopsis() when it
        // compares currentContentHash with contentHashAtSummary.
        Dev.info(log, "cache.stale",
            "path" to path,
            "oldHash" to (current.contentHashAtSummary?.take(8) ?: "NONE"),
            "newHash" to newHash.take(8)
        )
    }

    // ==================== Cleanup ====================

    /**
     * Remove all cached data for a deleted file.
     */
    fun onFileDeleted(path: String) {
        entries.remove(path)
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
     */
    fun clear() {
        val size = entries.size
        entries.clear()
        Dev.info(log, "cache.cleared", "entriesRemoved" to size)
    }

    // ==================== Helpers ====================

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
     * @param summaries Map of filePath → Entry data from storage
     */
    fun warmFromStorage(summaries: Map<String, Entry>) {
        if (warmedFromStorage) return
        synchronized(this) {
            if (warmedFromStorage) return
            for ((path, entry) in summaries) {
                entries.putIfAbsent(path, entry)
            }
            warmedFromStorage = true
            Dev.info(log, "cache.warmed", "entries" to summaries.size)
        }
    }
}