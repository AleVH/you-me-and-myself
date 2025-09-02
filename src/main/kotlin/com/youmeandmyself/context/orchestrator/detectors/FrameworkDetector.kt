// (project-root)/src/main/kotlin/com/youmeandmyself/context/orchestrator/detectors/FrameworkDetector.kt
package com.youmeandmyself.context.orchestrator.detectors

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.youmeandmyself.context.orchestrator.*
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * FrameworkDetector
 * - Scans common build files quickly and extracts recognizable frameworks/libs (by name)
 *   with optional versions when trivial to spot.
 * - Heuristic only; we’ll replace with proper parsers in later milestones.
 * - Index/cancellation aware via DumbService + ProgressManager.checkCanceled().
 */
class FrameworkDetector : Detector {
    override val name: String = "FrameworkDetector"

    override suspend fun isApplicable(request: ContextRequest): Boolean =
        request.wantFrameworks

    override suspend fun detect(project: Project, request: ContextRequest): List<ContextSignal> {
        if (DumbService.isDumb(project)) return emptyList()

        val roots: List<VirtualFile> = if (request.scopePaths.isEmpty()) {
            project.baseDir()?.let { listOf(it) } ?: emptyList()
        } else {
            request.scopePaths.mapNotNull { project.baseDir()?.findFileByRelativePath(it) }
        }

        val buildFiles = mutableListOf<VirtualFile>()
        val interestingNames = setOf(
            "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts",
            "pom.xml", "package.json", "composer.json", "requirements.txt", "pyproject.toml"
        )

        for (root in roots) {
            ProgressManager.checkCanceled()
            VfsUtilCore.iterateChildrenRecursively(root, null) { vf ->
                ProgressManager.checkCanceled()
                if (!vf.isDirectory && vf.name in interestingNames) buildFiles.add(vf)
                true // continue iteration
            }
        }

        val out = mutableListOf<ContextSignal.Framework>()
        for (vf in buildFiles) {
            ProgressManager.checkCanceled()
            val text = vf.safeReadText() ?: continue
            // Very small set of fast regex-ish checks; we’ll expand later.
            when (vf.name) {
                "build.gradle", "build.gradle.kts" -> {
                    // Look for common JVM frameworks/libraries
                    find(text, "org\\.springframework")?.let { v ->
                        out += f("Spring", v)
                    }
                    find(text, "io\\.ktor")?.let { v ->
                        out += f("Ktor", v)
                    }
                    find(text, "org\\.jetbrains\\.kotlin")?.let { v ->
                        out += f("Kotlin Stdlib", v)
                    }
                }
                "pom.xml" -> {
                    if (text.contains("<groupId>org.springframework</groupId>")) out += f("Spring", null)
                    if (text.contains("<groupId>io.ktor</groupId>")) out += f("Ktor", null)
                }
                "package.json" -> {
                    pkg(text, "react")?.let { out += f("React", it) }
                    pkg(text, "next")?.let { out += f("Next.js", it) }
                    pkg(text, "vue")?.let { out += f("Vue", it) }
                    pkg(text, "nestjs")?.let { out += f("NestJS", it) }
                    pkg(text, "express")?.let { out += f("Express", it) }
                    pkg(text, "tailwindcss")?.let { out += f("Tailwind CSS", it) }
                }
                "composer.json" -> {
                    comp(text, "laravel/framework")?.let { out += f("Laravel", it) }
                    comp(text, "symfony/symfony")?.let { out += f("Symfony", it) }
                }
                "requirements.txt", "pyproject.toml" -> {
                    if (text.contains("fastapi")) out += f("FastAPI", null)
                    if (text.contains("django")) out += f("Django", null)
                    if (text.contains("flask")) out += f("Flask", null)
                }
            }
        }

        // Confidence: higher if multiple frameworks found, medium if single, low if none.
        val conf = when {
            out.size >= 2 -> Confidence.HIGH
            out.size == 1 -> Confidence.MEDIUM
            else -> Confidence.LOW
        }

        return out.map { it.copy(confidence = conf) }
    }

    private fun f(name: String, version: String?): ContextSignal.Framework =
        ContextSignal.Framework(name = name, version = version, confidence = Confidence.LOW, source = name())

    private fun name() = this.name

    private fun find(text: String, needle: String): String? =
        if (text.contains(Regex(needle))) null else null // placeholder; keep version null for speed

    private fun pkg(json: String, dep: String): String? {
        // extremely small heuristic for `"dep": "x.y.z"` in dependencies/devDependencies
        val rx = Regex("\"$dep\"\\s*:\\s*\"([^\"]+)\"")
        return rx.find(json)?.groupValues?.getOrNull(1)
    }

    private fun comp(json: String, dep: String): String? {
        val rx = Regex("\"$dep\"\\s*:\\s*\"([^\"]+)\"")
        return rx.find(json)?.groupValues?.getOrNull(1)
    }

    private fun VirtualFile.safeReadText(): String? =
        try { InputStreamReader(this.inputStream, Charsets.UTF_8).use { BufferedReader(it).readText() } }
        catch (_: Throwable) { null }

    private fun Project.baseDir(): VirtualFile? =
        this.baseDir ?: this.projectFile?.parent
}
