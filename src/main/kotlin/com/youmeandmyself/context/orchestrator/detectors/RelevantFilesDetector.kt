// (project-root)/src/main/kotlin/com/youmeandmyself/context/orchestrator/detectors/RelevantFilesDetector.kt
package com.youmeandmyself.context.orchestrator.detectors

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.youmeandmyself.context.orchestrator.*

/**
 * RelevantFilesDetector
 * - Finds a concise set of "anchor" files the rest of the pipeline can use:
 *   README, CONTRIBUTING, LICENSE, .editorconfig, main entrypoints, DI/config files, etc.
 * - Keeps list small to avoid noise. Confidence scales with count.
 */
class RelevantFilesDetector : Detector {
    override val name: String = "RelevantFilesDetector"

    override suspend fun isApplicable(request: ContextRequest): Boolean =
        request.wantRelevantFiles

    override suspend fun detect(project: Project, request: ContextRequest): List<ContextSignal> {
        if (DumbService.isDumb(project)) return emptyList()

        val roots: List<VirtualFile> = if (request.scopePaths.isEmpty()) {
            project.baseDir()?.let { listOf(it) } ?: emptyList()
        } else {
            request.scopePaths.mapNotNull { project.baseDir()?.findFileByRelativePath(it) }
        }

        val matches = mutableSetOf<String>()
        val names = setOf(
            "README.md", "README", "CONTRIBUTING.md", "LICENSE", ".editorconfig",
            // Common app entrypoints/configs
            "main.kt", "Main.kt", "Application.kt",
            "index.js", "index.ts", "main.ts", "server.ts",
            "app.module.ts", "nest-cli.json",
            "composer.json", ".env", ".env.example",
            "application.yml", "application.yaml", "application.properties"
        )

        for (root in roots) {
            ProgressManager.checkCanceled()
            VfsUtilCore.iterateChildrenRecursively(root, null) { vf ->
                ProgressManager.checkCanceled()
                if (!vf.isDirectory && vf.name in names) {
                    matches += vf.path
                }
                true // continue iteration
            }
        }

        val conf = when {
            matches.size >= 5 -> Confidence.HIGH
            matches.size >= 2 -> Confidence.MEDIUM
            matches.isNotEmpty() -> Confidence.LOW
            else -> Confidence.LOW
        }

        return listOf(
            ContextSignal.RelevantFiles(
                filePaths = matches.take(25).sorted(), // cap to keep payload small
                confidence = conf,
                source = name
            )
        )
    }

    private fun Project.baseDir(): VirtualFile? =
        this.baseDir ?: this.projectFile?.parent
}
