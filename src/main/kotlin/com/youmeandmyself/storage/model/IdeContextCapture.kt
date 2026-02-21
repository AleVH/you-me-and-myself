package com.youmeandmyself.storage.model

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.module.ModuleUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.youmeandmyself.dev.Dev
import com.intellij.openapi.diagnostic.Logger

/**
 * Captures the current IDE state for storage alongside an exchange.
 *
 * Called at chat-send time (BEFORE the HTTP request) to snapshot what
 * the developer was working on at the moment they asked their question.
 * The capture happens in the "time gap" while the request is about to
 * be sent — no extra latency for the user.
 *
 * All capture methods are best-effort — if any part fails, that field
 * is null and the rest still works.
 *
 * ## What Gets Captured
 *
 * - The active (focused) editor tab
 * - ALL other open editor tabs (devs always have many open)
 * - Language and module of the active file
 * - Current git branch
 *
 * ## Thread Safety
 *
 * Must be called on the EDT (Event Dispatch Thread) for reliable results,
 * since it accesses editor and file manager state. When called from
 * GenericLlmProvider.chat(), the caller (ChatPanel) is already on EDT.
 *
 * ## Usage
 *
 * ```kotlin
 * // In GenericLlmProvider, before the HTTP request:
 * val context = IdeContextCapture.capture(project)
 * // context.activeFile = "src/main/kotlin/com/example/UserService.kt"
 * // context.openFiles = ["AuthService.kt", "build.gradle.kts", "README.md"]
 * // context.language = "Kotlin"
 * // context.module = "app"
 * // context.branch = "feature/auth-refactor"
 * ```
 */
object IdeContextCapture {

    private val log = Logger.getInstance(IdeContextCapture::class.java)

    /**
     * Capture current IDE state.
     *
     * Must be called on the EDT (Event Dispatch Thread) for reliable results,
     * since it accesses editor and file manager state.
     *
     * @param project The IntelliJ project
     * @return Captured context (fields may be null if not available)
     */
    fun capture(project: Project): IdeContext {
        return try {
            val fileEditorManager = FileEditorManager.getInstance(project)
            val activeFile = fileEditorManager.selectedFiles.firstOrNull()

            // Capture ALL open editor tabs, not just the active one.
            // Devs context-switch constantly — the question might be about
            // a file that's open but not focused.
            val allOpenFiles = fileEditorManager.openFiles.toList()
            val otherOpenFiles = allOpenFiles
                .filter { it != activeFile }
                .mapNotNull { getRelativePath(it, project) }

            IdeContext(
                activeFile = activeFile?.let { getRelativePath(it, project) },
                openFiles = otherOpenFiles.takeIf { it.isNotEmpty() },
                language = activeFile?.let { getLanguage(it) },
                module = activeFile?.let { getModule(it, project) },
                branch = getGitBranch(project)
            )
        } catch (e: Exception) {
            Dev.warn(log, "context.capture_failed", e)
            IdeContext.empty()
        }
    }

    /**
     * Get file path relative to project root.
     */
    private fun getRelativePath(file: VirtualFile, project: Project): String? {
        return try {
            val basePath = project.basePath ?: return file.path
            if (file.path.startsWith(basePath)) {
                file.path.removePrefix(basePath).removePrefix("/")
            } else {
                file.path
            }
        } catch (e: Exception) {
            file.path
        }
    }

    /**
     * Detect the programming language from the file.
     */
    private fun getLanguage(file: VirtualFile): String? {
        return try {
            file.fileType.name.takeIf { it != "UNKNOWN" }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Get the IntelliJ module this file belongs to.
     */
    private fun getModule(file: VirtualFile, project: Project): String? {
        return try {
            ModuleUtil.findModuleForFile(file, project)?.name
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Get the current git branch.
     *
     * Uses IntelliJ's VCS integration via reflection to avoid hard dependency
     * on the Git4Idea plugin. Returns null if the project isn't under git
     * or if VCS info is unavailable.
     */
    private fun getGitBranch(project: Project): String? {
        return try {
            val gitRepoManager = project.getService(
                Class.forName("git4idea.repo.GitRepositoryManager") as Class<*>
            )
            val getRepositories = gitRepoManager.javaClass.getMethod("getRepositories")
            val repos = getRepositories.invoke(gitRepoManager) as? List<*>
            val firstRepo = repos?.firstOrNull() ?: return null
            val getCurrentBranch = firstRepo.javaClass.getMethod("getCurrentBranch")
            val branch = getCurrentBranch.invoke(firstRepo) ?: return null
            val getName = branch.javaClass.getMethod("getName")
            getName.invoke(branch) as? String
        } catch (e: Exception) {
            // Git plugin not available or no git repo — that's fine
            null
        }
    }
}