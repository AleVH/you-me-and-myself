package com.youmeandmyself.ai.chat.context

import com.intellij.openapi.vfs.VirtualFile

/**
 * Standalone heuristic filter for context gathering decisions.
 *
 * ## Why This Exists
 *
 * This class was extracted from [ContextAssembler] to solve a critical launch blocker:
 * the heuristic was embedded inside the assembler pipeline and silently dropped ALL
 * gathered context when it didn't recognise the user's message as "code-related".
 *
 * The symptom: the user sees "Context ready in 1629ms" (context was gathered), but
 * the AI responds with "you haven't provided the code" — because the heuristic
 * discarded everything before it reached the prompt.
 *
 * ## Architecture: Attach/Detach Pattern
 *
 * This filter is called OPTIONALLY by [ContextAssembler] as a first-pass pre-check.
 * It is NOT embedded in the pipeline — it sits BEFORE the pipeline entry point.
 *
 * ```
 *   User message
 *       │
 *       ▼
 *   ContextHeuristicFilter.shouldSkipContext()   ◄── this class (optional first gate)
 *       │
 *       ├── true  → skip context gathering entirely (return raw input)
 *       │
 *       └── false → proceed to ContextAssembler pipeline
 *                       │
 *                       ├── kill-switch check (contextEnabled)
 *                       ├── bypass mode check (per-tab dial position)
 *                       ├── IDE context gathering (detectors)
 *                       ├── summary enrichment
 *                       └── prompt assembly
 * ```
 *
 * ## Toggle: On/Off via Settings
 *
 * Controlled by [ContextSettingsState.heuristicFilterEnabled]:
 * - **false (DEFAULT):** Filter is bypassed. Context ALWAYS flows through when
 *   context gathering is enabled. This is the safe launch default — zero false negatives.
 * - **true:** Filter is active. Messages that don't look code-related (e.g. "hello",
 *   "tell me a joke") skip context gathering to save tokens.
 *
 * The toggle lives in: Settings → Tools → YMM Assistant → Context → "Smart context filter"
 *
 * ## How to Remove Entirely
 *
 * If this heuristic proves more trouble than it's worth, removing it is a one-step
 * operation: delete the `if (heuristicEnabled && filter.shouldSkipContext(...))` block
 * in [ContextAssembler.assemble]. The filter class can then be deleted. No other code
 * depends on it (except [ContextAssembler.buildCurrentFileBlock] which uses
 * [refersToCurrentFile] — that one call would need inlining or a local replacement).
 *
 * ## Design Principles
 *
 * 1. **No dependencies on assembler internals** — pure string analysis + VirtualFile check.
 * 2. **All constants co-located** — keyword lists, regex, file extensions are all here.
 * 3. **Testable in isolation** — no project, no services, no DI needed.
 * 4. **Errs on the side of inclusion** — when in doubt, returns false (= gather context).
 *
 * @see ContextAssembler.assemble — the only caller (optional pre-check)
 * @see ContextSettingsState.heuristicFilterEnabled — the on/off toggle
 */
class ContextHeuristicFilter {

    // ── Public API ────────────────────────────────────────────────────────

