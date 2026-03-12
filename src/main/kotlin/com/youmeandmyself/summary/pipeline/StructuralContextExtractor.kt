package com.youmeandmyself.summary.pipeline

import com.intellij.openapi.diagnostic.Logger
import com.youmeandmyself.dev.Dev

/**
 * Extracts structural context from source code using regex heuristics.
 *
 * ## Purpose
 *
 * Before summarizing a code element (method, class, file), we gather structural
 * information that helps the LLM understand WHAT it's looking at. This context is
 * prepended to the summary prompt via the {structuralContext} placeholder.
 *
 * ## Why Isolated
 *
 * This class is deliberately standalone and stateless so it can be:
 * - Replaced with real PSI integration incrementally (swap internals, keep interface)
 * - Tested independently of the summary pipeline
 * - Extended per-language without touching prompt or pipeline logic
 *
 * ## What It Extracts (Pragmatic, Not Perfect)
 *
 * For methods: visibility, annotations, parameter types, return type, receiver type
 * For classes: visibility, annotations, superclass/interfaces, type parameters
 * For files: package declaration, import summary, top-level declarations
 *
 * The LLM is good at understanding code even without explicit structural context —
 * this is an optimization, not a requirement. Empty context is fine.
 *
 * ## Supported Languages
 *
 * - Kotlin / Java: richest extraction (annotations, types, visibility)
 * - JavaScript / TypeScript: basic extraction (exports, imports, JSDoc)
 * - Fallback: empty context (the source code itself is enough for the LLM)
 *
 * ## Design Doc Reference
 *
 * "The Summary Module — Complete Architecture", Part 3 (Node Context Assembler):
 * "Before summarizing, collect structural info from PSI/IDE: node type, annotations,
 * package, relationships, fan-in. This context is prepended to the summary prompt."
 */
object StructuralContextExtractor {

    private val log = Logger.getInstance(StructuralContextExtractor::class.java)

    // ==================== Public API ====================

    /**
     * Extract structural context for a method-level summary.
     *
     * @param sourceCode The method's source code (including signature)
     * @param languageId Language identifier (e.g., "Kotlin", "Java", "TypeScript")
     * @return Structured context string, or empty string if nothing useful found
     */
    fun forMethod(sourceCode: String, languageId: String?): String {
        val lang = normalizeLanguage(languageId)
        return when (lang) {
            Language.KOTLIN -> extractKotlinMethodContext(sourceCode)
            Language.JAVA -> extractJavaMethodContext(sourceCode)
            Language.TYPESCRIPT, Language.JAVASCRIPT -> extractJsTsMethodContext(sourceCode)
            Language.UNKNOWN -> ""
        }
    }

    /**
     * Extract structural context for a class-level summary.
     *
     * @param sourceCode The class's source code (or at minimum, the class declaration + member signatures)
     * @param languageId Language identifier
     * @return Structured context string, or empty string if nothing useful found
     */
    fun forClass(sourceCode: String, languageId: String?): String {
        val lang = normalizeLanguage(languageId)
        return when (lang) {
            Language.KOTLIN -> extractKotlinClassContext(sourceCode)
            Language.JAVA -> extractJavaClassContext(sourceCode)
            Language.TYPESCRIPT, Language.JAVASCRIPT -> extractJsTsClassContext(sourceCode)
            Language.UNKNOWN -> ""
        }
    }

    /**
     * Extract structural context for a file-level summary.
     *
     * @param sourceCode The file's full content (or a representative sample)
     * @param languageId Language identifier
     * @return Structured context string, or empty string if nothing useful found
     */
    fun forFile(sourceCode: String, languageId: String?): String {
        val lang = normalizeLanguage(languageId)
        return when (lang) {
            Language.KOTLIN -> extractKotlinFileContext(sourceCode)
            Language.JAVA -> extractJavaFileContext(sourceCode)
            Language.TYPESCRIPT, Language.JAVASCRIPT -> extractJsTsFileContext(sourceCode)
            Language.UNKNOWN -> ""
        }
    }

    // ==================== Kotlin Extraction ====================

    private fun extractKotlinMethodContext(source: String): String {
        val parts = mutableListOf<String>()

        // Annotations (lines starting with @, before the fun keyword)
        val annotations = KOTLIN_ANNOTATION_RE.findAll(source)
            .map { it.value.trim() }
            .toList()
        if (annotations.isNotEmpty()) {
            parts += "Annotations: ${annotations.joinToString(", ")}"
        }

        // Visibility + modifiers
        val funMatch = KOTLIN_FUN_RE.find(source)
        if (funMatch != null) {
            val modifiers = funMatch.groupValues[1].trim()
            val receiverAndName = funMatch.groupValues[2].trim()
            val params = funMatch.groupValues[3].trim()
            val returnType = funMatch.groupValues.getOrNull(4)?.trim()?.removePrefix(":")?.trim()

            if (modifiers.isNotBlank()) parts += "Modifiers: $modifiers"
            if ("." in receiverAndName) {
                val receiver = receiverAndName.substringBeforeLast(".")
                parts += "Receiver: $receiver"
            }
            if (params.isNotBlank()) parts += "Parameters: $params"
            if (!returnType.isNullOrBlank()) parts += "Returns: $returnType"
        }

        // Suspend
        if (source.contains("suspend ")) parts += "Coroutine: suspend function"

        return formatContext(parts)
    }

