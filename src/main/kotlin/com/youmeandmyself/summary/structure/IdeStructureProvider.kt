// File: src/main/kotlin/com/youmeandmyself/summary/structure/IdeStructureProvider.kt
package com.youmeandmyself.summary.structure

import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.structureView.TreeBasedStructureViewBuilder
import com.intellij.ide.util.treeView.smartTree.TreeElement
import com.intellij.lang.LanguageStructureViewBuilder
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiQualifiedNamedElement
import com.intellij.psi.PsiWhiteSpace
import com.youmeandmyself.dev.Dev
import com.youmeandmyself.summary.model.CodeElement
import com.youmeandmyself.summary.model.CodeElementKind
import java.security.MessageDigest

/**
 * Language-agnostic implementation of [CodeStructureProvider].
 *
 * ============================================================================
 * !! CRITICAL DESIGN PRINCIPLE — DO NOT VIOLATE !!
 * ============================================================================
 *
 * This plugin NEVER performs its own code structure analysis. It relies
 * ENTIRELY on the JetBrains IDE's built-in systems:
 *
 * - **Structure View API** for element detection (classes, methods, etc.)
 * - **PSI tree** (as provided and built by the IDE) for text ranges and content
 * - **Generic PSI interfaces** (PsiNamedElement, PsiComment, PsiWhiteSpace)
 *   for language-agnostic element inspection
 *
 * Any code that imports language-specific PSI classes (e.g., KtFile,
 * KtClassOrObject, PsiJavaFile, PsiClass, PsiMethod, KtNamedFunction)
 * or branches on language type constitutes a **FLAGRANT VIOLATION** of
 * the plugin's foundational principles.
 *
 * The IDE's PSI is rock solid — it powers code completion, refactoring,
 * inspections, and navigation for every supported language. If the IDE
 * can parse it, we can consume it. We do NOT reinvent the wheel.
 *
 * If the IDE doesn't support a language (no Structure View registered),
 * we skip summarization for that file. We do NOT attempt any fallback.
 * No regex, no manual parsing, nothing.
 * ============================================================================
 *
 * ## How It Works
 *
 * 1. The IDE parses the file and builds its PSI tree (already done by IDE).
 * 2. The language plugin registers a StructureViewFactory that curates
 *    the PSI tree into a hierarchical structure (classes -> methods).
 * 3. We ask the IDE for that curated tree via [LanguageStructureViewBuilder].
 * 4. We walk the tree, mapping each element to a [CodeElement].
 * 5. For hashing, we read the PsiElement's text, strip whitespace/comments,
 *    and compute SHA-256. This is CONSUMING IDE data, not duplicating IDE work.
 *
 * ## Hashing Trade-Off
 *
 * The generic fingerprint (strip whitespace + comments, hash the rest) is
 * slightly over-eager compared to a fully semantic, language-specific hash.
 * For example, renaming a local variable will change the hash even though
 * it doesn't affect behavior. This is acceptable:
 * - We may invalidate slightly more often than strictly necessary
 * - But we NEVER serve stale summaries
 * - And we remain 100% language-agnostic
 *
 * ## Thread Safety
 *
 * All PSI access is wrapped in [ReadAction.compute]. Safe from any thread.
 *
 * @param project The IntelliJ project context.
 */
class IdeStructureProvider(private val project: Project) : CodeStructureProvider {

    private val log = Logger.getInstance(IdeStructureProvider::class.java)

    // ==================== detectElements ====================

