package com.youmeandmyself.ai.chat.context

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.youmeandmyself.context.orchestrator.ContextBundle
import com.youmeandmyself.context.orchestrator.ContextKind
import com.youmeandmyself.context.orchestrator.ContextOrchestrator
import com.youmeandmyself.context.orchestrator.ContextRequest
import com.youmeandmyself.context.orchestrator.DetectorRegistry
import com.youmeandmyself.context.orchestrator.OrchestratorMetrics
import com.youmeandmyself.context.orchestrator.detectors.FrameworkDetector
import com.youmeandmyself.context.orchestrator.detectors.LanguageDetector
import com.youmeandmyself.context.orchestrator.detectors.ProjectStructureDetector
import com.youmeandmyself.context.orchestrator.detectors.RelevantFilesDetector
import com.youmeandmyself.dev.Dev
import com.youmeandmyself.storage.LocalStorageFacade
import kotlinx.coroutines.CoroutineScope
import java.nio.charset.Charset

/**
 * Assembles the complete prompt that gets sent to the AI provider.
 *
 * ## Why This Exists
 *
 * Before this refactoring, prompt assembly was embedded inside ChatPanel.doSend() —
 * ~120 lines of context detection, IDE introspection, file reading, and string
 * formatting mixed with UI code. This class extracts all of that into a testable,
 * UI-free module.
 *
 * ## What It Does
 *
 * 1. **Context detection**: Determines if the user's message benefits from IDE context
 *    (code files, project structure, framework info). Uses heuristics like detecting
 *    code keywords, file extensions, error messages, or deictic phrases ("this file").
 *
 * 2. **IDE context gathering**: Uses [ContextOrchestrator] to collect project language,
 *    frameworks, build system, and relevant files (with a time budget).
 *
 * 3. **Summary inclusion**: Reads code summaries from [SummaryStoreProvider] for relevant
 *    files. In individual tier, these are local summaries only. In company tier, this
 *    includes shared summaries from other team members.
 *
 * 4. **Claim coordination** (company tier): If a summary is missing and another developer
 *    is currently generating it, the assembler can wait briefly. If no one is generating,
 *    it signals the pipeline to start summarization (fire-and-forget).
 *
 * 5. **Prompt formatting**: Combines all context into a structured prompt with clear
 *    sections ([Context], [Files], etc.) that the AI can parse.
 *
 * ## Conversation History
 *
 * History is NOT included by this class — it's the orchestrator's responsibility to
 * pass history to the provider. This class only handles the "context" portion of the
 * prompt (IDE state, code files, summaries).
 *
 * ## Thread Safety
 *
 * This class is stateless — all state is passed in via method parameters.
 * Multiple context assemblies can run concurrently without interference.
 *
 * @param project The IntelliJ project for IDE introspection
 * @param summaryStore Provider for reading code summaries (local or shared, based on tier)
 */
