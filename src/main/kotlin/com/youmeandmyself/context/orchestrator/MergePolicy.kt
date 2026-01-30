// (project-root)/src/main/kotlin/com/youmeandmyself/context/orchestrator/MergePolicy.kt
package com.youmeandmyself.context.orchestrator

import com.intellij.openapi.project.ProjectManager
import com.youmeandmyself.ai.settings.PluginSettingsState
import com.youmeandmyself.dev.Dev
import com.intellij.openapi.diagnostic.Logger

/**
 * MergePolicy
 *
 * Purpose:
 * - Deterministically merge raw detector signals into a final ContextBundle.
 * - Produce a materialized, capped, provider-agnostic file list with reasons and truncation flags.
 *
 * Behavior:
 * 1) Picks the best singleton signals (language, project structure) by confidence.
 * 2) Unions frameworks by name (highest confidence wins).
 * 3) For files:
 *    - Deduplicates by path (keeps highest score).
 *    - Applies denylist filters (dirs, binary/media suffixes, secrets).
 *    - Orders by score desc, then path asc (stable).
 *    - Enforces caps (max files, per-file chars, total chars).
 *    - Marks files as truncated when their estimated length exceeds per-file cap.
 *
 * Notes:
 * - Caps (MAX_FILES_TOTAL, MAX_CHARS_PER_FILE, MAX_CHARS_TOTAL) are constants here;
 *   you can later read them from settings without changing call sites.
 * - Per-file language is set to the bundle's language for now; detectors can provide
 *   per-file language later if you extend RelevantCandidate to carry it.
 */
object MergePolicy {

    @Volatile internal var lastFilesRawAttached: Int = 0
    @Volatile internal var lastFilesSummarizedAttached: Int = 0
    @Volatile internal var lastStaleSynopsesUsed: Int = 0

    private val log = Logger.getInstance(MergePolicy::class.java)

    /**
     * Entry point: merge the list of signals into a ContextBundle.
     */
    fun merge(signals: List<ContextSignal>): ContextBundle {

        Dev.info(log, "merge.enter", "signals" to signals.size)

        var lang: ContextSignal.Language? = null
        val frameworks = linkedMapOf<String, ContextSignal.Framework>()
        var structure: ContextSignal.ProjectStructure? = null
        var filesSignal: ContextSignal.RelevantFiles? = null

        // Walk all signals and pick best/union as appropriate.
        for (s in signals) when (s) {
            is ContextSignal.Language -> {
                lang = pickBest(lang, s) { it.confidence.value }
            }
            is ContextSignal.Framework -> {
                val key = s.name.lowercase()
                val curr = frameworks[key]
                frameworks[key] = pickBest(curr, s) { it.confidence.value }
            }
            is ContextSignal.ProjectStructure -> {
                structure = pickBest(structure, s) { it.confidence.value }
            }
            is ContextSignal.RelevantFiles -> {
                filesSignal = pickBest(filesSignal, s) { it.confidence.value }
            }
        }

        // Build final, capped file list + totals/truncation counts.
        val result = buildFiles(
            langId = lang?.languageId,
            filesSignal = filesSignal
        )

        lastFilesRawAttached = result.filesRawAttached
        lastFilesSummarizedAttached = result.filesSummarizedAttached
        lastStaleSynopsesUsed = result.staleSynopsesUsed

        // Return the merged bundle with transparency fields filled in.
        return ContextBundle(
            language = lang,
            frameworks = frameworks.values.toList(),
            projectStructure = structure,
            relevantFiles = filesSignal,
            files = result.finalFiles,
            totalChars = result.totalChars,
            truncatedCount = result.truncatedCount,
            rawSignals = signals
        ).also {
            // Note: counters are pulled by ContextOrchestrator when building metrics.
            // (No change to ContextBundle fields.)
        }
    }

    /**
     * Generic “best of two” by score.
     * If scores tie, prefer the newer (b).
     */
    private fun <T> pickBest(a: T?, b: T, score: (T) -> Int): T =
        if (a == null) b else if (score(b) >= score(a)) b else a

    // ---------------------------------------------------------------------------------------------
    // Policy configuration (constants for now; wire to Settings later)
    // ---------------------------------------------------------------------------------------------
    /** Denylisted directory substrings (normalized with forward slashes). */
    private val DENY_DIRS = listOf(
        "/.git/", "/.idea/", "/node_modules/", "/vendor/",
        "/build/", "/out/", "/dist/", "/target/"
    )

    /** Denylisted file suffixes we never want to include. */
    private val DENY_SUFFIXES = listOf(
        ".class", ".jar", ".png", ".jpg", ".jpeg", ".gif", ".svg",
        ".webp", ".ico", ".pdf", ".zip", ".gz", ".tgz", ".bz2",
        ".exe", ".dll", ".so", ".dylib", ".a", ".o", ".bin"
    )

    /** Denylisted keywords suggesting secrets or sensitive material. */
    private val DENY_KEYWORDS = listOf("secret", ".env", ".pem", "id_rsa")

    /**
     * Returns true if a path should be excluded by the denylist rules.
     * Paths are compared in forward-slash form for consistency across OSes.
     */
    private fun isDenied(path: String): Boolean {
        val p = path.replace('\\', '/')
        if (DENY_DIRS.any { it in "$p/" }) return true
        if (DENY_SUFFIXES.any { p.endsWith(it, ignoreCase = true) }) return true
        if (DENY_KEYWORDS.any { it in p.lowercase() }) return true
        return false
    }

