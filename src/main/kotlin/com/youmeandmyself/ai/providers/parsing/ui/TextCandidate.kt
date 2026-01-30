package com.youmeandmyself.ai.providers.parsing.ui

/**
 * A candidate text string found during heuristic JSON walking.
 *
 * When we can't identify a known response format, we walk the JSON and find
 * all plausible content strings. Each becomes a TextCandidate with enough
 * context for:
 * 1. Automatic ranking (score-based selection)
 * 2. User presentation (show path and preview in correction dialog)
 * 3. Learning (save user's choice to improve future parsing)
 *
 * @property text The actual string content
 * @property path JSON path where this was found (e.g., ".choices[0].message.content")
 * @property score Heuristic score (higher = more likely to be the actual content)
 * @property preview Truncated preview for UI display (first ~100 chars)
 * @property scoreBreakdown Human-readable explanation of why it scored this way
 */
data class TextCandidate(
    val text: String,
    val path: String,
    val score: Int,
    val preview: String = text.take(100).replace("\n", " ") + if (text.length > 100) "..." else "",
    val scoreBreakdown: List<String> = emptyList()
) {
    /**
     * User-friendly label for display in selection dialog.
     * Shows the path and a preview of the content.
     */
    fun displayLabel(): String {
        val pathDisplay = path.ifEmpty { "(root)" }
        return "$pathDisplay: $preview"
    }

    /**
     * Detailed view for when user wants more info about a candidate.
     */
    fun detailedDescription(): String = buildString {
        appendLine("Path: $path")
        appendLine("Score: $score")
        if (scoreBreakdown.isNotEmpty()) {
            appendLine("Why this score:")
            scoreBreakdown.forEach { appendLine("  • $it") }
        }
        appendLine()
        appendLine("Full content:")
        appendLine(text)
    }

    companion object {
        /**
         * Create a candidate with automatic preview generation.
         */
        fun create(
            text: String,
            path: String,
            score: Int,
            scoreBreakdown: List<String> = emptyList()
        ): TextCandidate = TextCandidate(
            text = text,
            path = path,
            score = score,
            preview = generatePreview(text),
            scoreBreakdown = scoreBreakdown
        )

        private fun generatePreview(text: String): String {
            val cleaned = text.trim().replace(Regex("\\s+"), " ")
            return if (cleaned.length <= 100) {
                cleaned
            } else {
                cleaned.take(97) + "..."
            }
        }
    }
}