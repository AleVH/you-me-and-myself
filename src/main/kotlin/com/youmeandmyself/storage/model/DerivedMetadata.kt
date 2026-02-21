package com.youmeandmyself.storage.model

import java.security.MessageDigest

/**
 * Auto-detected content features extracted from an AI response.
 *
 * Generated at ingest time (when saving an exchange) by analyzing the
 * parsed assistant text. Stored in SQLite for fast filtering without
 * touching JSONL files.
 *
 * ## What Gets Detected
 *
 * - Code blocks: presence, languages (kotlin, bash, python, etc.)
 * - Commands: shell/terminal commands the user can run
 * - Stack traces: exception patterns suggesting debugging context
 * - Topics: keyword-based classification (build, gradle, docker, etc.)
 * - File paths: references to files in the response
 * - Duplicate hash: SHA-256 of the prompt for duplicate detection
 *
 * ## Why at Ingest
 *
 * Detecting these once at save time means the Library tab can filter
 * by "has code blocks" or "topic:gradle" without re-parsing responses.
 * If the SQLite database is rebuilt, these are regenerated from JSONL.
 *
 * @property hasCodeBlock True if the response contains fenced code blocks
 * @property codeLanguages Languages detected in code fences (e.g., ["kotlin", "bash"])
 * @property hasCommand True if bash/shell/terminal code blocks are present
 * @property hasStacktrace True if exception/stack trace patterns detected
 * @property detectedTopics Auto-classified topics based on keyword rules
 * @property filePaths File paths mentioned in the response
 * @property duplicateHash SHA-256 hash of the prompt input for duplicate detection
 */