    // ---------------------------------------------------------------------------------------------
    // Files builder (dedupe → filter → order → cap → flag truncation)
    // ---------------------------------------------------------------------------------------------

    /** Internal aggregation result used by merge(). */
    private data class BuildResult(
        val finalFiles: List<ContextFile>,
        val totalChars: Int,
        val truncatedCount: Int,
        val filesRawAttached: Int,
        val filesSummarizedAttached: Int,
        val staleSynopsesUsed: Int
    )

    /**
     * Converts a RelevantFiles signal into a finalized, capped list of ContextFile entries.
     */
    private fun buildFiles(
        langId: String?,
        filesSignal: ContextSignal.RelevantFiles?
    ): BuildResult {

        Dev.info(log, "merge.files.start",
            "hasFilesSignal" to (filesSignal != null),
            "candidates" to (filesSignal?.candidates?.size ?: 0),
            "lang" to langId)

        if (filesSignal == null) return BuildResult(emptyList(), 0, 0, 0, 0, 0)

        // 1) Deduplicate by path (keep the highest-score candidate).
        val dedup = filesSignal.candidates
            .groupBy { it.path }
            .mapValues { (_, list) -> list.maxByOrNull { it.score }!! }
            .values

        // 2) Apply denylist filters early to keep budgets for useful files.
        val filtered = dedup.filterNot { isDenied(it.path) }

        // 3) Deterministic ordering: score desc → path asc (stable, predictable).
        val ordered = filtered.sortedWith(
            compareByDescending<ContextSignal.RelevantCandidate> { it.score }
                .thenBy { it.path }
        )

        // 4) Enforce caps and compute truncation/summaries.
        //    We decide RAW vs SUMMARY per candidate based on per-file cap.
        val out = mutableListOf<ContextFile>()
        var total = 0
        var truncated = 0
        // metrics: counters for transparency; wire into OrchestratorMetrics later.
        var filesRawAttached = 0
        var filesSummarizedAttached = 0
        var staleSynopsesUsed = 0

        // SummaryStore lives at project level; we resolve it lazily when needed
        val summaryStore = ProjectManager.getInstance()
            .openProjects.firstOrNull()
            ?.getService(SummaryStore::class.java)

        // NEW: read M4 caps/toggles from settings (fallback instance if service not found)
        val settings = ProjectManager.getInstance()
            .openProjects.firstOrNull()
            ?.getService(PluginSettingsState::class.java)
            ?: PluginSettingsState()


        for (cand in ordered) {
            if (out.size >= settings.maxFilesTotal) break

            val estRaw = cand.estChars ?: 0
            val rawWouldTruncate = estRaw > settings.maxCharsPerFile

            // Decide representation
            val useSummary = settings.enableSummaries && rawWouldTruncate && summaryStore != null

            Dev.info(log, "merge.files.pick",
                "path" to cand.path,
                "estRaw" to estRaw,
                "rawCap" to settings.maxCharsPerFile,
                "enableSummaries" to settings.enableSummaries,
                "store" to (summaryStore != null),
                "useSummary" to useSummary)

            val cf: ContextFile = if (!useSummary) {
                val allowedForFile = minOf(estRaw, settings.maxCharsPerFile)
                if (estRaw > settings.maxCharsPerFile) truncated++
                ContextFile(
                    path = cand.path,
                    languageId = langId,
                    kind = ContextKind.RAW,
                    reason = cand.reason,
                    charCount = allowedForFile,
                    truncated = estRaw > settings.maxCharsPerFile
                )
            } else {

                Dev.info(log, "merge.summary.start", "path" to cand.path, "lang" to langId, "reason" to cand.reason)


                // Ask SummaryStore for header + optional synopsis
                val (header, headerChars) = summaryStore.ensureHeaderSample(
                    path = cand.path,
                    languageId = langId,
                    maxChars = settings.headerSampleMaxChars
                )
                val (synopsis, isStale) = summaryStore.getOrEnqueueSynopsis(
                    path = cand.path,
                    languageId = langId,
                    currentContentHash = null, // TODO: pass a hash when you have it
                    maxTokens = settings.synopsisMaxTokens,
                    autoGenerate = settings.generateSynopsesAutomatically
                )

                Dev.info(log, "merge.synopsis.req", "path" to cand.path, "stale" to isStale)

                val budgeted = (headerChars) + (synopsis?.length ?: 0)

                ContextFile(
                    path = cand.path,
                    languageId = langId,
                    kind = ContextKind.SUMMARY,
                    reason = cand.reason,
                    charCount = budgeted,
                    truncated = false,
                    headerSample = header,
                    modelSynopsis = synopsis,
                    isStale = isStale,
                    source = "summary-cache"
                )
            }

            // Respect global cap using the actual representation size
            val wouldBe = total + cf.charCount
            if (wouldBe > settings.maxCharsTotal) break

            // metrics: increment counters by representation
            when (cf.kind) {
                ContextKind.RAW -> filesRawAttached++
                ContextKind.SUMMARY -> {
                    filesSummarizedAttached++
                    if (cf.isStale) staleSynopsesUsed++
                }
            }

            out += cf
            total = wouldBe
        }

        // TODO: add filesRawAttached, filesSummarizedAttached, staleSynopsesUsed to OrchestratorMetrics once the data class includes these fields.
        return BuildResult(
            out,
            total,
            truncated,
            filesRawAttached,
            filesSummarizedAttached,
            staleSynopsesUsed
        )
    }
}
