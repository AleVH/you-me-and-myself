// File: src/main/kotlin/com/youmeandmyself/context/orchestrator/resolvers/JsTsImportResolver.kt
package com.youmeandmyself.context.orchestrator.resolvers

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.DumbService
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.youmeandmyself.context.orchestrator.ContextSignal

/**
 * JS/TS import resolver (heuristic).
 * Handles:
 *   import x from "foo/bar"
 *   const x = require("foo/bar")
 * Resolves last path segment to candidate file names (index.ts, .ts, .tsx, .js).
 */
class JsTsImportResolver : RelatedResolver {

    override fun supports(languageId: String?): Boolean {
        if (languageId == null) return false
        val id = languageId.lowercase()
        return id.contains("javascript") || id.contains("typescript") || id == "js" || id == "ts"
    }

    override fun resolve(project: Project, file: VirtualFile): List<ContextSignal.RelevantCandidate> {
        if (DumbService.isDumb(project)) return emptyList()

        val out = mutableListOf<ContextSignal.RelevantCandidate>()
        val text = runCatching { String(file.inputStream.readAllBytes()) }.getOrNull()?.take(200_000) ?: return out

        val importRegex = Regex("""(?m)^\s*(?:import\s+.*?\s+from\s+|require\()\s*["']([^"']+)["']""")
        val hits = importRegex.findAll(text).mapNotNull { it.groupValues.getOrNull(1) }.toList()

        val scope = GlobalSearchScope.projectScope(project)

        // Wrap all index access in one read action
        val found = ReadAction.compute<List<Pair<VirtualFile, String>>, Throwable> {
            val acc = mutableListOf<Pair<VirtualFile, String>>()
            for (spec in hits) {
                val last = spec.substringAfterLast('/').ifBlank { spec }
                val names = listOf(
                    "$last.ts", "$last.tsx", "$last.js", "$last.jsx", "$last/index.ts", "$last/index.js"
                )
                for (n in names) {
                    val files = FilenameIndex.getVirtualFilesByName(project, n, scope)
                    for (vf in files) {
                        if (vf.isValid) acc += vf to spec
                    }
                }
            }
            acc
        }

        for ((vf, spec) in found) {
            out += ContextSignal.RelevantCandidate(
                path = vf.path,
                reason = "import: $spec",
                score = 80,
                estChars = estimateCharsOrNull(vf) // this helper will be wrapped below
            )
        }
        return out
    }

    private fun estimateCharsOrNull(vf: VirtualFile): Int? {
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