    /**
     * Detects code elements in the given file using the IDE's Structure View.
     *
     * The Structure View is the same tree that powers the IDE's Structure
     * tool window. Every language plugin provides one. We just consume it.
     */
    override fun detectElements(file: VirtualFile, scope: DetectionScope): List<CodeElement> {
        return try {
            ReadAction.compute<List<CodeElement>, Throwable> {
                val psiFile = PsiManager.getInstance(project).findFile(file)
                if (psiFile == null) {
                    Dev.info(log, "structure.ide.no_psi_file",
                        "path" to file.path,
                        "reason" to "PsiManager returned null"
                    )
                    return@compute emptyList()
                }

                // Ask the IDE for the curated structure view of this file.
                // This is language-agnostic: the IDE's language plugin provides it.
                val builder = LanguageStructureViewBuilder.getInstance()
                    .getStructureViewBuilder(psiFile)

                if (builder == null) {
                    Dev.info(log, "structure.ide.no_structure_view",
                        "path" to file.path,
                        "language" to psiFile.language.id,
                        "reason" to "No StructureViewBuilder registered for this language — skipping"
                    )
                    return@compute emptyList()
                }

                if (builder !is TreeBasedStructureViewBuilder) {
                    Dev.info(log, "structure.ide.non_tree_builder",
                        "path" to file.path,
                        "builderType" to builder.javaClass.simpleName,
                        "reason" to "Builder is not tree-based — cannot walk structure"
                    )
                    return@compute emptyList()
                }

                val model = builder.createStructureViewModel(null)
                try {
                    val root = model.root

                    val elements = mutableListOf<CodeElement>()
                    walkStructureTree(root, scope, elements, depth = 0, parentName = null)

                    Dev.info(log, "structure.ide.detected",
                        "path" to file.path,
                        "language" to psiFile.language.id,
                        "scope" to scope.javaClass.simpleName,
                        "elementCount" to elements.size,
                        "elements" to elements.map { "${it.kind}:${it.name}" }.joinToString(", ")
                    )

                    elements
                } finally {
                    model.dispose()
                }
            }
        } catch (e: Throwable) {
            Dev.warn(log, "structure.ide.detection_error", e,
                "path" to file.path,
                "scope" to scope.javaClass.simpleName
            )
            emptyList()
        }
    }

    /**
     * Recursively walks the IDE's Structure View tree, mapping elements to [CodeElement].
     *
     * The tree hierarchy is defined by the IDE's language plugin — we NEVER
     * interpret or override it. Typically:
     * - Depth 0: root (the file itself) — skipped, we only walk its children
     * - Depth 1: containers (classes, interfaces, objects, top-level functions)
     * - Depth 2+: members (methods, properties, nested elements)
     *
     * The IDE decides what appears in the structure view. We just consume it.
     */
    private fun walkStructureTree(
        treeElement: TreeElement,
        scope: DetectionScope,
        result: MutableList<CodeElement>,
        depth: Int,
        parentName: String?
    ) {
        // Only StructureViewTreeElement gives us getValue() -> PsiElement
        val structElement = treeElement as? StructureViewTreeElement
        val psiElement = structElement?.value as? PsiElement
        val presentation = treeElement.presentation
        val name = presentation.presentableText

        // Depth 0 is the file root — we don't add it as an element, just walk its children
        if (depth > 0 && psiElement != null && name != null) {
            val hasChildren = treeElement.children.isNotEmpty()
            val kind = determineKind(psiElement, hasChildren)
            val textRange = psiElement.textRange

            // Build signature from IDE-provided qualified name if available
            val qualifiedName = (psiElement as? PsiQualifiedNamedElement)?.qualifiedName
            val signature = buildSignature(qualifiedName, parentName, name)

            val element = CodeElement(
                name = name,
                body = psiElement.text,
                kind = kind,
                offsetRange = textRange.startOffset..textRange.endOffset,
                parentName = parentName,
                signature = signature
            )

            // Apply scope filtering
            if (matchesScope(element, kind, scope, depth)) {
                result.add(element)
            }
        }

        // Determine if we should recurse into children based on scope
        if (shouldRecurse(scope, depth, name)) {
            for (child in treeElement.children) {
                walkStructureTree(
                    child, scope, result,
                    depth = depth + 1,
                    parentName = if (depth >= 1) name else null
                )
            }
        }
    }

