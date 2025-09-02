// (project-root)/src/main/kotlin/com/youmeandmyself/context/orchestrator/detectors/ProjectStructureDetector.kt
package com.youmeandmyself.context.orchestrator.detectors

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VirtualFile
import com.youmeandmyself.context.orchestrator.*

/**
 * ProjectStructureDetector
 * - Captures: list of modules (source roots’ top-level parents as a proxy), and build system presence.
 * - Keeps it cheap and index-aware.
 */
class ProjectStructureDetector : Detector {
    override val name: String = "ProjectStructureDetector"

    override suspend fun isApplicable(request: ContextRequest): Boolean =
        request.wantProjectStructure

    override suspend fun detect(project: Project, request: ContextRequest): List<ContextSignal> {
        if (DumbService.isDumb(project)) {
            return listOf(
                ContextSignal.ProjectStructure(
                    modules = emptyList(),
                    buildSystem = null,
                    confidence = Confidence.LOW,
                    source = name
                )
            )
        }

        val prm = ProjectRootManager.getInstance(project)
        val modules = prm.contentSourceRoots
            .mapNotNull { guessModuleName(it) }
            .distinct()
            .sorted()

        val build = detectBuildSystem(project)

        val conf = when {
            modules.isNotEmpty() || build != null -> Confidence.MEDIUM
            else -> Confidence.LOW
        }

        return listOf(
            ContextSignal.ProjectStructure(
                modules = modules,
                buildSystem = build,
                confidence = conf,
                source = name
            )
        )
    }

    private fun guessModuleName(root: VirtualFile): String? {
        // Heuristic: module name is the top-level folder under project base containing this source root.
        val path = root.path
        val parts = path.split('/', '\\')
        return parts.dropLast(3).lastOrNull() // keeps it very rough and fast
    }

    private fun detectBuildSystem(project: Project): String? {
        val base = project.baseDir ?: project.projectFile?.parent ?: return null
        val children = base.children?.map { it.name }?.toSet() ?: return null
        return when {
            "build.gradle" in children || "build.gradle.kts" in children -> "Gradle"
            "settings.gradle" in children || "settings.gradle.kts" in children -> "Gradle"
            "pom.xml" in children -> "Maven"
            "package.json" in children -> "npm"
            "composer.json" in children -> "Composer"
            "pyproject.toml" in children || "requirements.txt" in children -> "Python (pip/poetry)"
            else -> null
        }
    }
}
