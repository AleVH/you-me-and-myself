// File: src/main/kotlin/com/youmeandmyself/ai/context/orchestrator/SummaryStore.kt

package com.youmeandmyself.context.orchestrator

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.youmeandmyself.context.provider.SynopsisProviderRegistry
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import com.intellij.openapi.Disposable
import kotlinx.coroutines.cancel
import com.youmeandmyself.dev.Dev
import com.intellij.openapi.diagnostic.Logger

/**
 * Project-level cache for per-file summaries used by M4.
 * Responsibilities:
 *  - Store/retrieve header samples (cheap, fast to compute)
 *  - Store/retrieve model synopses (expensive, may be stale)
 *  - Track staleness using a content hash recorded at summarization time
 *  - Provide a simple, cancel-safe API for MergePolicy to request summaries
 *
 * NOTE: This is intentionally simple and memory-backed for v1.
 *       You can migrate to persistent storage later without changing the API.
 */
@Service(Service.Level.PROJECT)
class SummaryStore(private val project: Project) : Disposable {

    private val log = Logger.getInstance(SummaryStore::class.java)

    data class Entry(
        val path: String,
        val languageId: String?,
        val summaryVersion: Int = 1,              // bump when changing synopsis format
        val contentHashAtSummary: String? = null, // hash when synopsis was computed
        val lastSummarizedAt: Instant? = null,
        val headerSample: String? = null,
        val modelSynopsis: String? = null
    )

    private val entries = ConcurrentHashMap<String, Entry>()

    // Prevent duplicate enqueue for the same path while a job is running
    private val pendingSynopsis = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    // Coroutine scope dedicated to background summarization jobs (supervised, IO dispatcher)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ---------------------------
    // Public API (used by merge)
    // ---------------------------

    /**
     * Ensure a header sample exists (compute-once, cheap).
     * Returns the header sample and an estimated char count for budgeting.
     */
    fun ensureHeaderSample(
        path: String,
        languageId: String?,
        maxChars: Int
    ): Pair<String?, Int> {
        val current = entries[path]
        if (current?.headerSample != null) {
            return current.headerSample to (current.headerSample.length)
        }
        val sample = computeHeaderSample(path, maxChars)
        val updated = (current ?: Entry(path, languageId)).copy(headerSample = sample)
        entries[path] = updated
        return sample to (sample?.length ?: 0)
    }

    /**
     * Get (or schedule) a model synopsis. Returns:
     *  - synopsis text (may be null the first time), and
     *  - staleness flag (true when file hash != recorded hash)
     *
     * If null is returned, caller should attach headerSample alone (still useful).
     */
    fun getOrEnqueueSynopsis(
        path: String,
        languageId: String?,
        currentContentHash: String?,
        maxTokens: Int,
        autoGenerate: Boolean
    ): Pair<String?, Boolean> {

        Dev.info(log, "syn.store.call",
            "path" to path,
            "auto" to autoGenerate,
            "hash" to (currentContentHash?.take(8) ?: "NONE"))


        val current = entries[path]
        val isStale = currentContentHash != null &&
                current?.contentHashAtSummary != null &&
                current.contentHashAtSummary != currentContentHash

        // If we already have a synopsis, return it (even if stale)
        if (current?.modelSynopsis != null) {
            return current.modelSynopsis to isStale
        }

        // Optionally kick off a background job to compute it
        if (autoGenerate && pendingSynopsis.add(path)) {

            Dev.info(log, "syn.store.enqueue", "path" to path, "tokens" to maxTokens)

            scope.launch {
                try {
                    // (1) Ensure we have a header sample to seed the LLM prompt (cheap + cached)
                    val (headerSample, _) = ensureHeaderSample(
                        path = path,
                        languageId = languageId,
                        maxChars = 1_500
                    )
                    val sourceText = headerSample ?: computeHeaderSample(path, 1_500) ?: ""

                    // RIGHT HERE, before calling the HTTP synopsis provider:
                    val selectedSummaryAi = com.youmeandmyself.ai.providers.ProviderRegistry.selectedSummaryProvider(project)
                    Dev.info(log, "syn.provider",
                        "summaryAi" to (selectedSummaryAi?.id ?: "NONE"),
                        "http" to "KtorSynopsisProvider")

                    // (2) Obtain the active provider and generate a synopsis (suspend, non-blocking)
                    val provider = SynopsisProviderRegistry.getInstance(project).activeProvider()

                    Dev.info(log, "syn.run.start",
                        "path" to path,
                        "provider" to provider.javaClass.simpleName,
                        "srcLen" to sourceText.length,
                        "tokens" to maxTokens
                    )

                    val generated = provider.generateSynopsis(
                        project = project,
                        path = path,
                        languageId = languageId,
                        sourceText = sourceText,
                        maxTokens = maxTokens
                    )

                    Dev.info(log, "syn.run.ok",
                        "path" to path,
                        "len" to generated.length
                    )

                    // (3) Persist result atomically
                    val now = Instant.now()
                    entries.compute(path) { _, curr ->
                        val base = curr ?: Entry(path = path, languageId = languageId)
                        base.copy(
                            modelSynopsis = generated,
                            contentHashAtSummary = currentContentHash,
                            lastSummarizedAt = now
                        )
                    }
                } catch (e: Throwable) {
                    // Optional: log/throttle
                    Dev.warn(log, "syn.run.fail", e, "path" to path)
                } finally {
                    pendingSynopsis.remove(path)
                }
            }
        }

        return null to false
    }

    /**
     * Mark synopsis stale when file changes. Caller should pass new hash.
     * Header sample will be recomputed lazily on next call.
     */
    fun onHashChange(path: String, newHash: String) {
        val current = entries[path] ?: return
        entries[path] = current.copy(
            // we purposely do not blank modelSynopsis; we treat it as "stale but usable"
            // and let getOrEnqueueSynopsis() refresh in the background
            contentHashAtSummary = current.contentHashAtSummary, // unchanged until re-summarized
            lastSummarizedAt = current.lastSummarizedAt
        )
        // Header sample refresh will happen lazily via ensureHeaderSample()
    }

    /**
     * Dev convenience: allow manual warm-up (pre-compute header samples).
     */
    fun warmUp(paths: List<String>, languageId: String?, maxChars: Int) {
        paths.forEach { ensureHeaderSample(it, languageId, maxChars) }
    }

    // ---------------------------
    // Internal helpers
    // ---------------------------

    private fun computeHeaderSample(path: String, maxChars: Int): String? {
        return try {
            val p = Path.of(path)
            if (!Files.isRegularFile(p)) return null
            val raw = Files.readString(p)
            // Head slice: top of file; in the future you can add a tail slice for some languages.
            raw.substring(0, min(raw.length, maxChars))
        } catch (_: Throwable) {
            null
        }
    }

    override fun dispose() {
        // Cancel all ongoing synopsis jobs gracefully when the project closes
        scope.cancel("Project disposed")
    }

    // Evict all cached data for a file that no longer exists
    fun onFileDeleted(path: String) {
        // Remove any in-flight job guard
        pendingSynopsis.remove(path)
        // Drop header/sample/synopsis stored in the entry map
        entries.remove(path)
        // If you later add other caches (e.g., LRU), evict them here too.
    }

}
