// File: src/main/kotlin/com/youmeandmyself/context/orchestrator/resolvers/ResolverCombiner.kt
package com.youmeandmyself.context.orchestrator.resolvers

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.youmeandmyself.context.orchestrator.ContextSignal

/**
 * Runs all matching resolvers, merges, de-dupes, and caps the result.
 * This is resolver-level capping (light); MergePolicy still applies global caps later.
 */
object ResolverCombiner {

    /** Soft cap to avoid huge intermediate lists before MergePolicy. */
    private const val SOFT_MAX = 64

    fun collect(
        project: Project,
        seed: VirtualFile,
        languageId: String?,
        resolvers: List<RelatedResolver>
    ): List<ContextSignal.RelevantCandidate> {
        if (!seed.isValid) return emptyList()
        val applicable = resolvers.filter { it.supports(languageId) }
        if (applicable.isEmpty()) return emptyList()

        val all = buildList {
            for (r in applicable) {
                runCatching { addAll(r.resolve(project, seed)) }.onFailure { /* swallow & continue */ }
                if (size >= SOFT_MAX) break
            }
        }

        // De-dupe by path, keep highest score; stable order by score desc, then path asc.
        return all
            .groupBy { it.path }
            .mapValues { (_, list) -> list.maxByOrNull { it.score }!! }
            .values
            .sortedWith(compareByDescending<ContextSignal.RelevantCandidate> { it.score }.thenBy { it.path })
            .take(SOFT_MAX)
    }
}