    /**
     * Determines the [CodeElementKind] from the IDE's own element classification.
     *
     * Uses [PsiElement.node.elementType] which is a label the IDE already assigned
     * to each element. We just read it — no language-specific imports, no our own
     * classification logic. The IDE already did the work.
     *
     * The elementType.toString() returns strings like "CLASS", "FUN", "PROPERTY",
     * "FIELD", "METHOD", "OBJECT_DECLARATION", etc. These vary by language but
     * we map them generically by common keywords.
     *
     * Falls back to tree position (hasChildren) only if elementType is unrecognised.
     */
    private fun determineKind(psiElement: PsiElement, hasChildren: Boolean): CodeElementKind {
        val elementType = psiElement.node?.elementType?.toString()?.uppercase() ?: ""

        return when {
            elementType.contains("CLASS") && elementType.contains("INTERFACE") -> CodeElementKind.INTERFACE
            elementType.contains("ENUM") -> CodeElementKind.ENUM
            elementType.contains("OBJECT") -> CodeElementKind.OBJECT
            elementType.contains("INTERFACE") -> CodeElementKind.INTERFACE
            elementType.contains("CLASS") -> CodeElementKind.CLASS
            elementType.contains("CONSTRUCTOR") || elementType.contains("PRIMARY_CONSTRUCTOR") || elementType.contains("SECONDARY_CONSTRUCTOR") -> CodeElementKind.CONSTRUCTOR
            elementType.contains("FUN") || elementType.contains("METHOD") -> CodeElementKind.METHOD
            elementType.contains("PROPERTY") || elementType.contains("FIELD") -> CodeElementKind.PROPERTY
            elementType.contains("FUNCTION") -> CodeElementKind.FUNCTION
            // Fallback: let tree position decide
            hasChildren -> CodeElementKind.CLASS
            else -> CodeElementKind.OTHER
        }
    }

    /**
     * Builds a signature string for cache keying.
     *
     * Uses IDE-provided qualified name when available (via PsiQualifiedNamedElement).
     * Falls back to parentName#name construction.
     */
    private fun buildSignature(
        qualifiedName: String?,
        parentName: String?,
        name: String
    ): String {
        if (qualifiedName != null) return qualifiedName
        return if (parentName != null) "$parentName#$name" else name
    }

    /**
     * Checks if an element matches the requested [DetectionScope].
     */
    private fun matchesScope(
        element: CodeElement,
        kind: CodeElementKind,
        scope: DetectionScope,
        depth: Int
    ): Boolean {
        return when (scope) {
            is DetectionScope.All -> true
            is DetectionScope.ClassesOnly -> {
                depth == 1 && kind == CodeElementKind.CLASS
            }
            is DetectionScope.MethodsOf -> {
                element.parentName == scope.className && kind == CodeElementKind.METHOD
            }
            is DetectionScope.SingleMethod -> {
                element.parentName == scope.className && element.name == scope.methodName
            }
            is DetectionScope.SingleClass -> {
                if (scope.includeMethods) {
                    (element.name == scope.className && depth == 1) ||
                        element.parentName == scope.className
                } else {
                    element.name == scope.className && depth == 1
                }
            }
        }
    }

    /**
     * Determines whether to recurse into children based on scope.
     */
    private fun shouldRecurse(scope: DetectionScope, depth: Int, currentName: String?): Boolean {
        return when (scope) {
            is DetectionScope.All -> true
            is DetectionScope.ClassesOnly -> depth == 0
            is DetectionScope.MethodsOf -> {
                depth == 0 || currentName == scope.className
            }
            is DetectionScope.SingleMethod -> {
                depth == 0 || currentName == scope.className
            }
            is DetectionScope.SingleClass -> {
                if (scope.includeMethods) {
                    depth == 0 || currentName == scope.className
                } else {
                    depth == 0
                }
            }
        }
    }

    // ==================== computeElementHash (Semantic Fingerprint) ====================

    /**
     * Computes a semantic fingerprint for a code element.
     *
     * The fingerprint is built by walking the PsiElement's tree (as built
     * by the IDE) and stripping whitespace and comments. This captures
     * structural/behavioral changes while ignoring formatting.
     *
     * This is CONSUMING the IDE's PSI tree, not duplicating IDE work.
     * We do not parse anything ourselves.
     *
     * ## Trade-off (documented and accepted)
     *
     * This approach is slightly over-eager compared to a language-specific
     * fingerprint. For example, renaming a parameter will change the hash
     * even though it doesn't affect behavior. This means:
     * - We may re-summarize slightly more often than strictly necessary
     * - But we NEVER serve stale summaries
     * - And we remain 100% language-agnostic
     *
     * This is the correct trade-off: the plugin's foundational principle
     * (rely on the IDE, no language-specific code) takes precedence over
     * marginal hashing efficiency.
     */
    override fun computeElementHash(file: VirtualFile, element: CodeElement): String {
        return try {
            ReadAction.compute<String, Throwable> {
                val psiFile = PsiManager.getInstance(project).findFile(file)
                if (psiFile == null) {
                    Dev.warn(log, "structure.ide.hash.no_psi_file", null,
                        "path" to file.path,
                        "element" to element.signature
                    )
                    return@compute sha256(element.body)
                }

                // Find the PsiElement at the stored offset
                val psiAtOffset = psiFile.findElementAt(element.offsetRange.first)
                if (psiAtOffset == null) {
                    Dev.warn(log, "structure.ide.hash.element_not_found", null,
                        "path" to file.path,
                        "element" to element.signature,
                        "offset" to element.offsetRange.first
                    )
                    return@compute sha256(element.body)
                }

                // Walk up to find the containing structural element
                // (findElementAt returns the leaf token, we need the declaration)
                val structuralElement = walkUpToMatchingRange(psiAtOffset, element)

                // Build fingerprint by stripping whitespace and comments
                val fingerprint = buildGenericFingerprint(structuralElement)

                Dev.info(log, "structure.ide.hash.computed",
                    "element" to element.signature,
                    "fingerprintLength" to fingerprint.length
                )

                sha256(fingerprint)
            }
        } catch (e: Throwable) {
            Dev.warn(log, "structure.ide.hash.error", e,
                "path" to file.path,
                "element" to element.signature
            )
            sha256(element.body)
        }
    }

