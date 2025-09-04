// (project-root)/src/main/kotlin/com/youmeandmyself/context/orchestrator/MergePolicy.kt
package com.youmeandmyself.context.orchestrator

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

    /**
     * Entry point: merge the list of signals into a ContextBundle.
     */
    fun merge(signals: List<ContextSignal>): ContextBundle {
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
        )
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

    /** Max number of files to include in the final bundle. */
    private const val MAX_FILES_TOTAL = 16

    /** Max allowed characters per file (used for budgeting/truncation flag). */
    private const val MAX_CHARS_PER_FILE = 50_000

    /** Max total characters across all files (sum of per-file budgets). */
    private const val MAX_CHARS_TOTAL = 500_000

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
        val truncatedCount: Int
    )

    /**
     * Converts a RelevantFiles signal into a finalized, capped list of ContextFile entries.
     */
    private fun buildFiles(
        langId: String?,
        filesSignal: ContextSignal.RelevantFiles?
    ): BuildResult {
        if (filesSignal == null) return BuildResult(emptyList(), 0, 0)

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

        // 4) Enforce caps and compute truncation flags.
        val out = mutableListOf<ContextFile>()
        var total = 0
        var truncated = 0

        for (cand in ordered) {
            if (out.size >= MAX_FILES_TOTAL) break

            val est = cand.estChars ?: 0
            val allowedForFile = minOf(est, MAX_CHARS_PER_FILE)
            val wouldBe = total + allowedForFile
            if (wouldBe > MAX_CHARS_TOTAL) break

            val isTrunc = est > MAX_CHARS_PER_FILE
            if (isTrunc) truncated++

            out += ContextFile(
                path = cand.path,
                languageId = langId,              // per-file language can be added later if detectors supply it
                kind = ContextKind.RAW,           // SUMMARY is reserved for M4 (summaries/indexer)
                reason = cand.reason,             // transparency: why this file is here
                charCount = allowedForFile,       // budget for this file (PromptBuilder will slice to this)
                truncated = isTrunc               // flag for transcript note
            )
            total = wouldBe
        }

        return BuildResult(out, total, truncated)
    }
}
