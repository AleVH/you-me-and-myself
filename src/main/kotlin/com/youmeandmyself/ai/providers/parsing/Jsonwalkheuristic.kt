package com.youmeandmyself.ai.providers.parsing

import com.youmeandmyself.ai.providers.parsing.ui.TextCandidate
import kotlinx.serialization.json.*

/**
 * Heuristic fallback for extracting content from unknown JSON formats.
 *
 * When FormatDetector can't identify a known schema, this walks the JSON
 * to find plausible content strings and rank them by likelihood.
 *
 * ## Three Scenarios
 *
 * 1. **High confidence**: Best candidate scores very high, clear winner
 *    → Return it as the answer, but include other candidates for correction
 *
 * 2. **Medium confidence**: Best candidate is okay but not certain
 *    → Show it but prominently offer "Not right?" correction
 *
 * 3. **Low/no confidence**: No good candidates or too close to call
 *    → Don't guess, immediately ask user to pick
 *
 * ## Scoring Heuristics
 *
 * - Longer strings score higher (content is usually substantial)
 * - Contains whitespace/punctuation (likely prose) scores higher
 * - Known content field names ("content", "text", "message") score higher
 * - URL-only strings score lower
 * - Known metadata field names ("id", "model", "object") score lower
 */
object JsonWalkHeuristic {

    // Field names that typically contain metadata, not content
    private val metadataFieldNames = setOf(
        "id", "object", "model", "created", "type", "role",
        "index", "finish_reason", "finishReason", "stop_reason",
        "name", "status", "code", "version", "url", "href",
        "timestamp", "date", "time"
    )

    // Field names that typically contain content
    private val contentFieldNames = setOf(
        "content", "text", "message", "response", "output",
        "answer", "result", "data", "body", "reply"
    )

    // Confidence thresholds
    private const val HIGH_CONFIDENCE_THRESHOLD = 120
    private const val MEDIUM_CONFIDENCE_THRESHOLD = 60
    private const val MIN_CANDIDATE_SCORE = 10

    /**
     * Walk the JSON and find all plausible content candidates, ranked by score.
     *
     * @param rawJson The raw JSON to analyze
     * @return HeuristicResult with best candidate and all alternatives, or null if parsing fails
     */
    fun findCandidates(rawJson: String): HeuristicResult? {
        val jsonElement = try {
            Json.parseToJsonElement(rawJson)
        } catch (e: Exception) {
            return null
        }

        val rawCandidates = mutableListOf<ScoringCandidate>()
        collectStrings(jsonElement, "", rawCandidates)

        if (rawCandidates.isEmpty()) return null

        // Score all candidates
        val scoredCandidates = rawCandidates
            .map { scoreCandidate(it) }
            .filter { it.score >= MIN_CANDIDATE_SCORE }
            .sortedByDescending { it.score }

        if (scoredCandidates.isEmpty()) return null

        val best = scoredCandidates.first()
        val alternatives = scoredCandidates.drop(1)

        // Determine confidence based on best score and gap to second-best
        val confidence = calculateConfidence(best, alternatives)

        return HeuristicResult(
            content = best.text,
            path = best.path,
            confidence = confidence,
            score = best.score,
            bestCandidate = best,
            allCandidates = scoredCandidates
        )
    }

    /**
     * Legacy method for backward compatibility.
     * @deprecated Use findCandidates() instead for full candidate list
     */
    @Deprecated("Use findCandidates() for full candidate list", ReplaceWith("findCandidates(rawJson)"))
    fun findBestContent(rawJson: String): HeuristicResult? = findCandidates(rawJson)

    /**
     * Try to extract content using a specific path (from user hint).
     *
     * @param rawJson The raw JSON
     * @param path The JSON path to try (e.g., ".data.response.text")
     * @return The content at that path, or null if path doesn't exist or is empty
     */
    fun extractAtPath(rawJson: String, path: String): String? {
        val jsonElement = try {
            Json.parseToJsonElement(rawJson)
        } catch (e: Exception) {
            return null
        }

        return navigateToPath(jsonElement, path)
    }

