package com.youmeandmyself.storage.model

/**
 * Snapshot of the IDE state at the moment a chat prompt was sent.
 *
 * Captured automatically — the developer doesn't need to do anything.
 * Stored in SQLite for filtering ("show me all chats while editing UserService.kt")
 * and context-aware features ("you asked about this file before").
 *
 * ## What Gets Captured
 *
 * - Active file: which file was in the focused editor tab
 * - Open files: all other files open in editor tabs (devs always have many)
 * - Language: the active file's programming language
 * - Module: which IntelliJ module the active file belongs to
 * - Branch: current git branch (if under version control)
 *
 * ## Why This Matters
 *
 * Developers context-switch constantly. When searching for a past conversation,
 * knowing "I was working on the auth module, on the feature/login branch"
 * massively narrows the search space. And knowing which files were open
 * (not just active) captures the broader working context — a question
 * about authentication might come while looking at UserController.kt
 * but AuthService.kt is open in another tab.
 *
 * ## Storage
 *
 * - activeFile, language, module, branch → individual SQLite columns (queryable)
 * - openFiles → comma-separated TEXT column in SQLite (same pattern as code_languages)
 * - Full context also saved in JSONL exchange record for completeness
 *
 * @property activeFile Path of the focused editor tab (relative to project root)
 * @property openFiles Paths of all other open editor tabs (relative to project root)
 * @property language Programming language of the active file (e.g., "Kotlin", "Java")
 * @property module IntelliJ module name (e.g., "app", "core", "storage")
 * @property branch Current git branch (e.g., "main", "feature/token-tracking")
 */
data class IdeContext(
    val activeFile: String? = null,
    val openFiles: List<String>? = null,
    val language: String? = null,
    val module: String? = null,
    val branch: String? = null
) {
    /**
     * Check if any context was captured.
     * May be empty if no editor is open or project isn't under VCS.
     */
    val isEmpty: Boolean
        get() = activeFile == null && openFiles.isNullOrEmpty() &&
                language == null && module == null && branch == null

    companion object {
        /** No context available. */
        fun empty() = IdeContext()
    }
}