    /**
     * Walks up the PSI tree from a leaf token to find the structural parent
     * that matches the given [CodeElement] by offset range.
     *
     * The offset range was recorded from the Structure View's PsiElement,
     * so walking up should find the same element.
     */
    private fun walkUpToMatchingRange(start: PsiElement, element: CodeElement): PsiElement {
        var current: PsiElement? = start
        while (current != null) {
            val range = current.textRange
            if (range.startOffset == element.offsetRange.first &&
                range.endOffset == element.offsetRange.last) {
                return current
            }
            current = current.parent
        }
        // If no exact match found, return the widest parent at the start offset.
        // This shouldn't happen in normal operation — log it.
        Dev.warn(log, "structure.ide.hash.no_range_match", null,
            "element" to element.signature,
            "expectedStart" to element.offsetRange.first,
            "expectedEnd" to element.offsetRange.last,
            "fallback" to "using start element"
        )
        return start
    }

    /**
     * Builds a language-agnostic fingerprint from a PsiElement tree.
     *
     * Walks all children recursively, SKIPPING:
     * - [PsiWhiteSpace] nodes (formatting, indentation, blank lines)
     * - [PsiComment] nodes (comments, doc comments, KDoc, Javadoc)
     *
     * Everything else (keywords, identifiers, operators, literals, types,
     * control flow tokens) is INCLUDED. The result is a normalized string
     * that changes only when the code's non-cosmetic content changes.
     *
     * This uses ONLY generic PSI interfaces — no language-specific types.
     */
    private fun buildGenericFingerprint(element: PsiElement): String {
        val sb = StringBuilder()
        appendFingerprintRecursive(element, sb)
        return sb.toString()
    }

    /**
     * Recursive helper for [buildGenericFingerprint].
     */
    private fun appendFingerprintRecursive(element: PsiElement, sb: StringBuilder) {
        // Skip whitespace and comments — these are cosmetic
        if (element is PsiWhiteSpace || element is PsiComment) return

        // Leaf node (no children) — include its text in the fingerprint
        if (element.firstChild == null) {
            sb.append(element.text)
            sb.append('\u0000') // null separator for consistent hashing
            return
        }

        // Non-leaf: recurse into children
        var child = element.firstChild
        while (child != null) {
            appendFingerprintRecursive(child, sb)
            child = child.nextSibling
        }
    }

    // ==================== extractStructuralContext ====================

    /**
     * Extracts structural context for the given element.
     *
     * Uses the IDE's Structure View presentation data and generic PsiElement
     * info. NO language-specific extraction.
     *
     * Returns a bullet-pointed string consumed by SummaryExtractor
     * via the {structuralContext} placeholder in prompt templates.
     */
    override fun extractStructuralContext(
        file: VirtualFile,
        element: CodeElement,
        level: ElementLevel
    ): String {
        return try {
            ReadAction.compute<String, Throwable> {
                val psiFile = PsiManager.getInstance(project).findFile(file)
                if (psiFile == null) {
                    Dev.info(log, "structure.ide.context.no_file",
                        "path" to file.path,
                        "element" to element.signature,
                        "level" to level.name
                    )
                    return@compute ""
                }

                val lines = mutableListOf<String>()

                when (level) {
                    ElementLevel.FILE -> extractFileContext(psiFile, lines)
                    ElementLevel.CLASS, ElementLevel.METHOD -> {
                        extractElementContext(psiFile, element, lines)
                    }
                }

                val result = lines.joinToString("\n")
                Dev.info(log, "structure.ide.context.extracted",
                    "element" to element.signature,
                    "level" to level.name,
                    "lineCount" to lines.size
                )
                result
            }
        } catch (e: Throwable) {
            Dev.warn(log, "structure.ide.context.error", e,
                "element" to element.signature,
                "level" to level.name
            )
            ""
        }
    }