    /**
     * Navigate JSON to a specific path and extract the string value.
     */
    private fun navigateToPath(element: JsonElement, path: String): String? {
        if (path.isEmpty() || path == ".") {
            return (element as? JsonPrimitive)?.contentOrNull
        }

        val parts = parsePath(path)
        var current: JsonElement = element

        for (part in parts) {
            current = when {
                part.startsWith("[") && part.endsWith("]") -> {
                    // Array index
                    val index = part.removeSurrounding("[", "]").toIntOrNull() ?: return null
                    (current as? JsonArray)?.getOrNull(index) ?: return null
                }
                current is JsonObject -> {
                    current[part] ?: return null
                }
                else -> return null
            }
        }

        return (current as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    }

    /**
     * Parse a JSON path into components.
     * ".choices[0].message.content" → ["choices", "[0]", "message", "content"]
     */
    private fun parsePath(path: String): List<String> {
        val result = mutableListOf<String>()
        val cleaned = path.removePrefix(".")

        var current = StringBuilder()
        var i = 0
        while (i < cleaned.length) {
            when (cleaned[i]) {
                '.' -> {
                    if (current.isNotEmpty()) {
                        result.add(current.toString())
                        current = StringBuilder()
                    }
                }
                '[' -> {
                    if (current.isNotEmpty()) {
                        result.add(current.toString())
                        current = StringBuilder()
                    }
                    // Capture the array index including brackets
                    val endBracket = cleaned.indexOf(']', i)
                    if (endBracket != -1) {
                        result.add(cleaned.substring(i, endBracket + 1))
                        i = endBracket
                    }
                }
                else -> current.append(cleaned[i])
            }
            i++
        }
        if (current.isNotEmpty()) {
            result.add(current.toString())
        }

        return result
    }

    /**
     * Calculate confidence based on score distribution.
     */
    private fun calculateConfidence(best: TextCandidate, alternatives: List<TextCandidate>): Confidence {
        // Very high score = high confidence
        if (best.score >= HIGH_CONFIDENCE_THRESHOLD) {
            return Confidence.HIGH
        }

        // Check gap to second-best
        val secondBestScore = alternatives.firstOrNull()?.score ?: 0
        val gap = best.score - secondBestScore

        return when {
            // Good score with clear lead
            best.score >= MEDIUM_CONFIDENCE_THRESHOLD && gap >= 30 -> Confidence.MEDIUM
            // Decent score
            best.score >= MEDIUM_CONFIDENCE_THRESHOLD -> Confidence.LOW
            // Poor score
            else -> Confidence.NONE
        }
    }

    /**
     * Recursively collect all string values from JSON.
     */
    private fun collectStrings(
        element: JsonElement,
        path: String,
        results: MutableList<ScoringCandidate>
    ) {
        when (element) {
            is JsonPrimitive -> {
                if (element.isString && element.content.isNotBlank()) {
                    results.add(ScoringCandidate(
                        value = element.content,
                        path = path,
                        fieldName = path.substringAfterLast(".").removeSuffix("]").let {
                            if (it.contains("[")) it.substringBefore("[") else it
                        }
                    ))
                }
            }
            is JsonObject -> {
                for ((key, value) in element) {
                    val newPath = if (path.isEmpty()) ".$key" else "$path.$key"
                    collectStrings(value, newPath, results)
                }
            }
            is JsonArray -> {
                element.forEachIndexed { index, value ->
                    collectStrings(value, "$path[$index]", results)
                }
            }
        }
    }

    /**
     * Score a candidate and convert to TextCandidate with breakdown.
     */
    private fun scoreCandidate(candidate: ScoringCandidate): TextCandidate {
        var score = 0
        val breakdown = mutableListOf<String>()
        val value = candidate.value

        // Length bonus (longer = more likely to be content)
        val lengthBonus = minOf(value.length / 10, 50)
        if (lengthBonus > 0) {
            score += lengthBonus
            breakdown.add("+$lengthBonus: length (${value.length} chars)")
        }

        // Contains whitespace (prose-like)
        if (value.contains(" ")) {
            score += 20
            breakdown.add("+20: contains spaces (prose-like)")
        }

        // Contains punctuation (prose-like)
        if (value.any { it in ".,!?;:" }) {
            score += 15
            breakdown.add("+15: contains punctuation")
        }

        // Contains newlines (multi-line content)
        if (value.contains("\n")) {
            score += 10
            breakdown.add("+10: multi-line content")
        }

        // Penalize if field name is metadata-like
        if (candidate.fieldName.lowercase() in metadataFieldNames) {
            score -= 40
            breakdown.add("-40: metadata-like field name '${candidate.fieldName}'")
        }

        // Penalize if looks like a URL
        if (value.startsWith("http://") || value.startsWith("https://")) {
            score -= 30
            breakdown.add("-30: looks like URL")
        }

        // Penalize if looks like an ID (alphanumeric with dashes)
        if (value.matches(Regex("^[a-zA-Z0-9-_]{8,}$"))) {
            score -= 25
            breakdown.add("-25: looks like ID/token")
        }

        // Bonus for content-like field names
        if (candidate.fieldName.lowercase() in contentFieldNames) {
            score += 30
            breakdown.add("+30: content-like field name '${candidate.fieldName}'")
        }

        // Bonus for being nested under typical content paths
        val pathLower = candidate.path.lowercase()
        if ("message" in pathLower || "content" in pathLower || "text" in pathLower) {
            score += 20
            breakdown.add("+20: path contains content-related term")
        }
        if ("choices" in pathLower || "candidates" in pathLower || "response" in pathLower) {
            score += 15
            breakdown.add("+15: path contains response-related term")
        }

        return TextCandidate.create(
            text = value,
            path = candidate.path,
            score = score,
            scoreBreakdown = breakdown
        )
    }
}

/**
 * Internal candidate during scoring (before conversion to TextCandidate).
 */
private data class ScoringCandidate(
    val value: String,
    val path: String,
    val fieldName: String
)

/**
 * Result of heuristic content extraction.
 */
data class HeuristicResult(
    val content: String,
    val path: String,
    val confidence: Confidence,
    val score: Int,
    val bestCandidate: TextCandidate,
    val allCandidates: List<TextCandidate>
)