    private fun extractKotlinClassContext(source: String): String {
        val parts = mutableListOf<String>()

        // Annotations before class declaration
        val annotations = KOTLIN_ANNOTATION_RE.findAll(source.substringBefore("class "))
            .map { it.value.trim() }
            .toList()
        if (annotations.isNotEmpty()) parts += "Annotations: ${annotations.joinToString(", ")}"

        // Class declaration
        val classMatch = KOTLIN_CLASS_RE.find(source)
        if (classMatch != null) {
            val modifiers = classMatch.groupValues[1].trim()
            val kind = classMatch.groupValues[2].trim() // class, interface, object, enum class
            val name = classMatch.groupValues[3].trim()
            val typeParams = classMatch.groupValues.getOrNull(4)?.trim()
            val supers = classMatch.groupValues.getOrNull(5)?.trim()?.removePrefix(":")?.trim()

            if (modifiers.isNotBlank()) parts += "Modifiers: $modifiers"
            parts += "Kind: $kind"
            if (!typeParams.isNullOrBlank()) parts += "Type parameters: $typeParams"
            if (!supers.isNullOrBlank()) parts += "Extends/Implements: $supers"
        }

        // Count members (rough)
        val funCount = "\\bfun\\b".toRegex().findAll(source).count()
        val valCount = "\\bval\\b|\\bvar\\b".toRegex().findAll(source).count()
        if (funCount > 0) parts += "Methods: ~$funCount"
        if (valCount > 0) parts += "Properties: ~$valCount"

        return formatContext(parts)
    }

    private fun extractKotlinFileContext(source: String): String {
        val parts = mutableListOf<String>()

        // Package
        val pkg = PACKAGE_RE.find(source)?.groupValues?.get(1)
        if (pkg != null) parts += "Package: $pkg"

        // Import summary (count, notable frameworks)
        val imports = IMPORT_RE.findAll(source).map { it.groupValues[1] }.toList()
        if (imports.isNotEmpty()) {
            parts += "Imports: ${imports.size} total"
            val frameworks = imports.mapNotNull { classifyImport(it) }.distinct()
            if (frameworks.isNotEmpty()) parts += "Dependencies: ${frameworks.joinToString(", ")}"
        }

        // Top-level declarations
        val classes = KOTLIN_CLASS_RE.findAll(source).count()
        val topFuns = "^\\s*(?:fun|suspend\\s+fun)\\b".toRegex(RegexOption.MULTILINE).findAll(source).count()
        if (classes > 0) parts += "Classes/objects: $classes"
        if (topFuns > 0) parts += "Top-level functions: $topFuns"

        return formatContext(parts)
    }

    // ==================== Java Extraction ====================

    private fun extractJavaMethodContext(source: String): String {
        val parts = mutableListOf<String>()

        val annotations = JAVA_ANNOTATION_RE.findAll(source)
            .map { it.value.trim() }
            .toList()
        if (annotations.isNotEmpty()) parts += "Annotations: ${annotations.joinToString(", ")}"

        val methodMatch = JAVA_METHOD_RE.find(source)
        if (methodMatch != null) {
            val modifiers = methodMatch.groupValues[1].trim()
            val returnType = methodMatch.groupValues[2].trim()
            val params = methodMatch.groupValues[4].trim()

            if (modifiers.isNotBlank()) parts += "Modifiers: $modifiers"
            if (returnType.isNotBlank()) parts += "Returns: $returnType"
            if (params.isNotBlank()) parts += "Parameters: $params"
        }

        return formatContext(parts)
    }

    private fun extractJavaClassContext(source: String): String {
        val parts = mutableListOf<String>()

        val annotations = JAVA_ANNOTATION_RE.findAll(source.substringBefore("class "))
            .map { it.value.trim() }
            .toList()
        if (annotations.isNotEmpty()) parts += "Annotations: ${annotations.joinToString(", ")}"

        val classMatch = JAVA_CLASS_RE.find(source)
        if (classMatch != null) {
            val modifiers = classMatch.groupValues[1].trim()
            val kind = classMatch.groupValues[2].trim()
            val extendsImpl = classMatch.groupValues.getOrNull(4)?.trim()

            if (modifiers.isNotBlank()) parts += "Modifiers: $modifiers"
            parts += "Kind: $kind"
            if (!extendsImpl.isNullOrBlank()) parts += "Extends/Implements: $extendsImpl"
        }

        val methodCount = JAVA_METHOD_RE.findAll(source).count()
        if (methodCount > 0) parts += "Methods: ~$methodCount"

        return formatContext(parts)
    }

