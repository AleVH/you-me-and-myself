package com.youmeandmyself.context.orchestrator

import com.youmeandmyself.dev.Dev
import com.intellij.openapi.diagnostic.Logger

/**
 * MergePolicy
 *
 * Pure merge function: detector signals in → ContextBundle out.
 *
 * ## Purpose
 *
 * Deterministically merge raw detector signals into a final [ContextBundle].
 * Produces a materialized, capped, provider-agnostic file list with reasons
 * and truncation flags. Returns metrics about what was produced.
 *
 * ## Behavior
 *
 * 1. Picks the best singleton signals (language, project structure) by confidence.
 * 2. Unions frameworks by name (highest confidence wins).
 * 3. For files:
 *    - Deduplicates by path (keeps highest score).
 *    - Applies denylist filters (dirs, binary/media suffixes, secrets).
 *    - Orders by score desc, then path asc (stable, predictable).
 *    - Enforces caps (max files, per-file chars, total chars).
 *    - Marks files as truncated when their estimated length exceeds per-file cap.
 *
 * ## Design Constraints
 *
 * - **Pure function**: No service lookups, no singletons, no side effects.
 *   All configuration is received via [MergeConfig] parameter.
 * - **All files are RAW at this stage.** Summary enrichment happens downstream
 *   in [ContextAssembler] via [SummaryStoreProvider].
 * - **Metrics are returned**, not stored in statics. The caller decides what
 *   to do with them (log, display, aggregate).
 *
 * ## Cap Defaults
 *
 * [MergeConfig] provides sensible defaults (15 files, 8K per file, 50K total).
 * These can be overridden by the caller — e.g., wired from plugin settings —
 * without changing MergePolicy itself.
 */
object MergePolicy {
    private val log = Logger.getInstance(MergePolicy::class.java)

    // ==================== Public API ====================

    /**
     * Configuration for the merge operation.
     *
     * All caps and limits are passed in here instead of being hardcoded.
     * Default values match the original constants so existing callers
     * that don't pass config continue to work identically.
     *
     * Future: these defaults can be wired from the plugin settings page
     * (Tools → YMM → Context) so the user controls prompt budget.
     *
     * @property maxFilesTotal Maximum number of files to include in context
     * @property maxCharsPerFile Maximum characters per individual file
     * @property maxCharsTotal Maximum total characters across all files
     */
    data class MergeConfig(
        val maxFilesTotal: Int = 15,
        val maxCharsPerFile: Int = 8_000,
        val maxCharsTotal: Int = 50_000
    )

    /**
     * Result of a merge operation.
     *
     * Contains the [ContextBundle] plus metrics about what was produced.
     * Metrics are populated by MergePolicy for the RAW stage; downstream
     * enrichment (ContextAssembler) may update its own counts for summaries.
     *
     * @property bundle The merged context bundle ready for enrichment
     * @property filesRawAttached Number of RAW files included in the bundle
     * @property filesDroppedByDenylist Files excluded by denylist rules
     * @property filesDroppedByCap Files excluded because caps were reached
     * @property truncatedCount Files included but marked as truncated (exceeded per-file cap)
     */
    data class MergeResult(
        val bundle: ContextBundle,
        val filesRawAttached: Int = 0,
        val filesDroppedByDenylist: Int = 0,
        val filesDroppedByCap: Int = 0,
        val truncatedCount: Int = 0
    )

    /**
     * Merge detector signals into a [ContextBundle].
     *
     * This is the single entry point. Receives signals from detectors and
     * configuration as parameters — no service lookups, no side effects.
     *
     * @param signals All detector signals collected by [ContextOrchestrator]
     * @param config Caps and limits for the merge (defaults provided)
     * @return [MergeResult] with the bundle and production metrics
     */
    fun merge(
        signals: List<ContextSignal>,
        config: MergeConfig = MergeConfig()
    ): MergeResult {

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
            filesSignal = filesSignal,
            config = config
        )