    /**
     * Extracts file-level structural context from the structure view root.
     */
    private fun extractFileContext(psiFile: PsiFile, lines: MutableList<String>) {
        lines.add("* Language: ${psiFile.language.displayName}")

        val builder = LanguageStructureViewBuilder.getInstance()
            .getStructureViewBuilder(psiFile)

        if (builder == null || builder !is TreeBasedStructureViewBuilder) return

        val model = builder.createStructureViewModel(null)
        try {
            val children = model.root.children

            val containerCount = children.count { child ->
                child is StructureViewTreeElement && child.children.isNotEmpty()
            }
            val memberCount = children.count { child ->
                child is StructureViewTreeElement && child.children.isEmpty()
            }

            lines.add("* Top-level containers: $containerCount")
            if (memberCount > 0) {
                lines.add("* Top-level members: $memberCount")
            }

            // List top-level element names (as the IDE sees them)
            val names = children.mapNotNull { child ->
                (child as? StructureViewTreeElement)?.presentation?.presentableText
            }
            if (names.isNotEmpty()) {
                lines.add("* Declarations: ${names.joinToString(", ")}")
            }
        } finally {
            model.dispose()
        }
    }

    /**
     * Extracts element-level structural context from the structure view.
     */
    private fun extractElementContext(
        psiFile: PsiFile,
        element: CodeElement,
        lines: MutableList<String>
    ) {
        val builder = LanguageStructureViewBuilder.getInstance()
            .getStructureViewBuilder(psiFile)

        if (builder == null || builder !is TreeBasedStructureViewBuilder) return

        val model = builder.createStructureViewModel(null)
        try {
            val treeElement = findInTree(model.root, element)

            if (treeElement != null) {
                val presentation = treeElement.presentation

                presentation.presentableText?.let { name ->
                    lines.add("* Name: $name")
                }
                presentation.locationString?.let { location ->
                    lines.add("* Location: $location")
                }

                // Children summary (for containers — methods within a class, etc.)
                val children = treeElement.children
                if (children.isNotEmpty()) {
                    val childNames = children.mapNotNull { child ->
                        (child as? StructureViewTreeElement)?.presentation?.presentableText
                    }
                    lines.add("* Members (${children.size}): ${childNames.joinToString(", ")}")
                }

                if (element.parentName != null) {
                    lines.add("* Parent: ${element.parentName}")
                }
            } else {
                Dev.info(log, "structure.ide.context.not_found_in_tree",
                    "element" to element.signature,
                    "reason" to "Could not find matching element in structure view"
                )
            }
        } finally {
            model.dispose()
        }
    }

    /**
     * Finds a [CodeElement] in the structure view tree by matching offset range.
     */
    private fun findInTree(
        treeElement: TreeElement,
        target: CodeElement
    ): StructureViewTreeElement? {
        if (treeElement is StructureViewTreeElement) {
            val psi = treeElement.value as? PsiElement
            if (psi != null) {
                val range = psi.textRange
                if (range.startOffset == target.offsetRange.first &&
                    range.endOffset == target.offsetRange.last
                ) {
                    return treeElement
                }
            }
        }

        for (child in treeElement.children) {
            val found = findInTree(child, target)
            if (found != null) return found
        }

        return null
    }

    // ==================== Helpers ====================

    /**
     * Computes SHA-256 hash of a string.
     *
     * Used for the semantic fingerprint: the normalised text (minus whitespace
     * and comments) is hashed into a fixed-length string for cache keying.
     */
    private fun sha256(input: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(input.toByteArray(Charsets.UTF_8))
        return md.digest().joinToString("") { b -> "%02x".format(b) }
    }
}