    private fun extractJavaFileContext(source: String): String {
        val parts = mutableListOf<String>()

        val pkg = PACKAGE_RE.find(source)?.groupValues?.get(1)
        if (pkg != null) parts += "Package: $pkg"

        val imports = IMPORT_RE.findAll(source).map { it.groupValues[1] }.toList()
        if (imports.isNotEmpty()) {
            parts += "Imports: ${imports.size} total"
            val frameworks = imports.mapNotNull { classifyImport(it) }.distinct()
            if (frameworks.isNotEmpty()) parts += "Dependencies: ${frameworks.joinToString(", ")}"
        }

        val classes = JAVA_CLASS_RE.findAll(source).count()
        if (classes > 0) parts += "Classes/interfaces: $classes"

        return formatContext(parts)
    }

    // ==================== JS/TS Extraction ====================

    private fun extractJsTsMethodContext(source: String): String {
        val parts = mutableListOf<String>()

        // JSDoc annotations
        val jsdoc = JSDOC_RE.find(source)
        if (jsdoc != null) {
            val params = JSDOC_PARAM_RE.findAll(jsdoc.value).map { it.groupValues[1] }.toList()
            val returns = JSDOC_RETURNS_RE.find(jsdoc.value)?.groupValues?.get(1)
            if (params.isNotEmpty()) parts += "JSDoc params: ${params.joinToString(", ")}"
            if (returns != null) parts += "JSDoc returns: $returns"
        }

        // Export/async
        if (source.contains("export ")) parts += "Exported: yes"
        if (source.contains("async ")) parts += "Async: yes"

        // TS type annotations on params
        val tsFunc = TS_FUNCTION_RE.find(source)
        if (tsFunc != null) {
            val params = tsFunc.groupValues[1].trim()
            val returnType = tsFunc.groupValues.getOrNull(2)?.trim()?.removePrefix(":")?.trim()
            if (params.isNotBlank()) parts += "Parameters: $params"
            if (!returnType.isNullOrBlank()) parts += "Returns: $returnType"
        }

        return formatContext(parts)
    }

    private fun extractJsTsClassContext(source: String): String {
        val parts = mutableListOf<String>()

        if (source.contains("export ")) parts += "Exported: yes"

        val classMatch = JS_CLASS_RE.find(source)
        if (classMatch != null) {
            val name = classMatch.groupValues[1].trim()
            val extendsClause = classMatch.groupValues.getOrNull(2)?.trim()
            if (!extendsClause.isNullOrBlank()) parts += "Extends: $extendsClause"
        }

        // TS implements
        val implMatch = TS_IMPLEMENTS_RE.find(source)
        if (implMatch != null) parts += "Implements: ${implMatch.groupValues[1].trim()}"

        // Decorators (@Component, @Injectable, etc.)
        val decorators = TS_DECORATOR_RE.findAll(source.substringBefore("class "))
            .map { it.value.trim() }
            .toList()
        if (decorators.isNotEmpty()) parts += "Decorators: ${decorators.joinToString(", ")}"

        return formatContext(parts)
    }

    private fun extractJsTsFileContext(source: String): String {
        val parts = mutableListOf<String>()

        // Import summary
        val imports = JS_IMPORT_RE.findAll(source).map { it.groupValues[1] }.toList()
        if (imports.isNotEmpty()) {
            parts += "Imports: ${imports.size} modules"
            val frameworks = imports.mapNotNull { classifyJsImport(it) }.distinct()
            if (frameworks.isNotEmpty()) parts += "Dependencies: ${frameworks.joinToString(", ")}"
        }

        // Exports
        val exportCount = "\\bexport\\b".toRegex().findAll(source).count()
        if (exportCount > 0) parts += "Exports: ~$exportCount"

        // Top-level declarations
        val classCount = JS_CLASS_RE.findAll(source).count()
        val funcCount = "^\\s*(?:export\\s+)?(?:async\\s+)?function\\b".toRegex(RegexOption.MULTILINE)
            .findAll(source).count()
        if (classCount > 0) parts += "Classes: $classCount"
        if (funcCount > 0) parts += "Functions: $funcCount"

        return formatContext(parts)
    }

    // ==================== Helpers ====================

    private enum class Language { KOTLIN, JAVA, TYPESCRIPT, JAVASCRIPT, UNKNOWN }