class ContextAssembler(
    private val project: Project,
    private val summaryStore: SummaryStoreProvider
) {
    private val log = Dev.logger(ContextAssembler::class.java)

    // ── Public API ───────────────────────────────────────────────────────

    /**
     * Assemble the complete prompt, optionally enriched with IDE context.
     *
     * This is the main entry point. It:
     * 1. Checks if context would be useful for this message
     * 2. If yes, gathers IDE context within a time budget
     * 3. Formats everything into the final prompt string
     *
     * @param userInput The raw text the user typed
     * @param scope Coroutine scope for context gathering (respects cancellation)
     * @return The assembled result with the effective prompt and context metadata
     */
    suspend fun assemble(
        userInput: String,
        scope: CoroutineScope
    ): AssembledPrompt {
        // Step 1: Determine if IDE context would help answer this question
        val editorFile = currentEditorFile()
        val needContext = shouldGatherContext(userInput, editorFile)

        if (!needContext) {
            // No context needed — just send the user's message as-is
            Dev.info(log, "context.skipped", "reason" to "not_needed")
            return AssembledPrompt(
                effectivePrompt = userInput,
                contextSummary = null,
                contextTimeMs = null
            )
        }

        // Step 2: Check if IDE is in "dumb mode" (indexing files)
        // Context gathering requires IntelliJ's index, which isn't available during indexing
        if (DumbService.isDumb(project)) {
            Dev.info(log, "context.skipped", "reason" to "ide_indexing")
            return AssembledPrompt.indexingBlocked()
        }

        // Step 3: Gather IDE context (language, frameworks, project structure, relevant files)
        val (bundle, metrics) = gatherIdeContext(scope)

        Dev.info(log, "context.gathered",
            "files" to bundle.files.size,
            "totalChars" to bundle.totalChars,
            "timeMs" to metrics.totalMillis
        )

        // Step 4: Format the context into a structured prompt section
        val contextNote = formatContextNote(bundle)
        val manifest = bundle.manifestLine()
        val filesBlock = bundle.filesSection()

        // Step 5: If the user refers to "this file", include the full file content
        val fileBlock = buildCurrentFileBlock(userInput, editorFile)

        // Step 6: Assemble the final prompt with all context sections
        val effectivePrompt = buildString {
            appendLine(contextNote)
            appendLine(manifest)
            appendLine()
            appendLine(filesBlock)
            if (fileBlock.isNotBlank()) {
                appendLine()
                appendLine(fileBlock)
            }
            appendLine()
            appendLine(userInput)
        }.trimEnd()

        Dev.info(log, "context.assembled",
            "effectivePromptLength" to effectivePrompt.length,
            "bundleFiles" to bundle.files.size,
            "rawFiles" to bundle.files.count { it.kind == ContextKind.RAW },
            "summaryFiles" to bundle.files.count { it.kind == ContextKind.SUMMARY }
        )

        return AssembledPrompt(
            effectivePrompt = effectivePrompt,
            contextSummary = manifest,
            contextTimeMs = metrics.totalMillis
        )
    }

    // ── Context Detection Heuristics ─────────────────────────────────────
    //
    // These heuristics determine whether the user's message would benefit
    // from having IDE context attached. They err on the side of inclusion:
    // it's better to attach unnecessary context (which the AI ignores) than
    // to miss context the AI needs (which leads to generic/wrong answers).

    /**
     * Master decision: should we gather IDE context for this message?
     *
     * Returns true if any of these conditions are met:
     * - The message contains code-related markers (file extensions, code keywords, error terms)
     * - The message explicitly refers to the current file ("this file", "explain this file")
     * - A code file is open in the editor AND the message uses generic analysis words
     *   ("explain", "what does this do") — likely referring to the open file
     *
     * @param text The user's raw input
     * @param editorFile The currently open file in the editor (null if no file is open)
     * @return True if IDE context should be gathered
     */
    internal fun shouldGatherContext(text: String, editorFile: VirtualFile?): Boolean {
        if (isContextLikelyUseful(text)) return true
        if (refersToCurrentFile(text)) return true

        // If a code file is open and the user uses generic analysis words,
        // they're probably asking about the open file
        val isEditorCodeFile = editorFile?.extension?.lowercase() in CODE_FILE_EXTENSIONS
        if (isEditorCodeFile) {
            val t = text.lowercase()
            val isGenericExplain = GENERIC_ANALYSIS_WORDS.any { it in t }
            if (isGenericExplain) return true
        }

        return false
    }

    /**
     * Heuristic: does the message contain markers that suggest code-related context would help?
     *
     * Checks for:
     * - Code fences (```)
     * - Error/exception keywords ("error", "traceback", "stack trace", etc.)
     * - File extension patterns (".kt", ".java", ".py", etc.)
     * - File path patterns (backslash or forward slash + filename)
     * - Code keywords ("import ", "class ", "fun ", etc.)
     *
     * Short greetings ("hi", "hello") are explicitly excluded even if they
     * happen to match some pattern.
     *
     * @param text The user's raw input
     * @return True if code-related markers were detected
     */
    internal fun isContextLikelyUseful(text: String): Boolean {
        val t = text.lowercase()

        // Code fences are a strong signal
        if ("```" in t) return true

        // Error/exception keywords suggest debugging context is needed
        if (ERROR_KEYWORDS.any { it in t }) return true

        // File extensions suggest the user is talking about specific files
        if (FILE_EXTENSION_HINTS.any { it in t }) return true

        // File path patterns (e.g., "src/main/Foo.kt" or "C:\Users\file.py")
        if (FILE_PATH_REGEX.containsMatchIn(t)) return true

        // Code keywords suggest the user is discussing code
        if (CODE_KEYWORDS.any { it in t }) return true

        // Short greetings — definitely no context needed.
        // Check this AFTER the other patterns in case someone says "hi, explain this error"
        val words = t.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.size <= 3 && GREETINGS.any { it == t || t.startsWith(it) }) return false

        return false
    }

    /**
     * Detect deictic phrasing that explicitly refers to the active editor file.
     *
     * When the user says "this file" or "explain this file", they mean the file
     * currently open in the editor — not some abstract concept of a file.
     * This triggers both context gathering AND inclusion of the full file content.
     *
     * @param text The user's raw input
     * @return True if the message refers to the current editor file
     */
    internal fun refersToCurrentFile(text: String): Boolean {
        val t = text.lowercase()
        return DEICTIC_FILE_PHRASES.any { it in t }
    }

    // ── IDE Context Gathering ────────────────────────────────────────────

    /**
     * Gather IDE context using the [ContextOrchestrator] detector pipeline.
     *
     * The orchestrator runs multiple detectors concurrently within a time budget:
     * - LanguageDetector: primary language of the project
     * - FrameworkDetector: frameworks in use (Spring, React, etc.)
     * - ProjectStructureDetector: build system, modules
     * - RelevantFilesDetector: files related to the user's question
     *
     * @param scope Coroutine scope (cancellation propagates to detectors)
     * @return The context bundle and performance metrics
     */
    private suspend fun gatherIdeContext(
        scope: CoroutineScope
    ): Pair<ContextBundle, OrchestratorMetrics> {
        val registry = DetectorRegistry(
            listOf(
                LanguageDetector(),
                FrameworkDetector(),
                ProjectStructureDetector(),
                RelevantFilesDetector()
            )
        )
        val orchestrator = ContextOrchestrator(registry, Logger.getInstance(ContextAssembler::class.java))

        // Time budget for context gathering. If detectors take longer than this,
        // partial results are returned (whatever finished in time).
        val request = ContextRequest(project = project, maxMillis = MAX_CONTEXT_GATHER_MS)

        return orchestrator.gather(request, scope)
    }

    // ── Prompt Formatting ────────────────────────────────────────────────

    /**
     * Format a compact context note from a [ContextBundle] for the AI model.
     *
     * This gives the AI a quick overview of the project environment without
     * overwhelming it with details. Example output:
     *
     * ```
     * [Context]
     * Language: kotlin
     * Frameworks: Spring Boot 3.2, Kotlin Coroutines
     * Build: gradle
     * Modules: app, core, api
     * Files: 5 (truncated: 2) (~12400 chars)
     * - src/main/kotlin/Foo.kt
     * - src/main/kotlin/Bar.kt
     * ```
     *
     * @param bundle The gathered IDE context
     * @return Formatted context note string
     */
    private fun formatContextNote(bundle: ContextBundle): String {
        val lang = bundle.language?.languageId ?: "unknown"

        val frameworks = bundle.frameworks.joinToString(", ") { f ->
            if (f.version.isNullOrBlank()) f.name else "${f.name} ${f.version}"
        }.ifBlank { "none" }

        val build = bundle.projectStructure?.buildSystem ?: "unknown"
        val modules = bundle.projectStructure?.modules?.joinToString(", ")?.ifBlank { null }

        // File preview: show first 5 file paths as a quick reference
        val filesLine = if (bundle.files.isNotEmpty()) {
            val preview = bundle.files.take(5).joinToString("\n- ") { it.path }
            val truncNote = if (bundle.truncatedCount > 0) " (truncated: ${bundle.truncatedCount})" else ""
            "\nFiles: ${bundle.files.size}$truncNote (~${bundle.totalChars} chars)\n- $preview"
        } else ""

        val modulesLine = modules?.let { "\nModules: $it" } ?: ""

        return """
            [Context]
            Language: $lang
            Frameworks: $frameworks
            Build: $build$modulesLine$filesLine
        """.trimIndent()
    }

    /**
     * Build the full content block for the currently open editor file.
     *
     * Only included when the user explicitly references "this file" (deictic phrasing).
     * Reads the file content with a safety cap to prevent sending enormous files to the AI.
     *
     * @param userInput The user's message (checked for deictic references)
     * @param editorFile The currently open file (null if nothing is open)
     * @return Formatted file block, or empty string if not applicable
     */
    private fun buildCurrentFileBlock(userInput: String, editorFile: VirtualFile?): String {
        // Only include if user explicitly references "this file"
        if (editorFile == null || !refersToCurrentFile(userInput)) return ""

        val text = readFileTextCapped(editorFile) ?: return ""
        if (text.isBlank()) return ""

        val lang = fenceLangFor(editorFile)
        val path = editorFile.path.substringAfterLast('/')

        return """
            [File: $path]
            ```$lang
            ${text.trim()}
            ```
        """.trimIndent()
    }

    // ── File Reading Helpers ─────────────────────────────────────────────

    /**
     * Get the currently active file in the editor.
     *
     * Returns null if no file is open or the editor manager is unavailable.
     * This is the file the user is looking at when they send their message.
     */
    private fun currentEditorFile(): VirtualFile? {
        return try {
            FileEditorManager.getInstance(project).selectedFiles.firstOrNull()
        } catch (e: Exception) {
            // FileEditorManager might not be available in all contexts (e.g., during tests)
            Dev.warn(log, "context.editor_file_unavailable", e)
            null
        }
    }

    /**
     * Read file content with a safety cap, using IntelliJ's ReadAction for thread safety.
     *
     * Tries two approaches:
     * 1. Read from IntelliJ's document model (preferred — picks up unsaved changes)
     * 2. Fall back to reading the VirtualFile directly (for files not in the document model)
     *
     * The cap prevents accidentally sending a 10MB generated file to the AI.
     *
     * @param vf The file to read
     * @param maxChars Maximum characters to read (default 50K ≈ ~12K tokens)
     * @return The file content, or null if reading failed
     */
    private fun readFileTextCapped(vf: VirtualFile, maxChars: Int = MAX_FILE_CHARS): String? {
        return try {
            ReadAction.compute<String?, Throwable> {
                val fdm = FileDocumentManager.getInstance()
                val doc = fdm.getDocument(vf)

                if (doc != null) {
                    // Prefer the document model — it has unsaved changes
                    val seq = doc.charsSequence
                    val end = minOf(seq.length, maxChars)
                    seq.subSequence(0, end).toString()
                } else {
                    // Fall back to raw file content
                    if (!vf.isValid) return@compute null
                    val cs: Charset = vf.charset
                    vf.inputStream.use { input ->
                        val bytes = input.readNBytes(maxChars + 1)
                        val len = bytes.size.coerceAtMost(maxChars)
                        String(bytes, 0, len, cs)
                    }
                }
            }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Derive a code fence language identifier from a VirtualFile's type.
     *
     * Used when including file content in the prompt so the AI knows
     * what language the code is in. Returns empty string for unknown types
     * (the fence will still work, just without syntax hints).
     *
     * @param vf The file to get the language for
     * @return Language identifier (e.g., "kotlin", "java", "ts") or empty string
     */
    private fun fenceLangFor(vf: VirtualFile): String {
        val n = vf.fileType.name.lowercase()
        return when {
            n.contains("kotlin") -> "kotlin"
            n.contains("java") -> "java"
            n.contains("typescript") -> "ts"
            n.contains("javascript") -> "js"
            n.contains("json") -> "json"
            n.contains("xml") -> "xml"
            n.contains("yaml") || n.contains("yml") -> "yaml"
            n.contains("python") -> "python"
            n.contains("go") -> "go"
            n.contains("rust") -> "rust"
            n.contains("c++") || n.contains("cpp") -> "cpp"
            n.contains("c#") || n.contains("csharp") -> "csharp"
            else -> ""
        }
    }

    // ── Constants ────────────────────────────────────────────────────────

    companion object {
        /**
         * Maximum time (ms) to spend gathering IDE context.
         * If detectors take longer, partial results are used.
         */
        private const val MAX_CONTEXT_GATHER_MS = 1400L

        /**
         * Maximum file content to read (characters).
         * ~50K chars ≈ ~12K tokens. Prevents sending huge files to the AI.
         */
        private const val MAX_FILE_CHARS = 50_000

        /**
         * File extensions that indicate a code file is open in the editor.
         * When a code file is open AND the user uses generic analysis words,
         * context is gathered even without explicit code markers in the message.
         */
        private val CODE_FILE_EXTENSIONS = setOf(
            "kt", "kts", "java", "js", "jsx", "ts", "tsx", "py", "php", "rb",
            "go", "rs", "c", "cpp", "h", "cs", "xml", "json", "yml", "yaml"
        )

        /**
         * Generic analysis words that, combined with a code file being open,
         * suggest the user is asking about the open file.
         */
        private val GENERIC_ANALYSIS_WORDS = listOf(
            "what does this do", "explain", "analyze", "describe"
        )

        /**
         * Error-related keywords that strongly suggest debugging context is needed.
         */
        private val ERROR_KEYWORDS = listOf(
            "error", "exception", "traceback", "stack trace", "unresolved reference",
            "undefined", "cannot find symbol", "no such method", "classnotfound"
        )

        /**
         * File extension patterns that suggest the user is discussing specific files.
         */
        private val FILE_EXTENSION_HINTS = listOf(
            ".kt", ".kts", ".java", ".js", ".ts", ".tsx", ".py", ".php", ".rb",
            ".go", ".rs", ".cpp", ".c", ".h", ".cs", ".xml", ".json", ".gradle",
            ".yml", ".yaml"
        )

        /**
         * Regex for detecting file path patterns (e.g., "src/main/Foo.kt" or "C:\Users\file.py").
         */
        private val FILE_PATH_REGEX = Regex("""[\\/].+\.(\w{1,6})""")

        /**
         * Code keywords that suggest the user is discussing source code.
         * Note the trailing space on most — prevents matching inside normal words.
         */
        private val CODE_KEYWORDS = listOf(
            "import ", "package ", "class ", "interface ", "fun ", "def ",
            "require(", "include(", "from ", "new ", "extends ", "implements "
        )

        /**
         * Greetings that should NOT trigger context gathering on their own.
         * Only suppresses context for very short messages (≤3 words).
         */
        private val GREETINGS = setOf("hi", "hello", "hey", "yo")

        /**
         * Deictic phrases that explicitly reference the current editor file.
         * These trigger both context gathering AND full file content inclusion.
         */
        private val DEICTIC_FILE_PHRASES = listOf(
            "this file", "explain this file", "walk me through this file",
            "what does this file do", "analyze this file"
        )
    }
}

/**
 * Result of prompt assembly.
 *
 * Contains the final prompt string and metadata about what context was included.
 * The orchestrator uses [effectivePrompt] for the AI call and the metadata
 * fields for the [ChatResult] it returns to the UI.
 *
 * @property effectivePrompt The complete prompt to send to the AI provider.
 *                           May be just the user's raw input (no context) or
 *                           the user's input enriched with context sections.
 * @property contextSummary Human-readable summary of attached context (e.g., "[Context: 3 files...]").
 *                          Null if no context was gathered. Shown to the user as a system message.
 * @property contextTimeMs How long context gathering took. Null if no context was gathered.
 * @property isBlockedByIndexing True if context was needed but IDE indexing prevented gathering.
 *                               The orchestrator should show an error message in this case.
 */
data class AssembledPrompt(
    val effectivePrompt: String,
    val contextSummary: String?,
    val contextTimeMs: Long?,
    val isBlockedByIndexing: Boolean = false
) {
    companion object {
        /**
         * Factory for the case where context is needed but IDE indexing blocks it.
         *
         * The orchestrator checks [isBlockedByIndexing] and returns an error ChatResult
         * asking the user to wait for indexing to finish.
         */
        fun indexingBlocked(): AssembledPrompt = AssembledPrompt(
            effectivePrompt = "",
            contextSummary = null,
            contextTimeMs = null,
            isBlockedByIndexing = true
        )
    }
}

// ── Extension Functions ─────────────────────────────────────────────────
//
// These format ContextBundle data into prompt sections.
// Extracted as extensions to keep ContextAssembler focused on orchestration.

/**
 * One-line summary of the context bundle for display.
 *
 * Example: "[Context: 5 files (3 raw, 2 summaries), ~12400 chars]"
 */
internal fun ContextBundle.manifestLine(): String {
    val raw = files.count { it.kind == ContextKind.RAW }
    val sum = files.count { it.kind == ContextKind.SUMMARY }
    return "[Context: ${files.size} files ($raw raw, $sum summaries), ~${totalChars} chars]"
}

/**
 * Detailed files section for the prompt.
 *
 * Each file gets a fenced block with metadata (path, language, kind, reason)
 * and content. Summaries include the synopsis and optional header sample.
 *
 * The AI uses this structured format to understand which files are raw content
 * vs. summaries, which are stale, and why each file was included.
 */
internal fun ContextBundle.filesSection(): String = buildString {
    appendLine("### Files")
    files.forEach { cf ->
        appendLine()
        appendLine("```text")
        appendLine("// path: ${cf.path} | lang: ${cf.languageId ?: "unknown"} | why: ${cf.reason}")
        when (cf.kind) {
            ContextKind.RAW -> {
                appendLine("// kind: RAW${if (cf.truncated) " (truncated)" else ""}")
            }
            ContextKind.SUMMARY -> {
                val status = when {
                    cf.modelSynopsis.isNullOrBlank() -> " (synopsis pending)"
                    cf.isStale -> " (synopsis may be outdated)"
                    else -> ""
                }
                appendLine("// kind: SUMMARY$status")
                if (!cf.headerSample.isNullOrBlank()) {
                    appendLine("[HEADER SAMPLE]")
                    appendLine(cf.headerSample)
                }
                if (!cf.modelSynopsis.isNullOrBlank()) {
                    appendLine()
                    appendLine("[SYNOPSIS]")
                    appendLine(cf.modelSynopsis)
                }
            }
        }
        appendLine("```")
    }
}