    /**
     * Should context gathering be SKIPPED for this message?
     *
     * Returns `true` if the heuristic thinks this message does NOT need IDE context.
     * When the filter is active and this returns `true`, [ContextAssembler] returns
     * the user's raw input without gathering any context.
     *
     * Returns `false` (= gather context) when:
     * - The message contains code-related markers (file extensions, code keywords, error terms)
     * - The message references the current editor file ("this file", "explain this file")
     * - A code file is open AND the user uses generic analysis words ("explain", "what does this do")
     * - None of the above, but the message doesn't match known skip patterns either (safe default)
     *
     * @param userInput The user's raw chat message
     * @param editorFile The currently focused file in the editor, or null if none
     * @return true = skip context, false = gather context
     */
    fun shouldSkipContext(userInput: String, editorFile: VirtualFile?): Boolean {
        // If any positive signal is detected, do NOT skip context
        if (isContextLikelyUseful(userInput)) return false
        if (refersToCurrentFile(userInput)) return false

        // If a code file is open in the editor AND the user uses generic analysis words
        // (e.g. "explain", "what does this do"), they're likely asking about the open file.
        // Do NOT skip context in this case.
        val isEditorCodeFile = editorFile?.extension?.lowercase() in CODE_FILE_EXTENSIONS
        if (isEditorCodeFile) {
            val t = userInput.lowercase()
            val isGenericExplain = GENERIC_ANALYSIS_WORDS.any { it in t }
            if (isGenericExplain) return false
        }

        // No positive signals found — heuristic says context is not needed.
        // This is the line that caused the launch blocker when the filter was embedded
        // in the pipeline with no way to disable it. Now it's opt-in only.
        return true
    }

    /**
     * Does the user's message reference the current editor file deictically?
     *
     * Deictic = language that points to something in the physical context rather than
     * naming it explicitly. "This file" means the file open in the editor, not any file.
     *
     * Used by:
     * 1. [shouldSkipContext] — if user references "this file", definitely gather context.
     * 2. [ContextAssembler.buildCurrentFileBlock] — if user references "this file",
     *    include the FULL file content in the prompt (not just a summary).
     *
     * This method stays public even when the heuristic filter is disabled, because
     * [ContextAssembler.buildCurrentFileBlock] always needs it.
     *
     * @param text The user's raw chat message
     * @return true if the message contains phrases like "this file", "explain this file", etc.
     */
    fun refersToCurrentFile(text: String): Boolean {
        val t = text.lowercase()
        return DEICTIC_FILE_PHRASES.any { it in t }
    }

    // ── Private Helpers ──────────────────────────────────────────────────

    /**
     * Does the message contain markers that suggest code-related context would help?
     *
     * Checks (in order of signal strength):
     * 1. Code fences (```) — strong signal, user is pasting/discussing code
     * 2. Error/exception keywords — user is debugging
     * 3. File extension patterns (.kt, .java, .py) — user mentions specific files
     * 4. File path patterns (src/main/Foo.kt) — user references project paths
     * 5. Code keywords (import, class, fun) — user discusses source code
     *
     * Short greetings ("hi", "hello") are explicitly excluded even if they
     * happen to match some pattern — checked LAST so "hi, explain this error"
     * still triggers context.
     *
     * @param text The user's raw input
     * @return true if code-related markers were detected
     */
    private fun isContextLikelyUseful(text: String): Boolean {
        val t = text.lowercase()

        // Code fences are a strong signal — user is pasting or discussing code
        if ("```" in t) return true

        // Error/exception keywords suggest debugging context is needed
        if (ERROR_KEYWORDS.any { it in t }) return true

        // File extensions suggest the user is talking about specific files
        if (FILE_EXTENSION_HINTS.any { it in t }) return true

        // File path patterns (e.g., "src/main/Foo.kt" or "C:\Users\file.py")
        if (FILE_PATH_REGEX.containsMatchIn(t)) return true

        // Code keywords suggest the user is discussing source code
        // Note the trailing spaces on most entries — prevents matching inside normal words
        // (e.g. "import" in "important" is avoided by checking "import ")
        if (CODE_KEYWORDS.any { it in t }) return true

        // Short greetings — definitely no context needed.
        // Checked AFTER all other patterns so "hi, explain this error" still triggers.
        val words = t.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.size <= 3 && GREETINGS.any { it == t || t.startsWith(it) }) return false