data class DerivedMetadata(
    val hasCodeBlock: Boolean = false,
    val codeLanguages: List<String> = emptyList(),
    val hasCommand: Boolean = false,
    val hasStacktrace: Boolean = false,
    val detectedTopics: List<String> = emptyList(),
    val filePaths: List<String> = emptyList(),
    val duplicateHash: String? = null
) {
    companion object {

        /**
         * Analyze assistant text and prompt to generate derived metadata.
         *
         * @param assistantText The parsed AI response text
         * @param promptText The user's original prompt
         * @return DerivedMetadata with all detected features
         */
        fun extract(assistantText: String?, promptText: String?): DerivedMetadata {
            if (assistantText.isNullOrBlank()) {
                return DerivedMetadata(
                    duplicateHash = promptText?.let { hashPrompt(it) }
                )
            }

            val codeBlocks = extractCodeBlocks(assistantText)
            val languages = codeBlocks.mapNotNull { it.language }.distinct()
            val commandLanguages = setOf("bash", "sh", "shell", "zsh", "terminal", "console", "cmd", "powershell")

            return DerivedMetadata(
                hasCodeBlock = codeBlocks.isNotEmpty(),
                codeLanguages = languages,
                hasCommand = languages.any { it in commandLanguages },
                hasStacktrace = detectStacktrace(assistantText),
                detectedTopics = detectTopics(assistantText, promptText),
                filePaths = extractFilePaths(assistantText),
                duplicateHash = promptText?.let { hashPrompt(it) }
            )
        }

        /**
         * Hash a prompt for duplicate detection.
         *
         * Normalizes whitespace before hashing so minor formatting
         * differences don't create false negatives.
         */
        fun hashPrompt(prompt: String): String {
            val normalized = prompt.trim().replace(Regex("\\s+"), " ").lowercase()
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(normalized.toByteArray())
            return hash.joinToString("") { "%02x".format(it) }
        }

        // ==================== Code Block Detection ====================

        private data class CodeBlock(val language: String?, val content: String)

        private val CODE_FENCE_REGEX = Regex("```(\\w*)\\s*\\n([\\s\\S]*?)```", RegexOption.MULTILINE)

        private fun extractCodeBlocks(text: String): List<CodeBlock> {
            return CODE_FENCE_REGEX.findAll(text).map { match ->
                CodeBlock(
                    language = match.groupValues[1].lowercase().ifBlank { null },
                    content = match.groupValues[2]
                )
            }.toList()
        }

        // ==================== Stack Trace Detection ====================

        private val STACKTRACE_PATTERNS = listOf(
            Regex("at\\s+[\\w.]+\\([\\w]+\\.\\w+:\\d+\\)"),        // Java/Kotlin: at com.foo.Bar(File.kt:42)
            Regex("Exception|Error:\\s+\\w+"),                       // Exception: Something
            Regex("Traceback \\(most recent call last\\)"),          // Python
            Regex("\\s+File \"[^\"]+\", line \\d+"),                // Python traceback
            Regex("panic:\\s+"),                                     // Go
            Regex("FATAL|SEVERE|UnhandledPromiseRejection"),         // Generic
            Regex("Caused by:\\s+")                                  // Java chained exceptions
        )

        private fun detectStacktrace(text: String): Boolean {
            return STACKTRACE_PATTERNS.any { it.containsMatchIn(text) }
        }

        // ==================== Topic Detection ====================

        /**
         * Keyword-based topic classification.
         *
         * Rules are intentionally simple — just keyword presence in
         * prompt + response combined. More sophisticated classification
         * (embeddings, NLP) is planned for post-launch.
         */
        private val TOPIC_RULES: Map<String, List<String>> = mapOf(
            "build" to listOf("gradle", "maven", "build.gradle", "pom.xml", "cmake", "makefile", "bazel", "compile", "compilation"),
            "testing" to listOf("junit", "test", "assert", "mock", "testng", "espresso", "kotest", "spock", "coverage"),
            "docker" to listOf("docker", "dockerfile", "container", "docker-compose", "kubernetes", "k8s", "helm"),
            "git" to listOf("git ", "commit", "branch", "merge", "rebase", "pull request", "cherry-pick", ".gitignore"),
            "database" to listOf("sql", "query", "select ", "insert ", "update ", "delete ", "join ", "postgresql", "mysql", "sqlite", "migration"),
            "auth" to listOf("jwt", "oauth", "token", "authentication", "authorization", "password", "session", "cors", "csrf"),
            "api" to listOf("rest", "endpoint", "http", "request", "response", "graphql", "grpc", "openapi", "swagger"),
            "ui" to listOf("layout", "component", "css", "html", "swing", "javafx", "compose", "react", "angular", "vue"),
            "performance" to listOf("performance", "optimize", "cache", "latency", "throughput", "benchmark", "profil", "memory leak"),
            "security" to listOf("security", "vulnerability", "encrypt", "decrypt", "hash", "sanitize", "injection", "xss"),
            "concurrency" to listOf("thread", "coroutine", "async", "await", "mutex", "lock", "concurrent", "parallel", "deadlock"),
            "serialization" to listOf("json", "xml", "yaml", "serialize", "deserialize", "parse", "marshal", "protobuf"),
            "dependency" to listOf("dependency", "import", "library", "package", "module", "classpath", "version conflict"),
            "debugging" to listOf("debug", "breakpoint", "stacktrace", "exception", "error", "crash", "null pointer", "bug"),
            "regex" to listOf("regex", "regular expression", "pattern", "match", "capture group"),
            "kotlin" to listOf("kotlin", "data class", "sealed class", "companion object", "extension function", "coroutine"),
            "java" to listOf("java", "jvm", "spring", "hibernate", "jpa", "servlet"),
            "python" to listOf("python", "pip", "django", "flask", "pandas", "numpy"),
            "infrastructure" to listOf("ci/cd", "pipeline", "deploy", "aws", "azure", "gcp", "terraform", "ansible", "nginx")
        )

        private fun detectTopics(assistantText: String?, promptText: String?): List<String> {
            val combined = "${promptText ?: ""} ${assistantText ?: ""}".lowercase()
            return TOPIC_RULES.entries
                .filter { (_, keywords) -> keywords.any { combined.contains(it) } }
                .map { it.key }
                .sorted()
        }

        // ==================== File Path Detection ====================

        private val FILE_PATH_REGEX = Regex(
            "(?:^|\\s|['\"`(])(" +
                    "(?:[/~]|\\w:[\\\\/])[\\w./-]+\\.[\\w]+" +           // Unix/Windows absolute paths
                    "|[\\w.-]+/[\\w.-]+(?:/[\\w.-]+)*\\.[\\w]+" +        // Relative paths with extensions
                    ")",
            RegexOption.MULTILINE
        )

        private fun extractFilePaths(text: String): List<String> {
            return FILE_PATH_REGEX.findAll(text)
                .map { it.groupValues[1].trim() }
                .filter { path ->
                    // Filter out URLs and common false positives
                    !path.startsWith("http") &&
                            !path.startsWith("www.") &&
                            path.contains(".")
                }
                .distinct()
                .toList()
                .take(20) // Cap at 20 to avoid bloat
        }
    }
}