    private fun normalizeLanguage(languageId: String?): Language {
        if (languageId == null) return Language.UNKNOWN
        val id = languageId.lowercase()
        return when {
            id.contains("kotlin") || id == "kt" -> Language.KOTLIN
            id.contains("java") && !id.contains("javascript") -> Language.JAVA
            id.contains("typescript") || id == "ts" || id == "tsx" -> Language.TYPESCRIPT
            id.contains("javascript") || id == "js" || id == "jsx" -> Language.JAVASCRIPT
            else -> Language.UNKNOWN
        }
    }

    /**
     * Format extracted parts into a structured context block.
     * Returns empty string if no parts were extracted (dumb-mode fallback).
     */
    private fun formatContext(parts: List<String>): String {
        if (parts.isEmpty()) return ""
        return parts.joinToString("\n") { "• $it" }
    }

    /** Classify a JVM import into a recognizable framework/library name. */
    private fun classifyImport(importPath: String): String? = when {
        importPath.startsWith("org.springframework") -> "Spring"
        importPath.startsWith("io.ktor") -> "Ktor"
        importPath.startsWith("kotlinx.coroutines") -> "Coroutines"
        importPath.startsWith("kotlinx.serialization") -> "Serialization"
        importPath.startsWith("javax.persistence") || importPath.startsWith("jakarta.persistence") -> "JPA"
        importPath.startsWith("org.junit") || importPath.startsWith("kotlin.test") -> "Testing"
        importPath.startsWith("com.intellij") -> "IntelliJ Platform"
        else -> null
    }

    /** Classify a JS/TS import into a recognizable framework/library name. */
    private fun classifyJsImport(modulePath: String): String? = when {
        modulePath.startsWith("react") -> "React"
        modulePath.startsWith("@angular") -> "Angular"
        modulePath.startsWith("vue") -> "Vue"
        modulePath.startsWith("express") -> "Express"
        modulePath.startsWith("@nestjs") -> "NestJS"
        modulePath.startsWith("next") -> "Next.js"
        else -> null
    }

    // ==================== Regex Patterns ====================
    // These are intentionally simple heuristics. They don't need to parse perfectly —
    // the LLM handles code fine even with incomplete context. This is an optimization.

    // --- Kotlin ---
    private val KOTLIN_ANNOTATION_RE = Regex("""^\s*@\w+(?:\([^)]*\))?""", RegexOption.MULTILINE)
    private val KOTLIN_FUN_RE = Regex(
        """((?:(?:public|private|protected|internal|override|open|abstract|suspend|inline|tailrec)\s+)*)fun\s+([^\s(]+)\s*\(([^)]*)\)\s*(?::\s*(\S+))?"""
    )
    private val KOTLIN_CLASS_RE = Regex(
        """((?:(?:public|private|protected|internal|open|abstract|sealed|data|inner|value|annotation)\s+)*)((?:enum\s+)?(?:class|interface|object))\s+(\w+)\s*(?:<([^>]+)>)?\s*(?::\s*([^{]+))?"""
    )

    // --- Java ---
    private val JAVA_ANNOTATION_RE = Regex("""^\s*@\w+(?:\([^)]*\))?""", RegexOption.MULTILINE)
    private val JAVA_METHOD_RE = Regex(
        """((?:(?:public|private|protected|static|final|abstract|synchronized|native)\s+)*)(\w+(?:<[^>]+>)?)\s+(\w+)\s*\(([^)]*)\)"""
    )
    private val JAVA_CLASS_RE = Regex(
        """((?:(?:public|private|protected|static|final|abstract)\s+)*)(class|interface|enum)\s+\w+\s*(?:<[^>]+>)?\s*((?:extends|implements)\s+[^{]+)?"""
    )

    // --- JS/TS ---
    private val JSDOC_RE = Regex("""/\*\*[\s\S]*?\*/""")
    private val JSDOC_PARAM_RE = Regex("""@param\s+\{([^}]+)\}""")
    private val JSDOC_RETURNS_RE = Regex("""@returns?\s+\{([^}]+)\}""")
    private val TS_FUNCTION_RE = Regex("""function\s+\w+\s*\(([^)]*)\)\s*(?::\s*(\S+))?""")
    private val JS_CLASS_RE = Regex("""class\s+(\w+)\s*(?:extends\s+(\w+))?""")
    private val TS_IMPLEMENTS_RE = Regex("""implements\s+([^{]+)""")
    private val TS_DECORATOR_RE = Regex("""^\s*@\w+(?:\([^)]*\))?""", RegexOption.MULTILINE)
    private val JS_IMPORT_RE = Regex("""import\s+.*?\s+from\s+['"]([^'"]+)['"]""")

    // --- Shared ---
    private val PACKAGE_RE = Regex("""^\s*package\s+([\w.]+)""", RegexOption.MULTILINE)
    private val IMPORT_RE = Regex("""^\s*import\s+([\w.*]+)""", RegexOption.MULTILINE)
}