// File: src/main/kotlin/com/youmeandmyself/context/orchestrator/resolvers/RelatedResolver.kt
package com.youmeandmyself.context.orchestrator.resolvers

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.youmeandmyself.context.orchestrator.ContextSignal

/**
 * Contract for language-aware "related files" resolvers.
 * Return ContextSignal.RelevantCandidate entries (path + reason + score + estChars).
 */
interface RelatedResolver {
    /** True if the resolver can handle files for this language id (e.g., "kotlin", "JAVA", "TypeScript"). */
    fun supports(languageId: String?): Boolean

    /**
     * Given a seed file, return likely neighbors (imports, same-package files, siblings, etc.).
     * Implementations should be fast (aim < 200ms) and safe under missing PSI (fallback to heuristics).
     */
    fun resolve(project: Project, file: VirtualFile): List<ContextSignal.RelevantCandidate>
}