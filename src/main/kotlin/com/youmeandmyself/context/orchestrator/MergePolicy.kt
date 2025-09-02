// (project-root)/src/main/kotlin/com/youmeandmyself/context/orchestrator/MergePolicy.kt
package com.youmeandmyself.context.orchestrator

/**
 * Purpose: deterministic merge of signals into a bundle.
 * Rule of thumb:
 * - For singletons (language/structure), pick highest confidence; tie-break by source priority.
 * - For lists (frameworks/files), union by key with max-confidence wins.
 */
object MergePolicy {
    fun merge(signals: List<ContextSignal>): ContextBundle {
        var lang: ContextSignal.Language? = null
        val frameworks = linkedMapOf<String, ContextSignal.Framework>()
        var structure: ContextSignal.ProjectStructure? = null
        var files: ContextSignal.RelevantFiles? = null

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
                files = pickBest(files, s) { it.confidence.value }
            }
        }

        return ContextBundle(
            language = lang,
            frameworks = frameworks.values.toList(),
            projectStructure = structure,
            relevantFiles = files,
            rawSignals = signals
        )
    }

    private fun <T> pickBest(a: T?, b: T, score: (T) -> Int): T =
        if (a == null) b else if (score(b) >= score(a)) b else a
}