        return MergeResult(
            bundle = ContextBundle(
                language = lang,
                frameworks = frameworks.values.toList(),
                projectStructure = structure,
                relevantFiles = filesSignal,
                files = result.finalFiles,
                totalChars = result.totalChars,
                truncatedCount = result.truncatedCount,
                rawSignals = signals
            ),
            filesRawAttached = result.finalFiles.size,
            filesDroppedByDenylist = result.deniedCount,
            filesDroppedByCap = result.cappedCount,
            truncatedCount = result.truncatedCount
        )
    }

    // ==================== Internals ====================

    /**
     * Generic "best of two" by score.
     * If scores tie, prefer the newer (b).
     */
    private fun <T> pickBest(a: T?, b: T, score: (T) -> Int): T =
        if (a == null) b else if (score(b) >= score(a)) b else a

    // ── Denylist Configuration ───────────────────────────────────────────

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

    // ── Files Builder ────────────────────────────────────────────────────

    /**
     * Internal aggregation result from the files builder.
     *
     * Tracks not just the final file list but also how many files were
     * dropped at each stage, so the caller gets full visibility.
     */
    private data class BuildResult(
        val finalFiles: List<ContextFile>,
        val totalChars: Int,
        val truncatedCount: Int,
        val deniedCount: Int,
        val cappedCount: Int
    )

    /**
     * Build the final file list: dedupe → filter → order → cap → flag truncation.
     *
     * All files are marked as [ContextKind.RAW] at this stage. Summary enrichment
     * happens downstream in [ContextAssembler] via [SummaryStoreProvider].
     *
     * @param langId Primary language ID from the language detector (applied to all files)
     * @param filesSignal The relevant files signal from detectors
     * @param config Caps and limits for this merge
     * @return [BuildResult] with final files and drop counts
     */
    private fun buildFiles(
        langId: String?,
        filesSignal: ContextSignal.RelevantFiles?,
        config: MergeConfig
    ): BuildResult {

        Dev.info(log, "merge.files.start",
            "hasFilesSignal" to (filesSignal != null),
            "candidates" to (filesSignal?.candidates?.size ?: 0),
            "lang" to langId)

        if (filesSignal == null) return BuildResult(emptyList(), 0, 0, 0, 0)

        // 1) Deduplicate by path (keep the highest-score candidate).
        val dedup = filesSignal.candidates
            .groupBy { it.path }
            .mapValues { (_, list) -> list.maxByOrNull { it.score }!! }
            .values

        // 2) Apply denylist filters early to keep budgets for useful files.
        val filtered = dedup.filterNot { isDenied(it.path) }
        val deniedCount = dedup.size - filtered.size

        // 3) Deterministic ordering: score desc → path asc (stable, predictable).
        val ordered = filtered.sortedWith(
            compareByDescending<ContextSignal.RelevantCandidate> { it.score }
                .thenBy { it.path }
        )

        // 4) Enforce caps and compute truncation.
        val out = mutableListOf<ContextFile>()
        var total = 0
        var truncated = 0
        var cappedCount = 0

        for (cand in ordered) {
            if (out.size >= config.maxFilesTotal) {
                cappedCount++
                continue
            }

            val estRaw = cand.estChars ?: 0
            val allowedForFile = minOf(estRaw, config.maxCharsPerFile)
            val wouldBe = total + allowedForFile
            if (wouldBe > config.maxCharsTotal) {
                cappedCount++
                continue
            }

            if (estRaw > config.maxCharsPerFile) truncated++

            out += ContextFile(
                path = cand.path,
                languageId = langId,
                kind = ContextKind.RAW,
                reason = cand.reason,
                charCount = allowedForFile,
                truncated = estRaw > config.maxCharsPerFile
            )

            total = wouldBe
        }

        Dev.info(log, "merge.files.done",
            "included" to out.size,
            "denied" to deniedCount,
            "capped" to cappedCount,
            "truncated" to truncated,
            "totalChars" to total
        )

        return BuildResult(out, total, truncated, deniedCount, cappedCount)
    }
}