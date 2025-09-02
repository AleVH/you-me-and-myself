// (project-root)/src/main/kotlin/com/youmeandmyself/context/orchestrator/detectors/LanguageDetector.kt
package com.youmeandmyself.context.orchestrator.detectors

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.youmeandmyself.context.orchestrator.*

/**
 * LanguageDetector
 * - Very fast heuristic: scan project files (optionally restricted by request.scopePaths),
 *   map file types to language IDs, and pick the highest coverage language.
 * - Index-aware: returns quickly in dumb mode with LOW confidence.
 * - Cancellation-aware: checks coroutineContext.isActive in loops.
 */
class LanguageDetector : Detector {
    override val name: String = "LanguageDetector"

    override suspend fun isApplicable(request: ContextRequest): Boolean =
        request.wantLanguage

    override suspend fun detect(project: Project, request: ContextRequest): List<ContextSignal> {
        // If IDE is indexing, we return a conservative guess with low confidence.
        if (DumbService.isDumb(project)) {
            return listOf(
                ContextSignal.Language(
                    languageId = "unknown",
                    confidence = Confidence.LOW,
                    source = name
                )
            )
        }

        // Collect files under the requested scope (or whole project if empty).
        val files = mutableListOf<VirtualFile>()
        val roots: List<VirtualFile> = if (request.scopePaths.isEmpty()) {
            project.baseDir()?.let { listOf(it) } ?: emptyList()
        } else {
            request.scopePaths.mapNotNull { project.baseDir()?.findFileByRelativePath(it) }
        }

        for (root in roots) {
            ProgressManager.checkCanceled()
            VfsUtilCore.iterateChildrenRecursively(root, null) { vf ->
                ProgressManager.checkCanceled()
                if (!vf.isDirectory) files.add(vf)
                true
            }
        }

        // Simple extension → language heuristic. Extend as needed later.
        val counts = mutableMapOf<String, Int>()
        fun bump(lang: String) { counts[lang] = (counts[lang] ?: 0) + 1 }

        for (vf in files) {
            ProgressManager.checkCanceled()
            when (vf.extension?.lowercase()) {
                "kt", "kts" -> bump("kotlin")
                "java" -> bump("java")
                "py" -> bump("python")
                "js" -> bump("javascript")
                "ts", "tsx" -> bump("typescript")
                "php" -> bump("php")
                "rb" -> bump("ruby")
                "go" -> bump("go")
                "rs" -> bump("rust")
                "scala" -> bump("scala")
                "cpp", "cc", "cxx", "hpp", "h" -> bump("cpp")
                "c" -> bump("c")
                "swift" -> bump("swift")
                "m", "mm" -> bump("objective-c")
            }
        }

        val best = counts.maxByOrNull { it.value }?.key
        val conf = when {
            counts.isEmpty() || best == null -> Confidence.LOW
            counts.values.sum() > 200 -> Confidence.HIGH
            counts.values.sum() > 30 -> Confidence.MEDIUM
            else -> Confidence.LOW
        }

        val lang = best ?: "unknown"
        return listOf(
            ContextSignal.Language(
                languageId = lang,
                confidence = conf,
                source = name
            )
        )
    }

    // Helper to get project base directory in both directory-based and file-based projects.
    private fun Project.baseDir(): VirtualFile? =
        this.baseDir ?: this.projectFile?.parent
}
