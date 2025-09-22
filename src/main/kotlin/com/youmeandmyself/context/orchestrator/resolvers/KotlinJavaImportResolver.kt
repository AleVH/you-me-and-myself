// File: src/main/kotlin/com/youmeandmyself/context/orchestrator/resolvers/KotlinJavaImportResolver.kt
package com.youmeandmyself.context.orchestrator.resolvers

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.DumbService
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.youmeandmyself.context.orchestrator.ContextSignal

/**
 * Kotlin/Java import-based resolver.
 * Uses PSI when available; falls back to regex on file text + filename lookups.
 * Scoring:
 *  - direct import hit: 90
 *  - same package sibling (kotlin/java): 75
 */
class KotlinJavaImportResolver : RelatedResolver {

    override fun supports(languageId: String?): Boolean {
        if (languageId == null) return false
        val id = languageId.lowercase()
        return id.contains("kotlin") || id.contains("java")
    }

    override fun resolve(project: Project, file: VirtualFile): List<ContextSignal.RelevantCandidate> {
        // Skip while indexing to avoid index/PSI access violations and noise
        if (DumbService.isDumb(project)) return emptyList()

        return ReadAction.compute<List<ContextSignal.RelevantCandidate>, Throwable> {
            val out = mutableListOf<ContextSignal.RelevantCandidate>()

            val psi = com.intellij.psi.PsiManager.getInstance(project).findFile(file)
            val text = runCatching { psi?.text ?: String(file.inputStream.readAllBytes()) }
                .getOrNull()
                ?.take(200_000) // safety cap

            // --- Imports (regex fallback; Kotlin/Java) ---
            if (text != null) {
                val importRegex = Regex("""(?m)^\s*(?:import|package)\s+([a-zA-Z0-9_\.]+)""")
                val hits = importRegex.findAll(text).mapNotNull { it.groupValues.getOrNull(1) }.toList()

                val scope = GlobalSearchScope.projectScope(project)
                for (fqName in hits) {
                    val simple = fqName.substringAfterLast('.')
                    if (simple.isBlank()) continue
                    val candidates = FilenameIndex.getVirtualFilesByName(project, "$simple.kt", scope) +
                            FilenameIndex.getVirtualFilesByName(project, "$simple.java", scope)
                    for (vf in candidates) {
                        if (!vf.isValid) continue
                        out += ContextSignal.RelevantCandidate(
                            path = vf.path,
                            reason = "import: $fqName",
                            score = 90,
                            estChars = estimateCharsOrNull(project, vf) // wrapped below
                        )
                    }
                }
            }

            // --- Same-package siblings heuristic ---
            val parent = file.parent
            if (parent != null && parent.isValid) {
                parent.children.orEmpty()
                    .filter { it.isValid && it != file && (it.name.endsWith(".kt") || it.name.endsWith(".java")) }
                    .take(20)
                    .forEach { sib ->
                        out += ContextSignal.RelevantCandidate(
                            path = sib.path,
                            reason = "same package",
                            score = 75,
                            estChars = estimateCharsOrNull(project, sib) // wrapped below
                        )
                    }
            }

            out
        }
    }

    private fun estimateCharsOrNull(project: Project, vf: VirtualFile): Int? {
        return try {
            ReadAction.compute<Int?, Throwable> {
                val doc = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(vf)
                doc?.textLength ?: vf.length.toInt()
            }
        } catch (_: Throwable) {
            null
        }
    }

}
