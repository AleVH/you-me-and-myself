package com.youmeandmyself.ai.chat.context

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.youmeandmyself.dev.Dev
import com.youmeandmyself.summary.model.CodeElement
import com.youmeandmyself.summary.model.CodeElementKind
import com.youmeandmyself.summary.structure.CodeStructureProviderFactory
import com.youmeandmyself.summary.structure.DetectionScope

/**
 * Resolves the current editor state into a structured context: what file is open,
 * where the cursor is, what code element the cursor is inside, and what class
 * contains that element.
 *
 * ## Why This Exists
 *
 * [ContextAssembler] needs to know not just WHICH file is open, but WHERE in the
 * file the user is looking. Without cursor awareness, context is always file-level
 * (wasteful). With it, context can be scoped to the exact method or class the user
 * is working on (precise, fewer tokens, better AI responses).
 *
 * ## How It Works
 *
 * 1. Reads the active editor from [FileEditorManager] (IDE API)
 * 2. Reads the caret offset from [Editor.caretModel] (IDE API)
 * 3. Reads selected text from [Editor.selectionModel] (IDE API)
 * 4. Uses [CodeStructureProviderFactory] to detect all code elements via
 *    the IDE's Structure View API (language-agnostic, PSI-backed)
 * 5. Finds the element whose offset range contains the cursor position
 * 6. Finds the containing class (parent) if the element is a method/property
 *
 * ## IDE Principle
 *
 * ╔══════════════════════════════════════════════════════════════════════╗
 * ║  This class ONLY reads from IDE APIs. It does NOT parse code,      ║
 * ║  detect structure, or do any work that the IDE already does.       ║
 * ║  All structure detection is delegated to JetBrains' Structure      ║
 * ║  View API via CodeStructureProviderFactory.                        ║
 * ║                                                                    ║
 * ║  Any attempt to add regex parsing, custom AST traversal, or       ║
 * ║  language-specific detection here is a VIOLATION of the plugin     ║
 * ║  infrastructure principles. See: Plugin Infrastructure Principles  ║
 * ╚══════════════════════════════════════════════════════════════════════╝
 *
 * ## Thread Safety
 *
 * All PSI reads are wrapped in [ReadAction]. Safe to call from any thread.
 * Returns null-safe results — if anything fails, fields degrade gracefully to null.
 *
 * ## Usage
 *
 * ```kotlin
 * val resolved = EditorElementResolver.resolve(project)
 * if (resolved != null) {
 *     val method = resolved.elementAtCursor  // the method the cursor is in
 *     val clazz = resolved.containingClass   // the class that contains it
 * }
 * ```
 */
object EditorElementResolver {

    private val log = Logger.getInstance(EditorElementResolver::class.java)

