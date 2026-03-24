// File: src/main/kotlin/com/youmeandmyself/summary/structure/CodeStructureProvider.kt
package com.youmeandmyself.summary.structure

import com.intellij.openapi.vfs.VirtualFile
import com.youmeandmyself.summary.model.CodeElement

/**
 * Abstraction over code structure detection.
 *
 * The current implementation is [PsiCodeStructureProvider], which uses the
 * IDE's PSI tree for accurate, reliable detection. It requires read access
 * and is unavailable during indexing (dumb mode) — in that window,
 * summarization is simply skipped and raw content is used instead.
 *
 * Consumers obtain an instance through [CodeStructureProviderFactory] and
 * never reference a concrete implementation directly. This allows swapping
 * the detection strategy without touching any consumer code, should a
 * better approach emerge in the future.
 *
 * ## Thread Safety
 *
 * Implementations handle their own thread safety requirements internally.
 * For PSI, this means wrapping access in ReadAction. Callers do not need
 * to worry about which thread they're on.
 */
interface CodeStructureProvider {

    /**
     * Detects code elements (classes, methods, functions) in the given file,
     * scoped by [scope].
     *
     * Returns an empty list if detection is not possible (e.g., binary file,
     * unsupported language within this provider).
     *
     * @param file The virtual file to analyse.
     * @param scope Controls which elements to detect (all, single class, etc.).
     * @return Detected elements, each with populated offsetRange and signature.
     */
    fun detectElements(file: VirtualFile, scope: DetectionScope): List<CodeElement>

    /**
     * Computes a semantic fingerprint hash for a single code element.
     *
     * The hash is built from structural elements that affect behavior
     * (signature, control flow, dependencies, types) and ignores cosmetic
     * changes (parameter names, local variable names, whitespace, comments,
     * formatting). This means a summary is only invalidated when the code's
     * behavior actually changes — not on every keystroke.
     *
     * Each element is hashed independently. Changes to one method do not
     * affect another method's hash within the same file.
     *
     * @param file The virtual file containing the element.
     * @param element The element to hash (must have been detected from [file]).
     * @return Hex-encoded SHA-256 of the semantic fingerprint.
     */
    fun computeElementHash(file: VirtualFile, element: CodeElement): String

    /**
     * Extracts structural context for the given element at the specified level.
     *
     * Returns a bullet-pointed string (e.g., "* Modifiers: public\n* Returns: String")
     * consumed by [SummaryExtractor] via the {structuralContext} placeholder.
     *
     * Returns empty string if no structural context can be extracted.
     *
     * @param file The virtual file containing the element. Needed to re-resolve
     *             the PSI element (PSI elements can become invalid after edits).
     * @param element The element to extract context for.
     * @param level Determines what kind of context to extract (method, class, file).
     * @return Bullet-pointed structural context string, or empty string.
     */
    fun extractStructuralContext(file: VirtualFile, element: CodeElement, level: ElementLevel): String
}
