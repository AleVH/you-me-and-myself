// (project-root)/src/main/kotlin/com/youmeandmyself/context/orchestrator/detectors/RelevantFilesDetector.kt
package com.youmeandmyself.context.orchestrator.detectors

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.youmeandmyself.context.orchestrator.*
import com.intellij.openapi.fileEditor.FileEditorManager
import com.youmeandmyself.context.orchestrator.resolvers.ResolverCombiner
import com.youmeandmyself.context.orchestrator.resolvers.KotlinJavaImportResolver
import com.youmeandmyself.context.orchestrator.resolvers.JsTsImportResolver
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.application.ReadAction

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
            project.baseDirSafe()?.let { listOf(it) } ?: emptyList()
        } else {
            request.scopePaths.mapNotNull { project.baseDirSafe()?.findFileByRelativePath(it) }
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

        // --- Anchor candidates as before ---
        val anchorCandidates = matches
            .take(25)
            .sorted()
            .map { path ->
                ContextSignal.RelevantCandidate(
                    path = path,
                    reason = "filename match",
                    score = 60,
                    estChars = estimateCharsOrNull(path)
                )
            }

        // --- Current editor file candidate ---
        val editorFile = FileEditorManager.getInstance(project).selectedFiles.firstOrNull()
        val currentFileCandidate = editorFile?.let { vf ->
            ContextSignal.RelevantCandidate(
                path = vf.path,
                reason = "current file",
                score = 100,
                estChars = estimateCharsOrNull(vf.path)
            )
        }

        // --- Resolver candidates (using ResolverCombiner) ---
        val languageId = editorFile?.fileType?.name
        val resolverCandidates =
            if (editorFile != null && languageId != null) {
                ResolverCombiner.collect(
                    project = project,
                    seed = editorFile,
                    languageId = languageId,
                    resolvers = listOf(
                        KotlinJavaImportResolver(),
                        JsTsImportResolver()
                        // more resolvers can be added here later
                    )
                )
            } else emptyList()

        // --- Merge all candidates ---
        val combined = buildList {
            addAll(anchorCandidates)
            currentFileCandidate?.let { add(it) }
            addAll(resolverCandidates)
        }

        return listOf(
            ContextSignal.RelevantFiles(
                candidates = combined,
                confidence = conf,
                source = name
            )
        )
    }

    private fun Project.baseDirSafe(): VirtualFile? {
        val base = this.basePath
        val lfs = LocalFileSystem.getInstance()
        val fromBasePath = base?.let { lfs.findFileByPath(it) }
        return fromBasePath ?: this.projectFile?.parent
    }

    // Place this as a private function in the same file.
    // If you don't have Project/VFS handy here, you can just return null.
    private fun estimateCharsOrNull(path: String): Int? {
        return try {
            ReadAction.compute<Int?, Throwable> {
                val vfs = LocalFileSystem.getInstance()
                val vf = vfs.findFileByPath(path) ?: return@compute null
                val fdm = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance()
                val doc = fdm.getDocument(vf)
                doc?.textLength ?: vf.length.toInt()
            }
        } catch (_: Throwable) {
            null
        }
    }

}