        return false
    }

    // ── Constants ────────────────────────────────────────────────────────
    //
    // All heuristic constants live here, co-located with the logic that uses them.
    // Previously scattered across ContextAssembler's companion object (lines 879-965).

    companion object {

        /**
         * File extensions that indicate a code file is open in the editor.
         *
         * Purpose: When a code file is open AND the user uses generic analysis words
         * ("explain", "what does this do"), context is gathered even without explicit
         * code markers in the message — because the user is likely asking about
         * the file they're looking at.
         *
         * This is a SET for O(1) lookup performance (checked on every message).
         */
        private val CODE_FILE_EXTENSIONS = setOf(
            "kt", "kts", "java", "js", "jsx", "ts", "tsx", "py", "php", "rb",
            "go", "rs", "c", "cpp", "h", "cs", "xml", "json", "yml", "yaml"
        )

        /**
         * Generic analysis words that, combined with a code file being open,
         * suggest the user is asking about the open file.
         *
         * Example: user has Foo.kt open and types "explain" → they mean "explain Foo.kt".
         * Without these, the message "explain" alone wouldn't trigger context gathering
         * because it has no explicit code markers.
         */
        private val GENERIC_ANALYSIS_WORDS = listOf(
            "what does this do", "explain", "analyze", "describe"
        )

        /**
         * Error-related keywords that strongly suggest debugging context is needed.
         *
         * When a user mentions errors/exceptions, they almost certainly want the AI
         * to see their code and project structure to help debug.
         */
        private val ERROR_KEYWORDS = listOf(
            "error", "exception", "traceback", "stack trace", "unresolved reference",
            "undefined", "cannot find symbol", "no such method", "classnotfound"
        )

        /**
         * File extension patterns that suggest the user is discussing specific files.
         *
         * Different from CODE_FILE_EXTENSIONS: these are SUBSTRING patterns checked
         * against the message text (e.g., ".kt" matches "check MyClass.kt"), while
         * CODE_FILE_EXTENSIONS are checked against the editor file's extension.
         */
        private val FILE_EXTENSION_HINTS = listOf(
            ".kt", ".kts", ".java", ".js", ".ts", ".tsx", ".py", ".php", ".rb",
            ".go", ".rs", ".cpp", ".c", ".h", ".cs", ".xml", ".json", ".gradle",
            ".yml", ".yaml"
        )

        /**
         * Regex for detecting file path patterns in the user's message.
         *
         * Matches strings like "src/main/Foo.kt" or "C:\Users\file.py" — any path
         * with a separator (/ or \) followed by a filename with a short extension.
         */
        private val FILE_PATH_REGEX = Regex("""[\\/].+\.(\w{1,6})""")

        /**
         * Code keywords that suggest the user is discussing source code.
         *
         * Note: most entries have a TRAILING SPACE to prevent false positives.
         * "import " won't match "important", "class " won't match "classic".
         * The ones with parentheses (require(, include() are already specific enough.
         */
        private val CODE_KEYWORDS = listOf(
            "import ", "package ", "class ", "interface ", "fun ", "def ",
            "require(", "include(", "from ", "new ", "extends ", "implements "
        )

        /**
         * Short greetings that should NOT trigger context gathering on their own.
         *
         * Only suppresses context for very short messages (≤3 words). A message like
         * "hi, can you explain this error" still triggers context because the error
         * keywords are checked first.
         */
        private val GREETINGS = setOf("hi", "hello", "hey", "yo")

        /**
         * Deictic phrases that explicitly reference the current editor file.
         *
         * "Deictic" = language that points to something in the immediate physical context.
         * "This file" means the file the user is looking at in the editor.
         *
         * These trigger TWO things:
         * 1. Context gathering is NOT skipped (even when the heuristic filter is active)
         * 2. The FULL file content is included in the prompt (not just a summary)
         *
         * @see refersToCurrentFile — the method that checks these
         * @see ContextAssembler.buildCurrentFileBlock — includes full content when matched
         */
        private val DEICTIC_FILE_PHRASES = listOf(
            "this file", "explain this file", "walk me through this file",
            "what does this file do", "analyze this file"
        )
    }
}