    /**
     * Resolve the current editor state into a [ResolvedEditorContext].
     *
     * Returns null if:
     * - No editor is open
     * - No file is associated with the editor
     * - IDE is in dumb mode (PSI unavailable) — cursor and file are still returned,
     *   but element detection is skipped (elementAtCursor = null, containingClass = null)
     *
     * @param project The current IntelliJ project
     * @return Resolved context, or null if no editor is open
     */
    fun resolve(project: Project): ResolvedEditorContext? {
        // Step 1: Get the active text editor (IDE API)
        val editor: Editor = FileEditorManager.getInstance(project).selectedTextEditor ?: run {
            Dev.info(log, "resolver.no_editor")
            return null
        }

        // Step 2: Get the file associated with the editor (IDE API)
        val virtualFile: VirtualFile = editor.virtualFile ?: run {
            Dev.info(log, "resolver.no_file")
            return null
        }

        // Step 3: Read cursor position and selection (IDE API)
        val cursorOffset = editor.caretModel.offset
        val selectedText = editor.selectionModel.selectedText

        Dev.info(log, "resolver.editor_state",
            "file" to virtualFile.name,
            "cursorOffset" to cursorOffset,
            "hasSelection" to (selectedText != null)
        )

        // Step 4: If PSI is unavailable (dumb mode), return file-level context only.
        // Same as the IDE — features that need indexing wait for it to finish.
        if (DumbService.isDumb(project)) {
            Dev.info(log, "resolver.dumb_mode", "file" to virtualFile.name)
            return ResolvedEditorContext(
                file = virtualFile,
                cursorOffset = cursorOffset,
                selectedText = selectedText,
                elementAtCursor = null,
                containingClass = null
            )
        }

        // Step 5: Detect code elements via Structure View API (language-agnostic)
        val provider = CodeStructureProviderFactory.getInstance(project).get()
        if (provider == null) {
            Dev.info(log, "resolver.no_provider", "file" to virtualFile.name)
            return ResolvedEditorContext(
                file = virtualFile,
                cursorOffset = cursorOffset,
                selectedText = selectedText,
                elementAtCursor = null,
                containingClass = null
            )
        }

        // Step 6: Find the element at cursor and its containing class
        val (elementAtCursor, containingClass) = try {
            ReadAction.compute<Pair<CodeElement?, CodeElement?>, Throwable> {
                val elements = provider.detectElements(virtualFile, DetectionScope.All)

                if (elements.isEmpty()) {
                    Dev.info(log, "resolver.no_elements", "file" to virtualFile.name)
                    return@compute Pair(null, null)
                }

                // Find the most specific (smallest) element that contains the cursor.
                // Methods are smaller than classes, so if the cursor is inside a method
                // that is inside a class, we want the method, not the class.
                val elementAtCursor = elements
                    .filter { cursorOffset in it.offsetRange.first..it.offsetRange.last }
                    .minByOrNull { it.offsetRange.last - it.offsetRange.first }

                // Find the containing class: the parent of the element at cursor.
                // If the element IS a class, the containing class is the element itself.
                val containingClass = when {
                    elementAtCursor == null -> null
                    elementAtCursor.kind == CodeElementKind.CLASS ||
                    elementAtCursor.kind == CodeElementKind.INTERFACE ||
                    elementAtCursor.kind == CodeElementKind.OBJECT ||
                    elementAtCursor.kind == CodeElementKind.ENUM -> elementAtCursor
                    elementAtCursor.parentName != null -> elements.firstOrNull { container ->
                        container.name == elementAtCursor.parentName && (
                            container.kind == CodeElementKind.CLASS ||
                            container.kind == CodeElementKind.INTERFACE ||
                            container.kind == CodeElementKind.OBJECT ||
                            container.kind == CodeElementKind.ENUM
                        )
                    }
                    else -> null
                }

                Dev.info(log, "resolver.resolved",
                    "file" to virtualFile.name,
                    "elementAtCursor" to (elementAtCursor?.name ?: "none"),
                    "elementKind" to (elementAtCursor?.kind?.name ?: "none"),
                    "containingClass" to (containingClass?.name ?: "none")
                )

                Pair(elementAtCursor, containingClass)
            }
        } catch (e: Throwable) {
            Dev.warn(log, "resolver.detection_failed", e, "file" to virtualFile.name)
            Pair(null, null)
        }

        return ResolvedEditorContext(
            file = virtualFile,
            cursorOffset = cursorOffset,
            selectedText = selectedText,
            elementAtCursor = elementAtCursor,
            containingClass = containingClass
        )
    }
}

/**
 * The resolved state of the current editor.
 *
 * Contains everything the context assembler needs to scope context to the
 * right level: file, class, or method.
 *
 * ## Nullability Contract
 *
 * - [file] is always non-null (if this object exists, an editor is open)
 * - [cursorOffset] is always available
 * - [selectedText] is null if nothing is selected
 * - [elementAtCursor] is null if:
 *   - PSI is unavailable (dumb mode)
 *   - Cursor is between elements (in whitespace, imports, etc.)
 *   - Structure View doesn't expose this element type
 * - [containingClass] is null if:
 *   - elementAtCursor is null
 *   - elementAtCursor is a top-level function/property (no parent class)
 *
 * When elementAtCursor is null, the assembler falls back to file-level context.
 * This is a graceful degradation, not an error.
 *
 * @param file The open file
 * @param cursorOffset Caret position (0-based character offset from file start)
 * @param selectedText Selected text, or null if no selection
 * @param elementAtCursor The most specific code element containing the cursor (method > class)
 * @param containingClass The class containing the cursor element, or the element itself if it's a class
 */
data class ResolvedEditorContext(
    val file: VirtualFile,
    val cursorOffset: Int,
    val selectedText: String?,
    val elementAtCursor: CodeElement?,
    val containingClass: CodeElement?
)
