package com.youmeandmyself.ai.providers.parsing

import com.youmeandmyself.ai.providers.parsing.ui.TextCandidate
import java.time.Instant

/**
 * Rich metadata about how a response was parsed.
 *
 * This is stored alongside the raw response in storage, enabling:
 * - Debugging: understand why parsing succeeded or failed
 * - Analytics: track which formats are being used
 * - Format learning: improve detection based on past successes
 * - User correction: show alternative candidates when heuristic was used
 *
 * @property parseStrategy How we interpreted this response
 * @property confidence How confident we are in the parsing
 * @property detectedSchema Which schema was detected (if known)
 * @property contentPath JSONPath used to extract content
 * @property candidates All candidate strings found (when heuristic used) - ranked by score
 * @property tokenUsage Token counts if available in response
 * @property requestId Provider's request/trace ID (for support)
 * @property finishReason Why the model stopped generating
 * @property parsedAt When parsing completed
 */
data class ParseMetadata(
    val parseStrategy: ParseStrategy,
    val confidence: Confidence,
    val detectedSchema: DetectedSchema? = null,
    val contentPath: String? = null,
    val candidates: List<TextCandidate> = emptyList(),
    val tokenUsage: TokenUsage? = null,
    val requestId: String? = null,
    val finishReason: String? = null,
    val parsedAt: Instant = Instant.now()
) {
    /**
     * Check if this result came from heuristic guessing (not a known format).
     */
    val wasHeuristicUsed: Boolean
        get() = parseStrategy == ParseStrategy.HEURISTIC_JSON_WALK

    /**
     * Check if user should be offered correction option.
     *
     * True when:
     * - Heuristic was used (we guessed)
     * - AND there are alternative candidates to show
     */
    val shouldOfferCorrection: Boolean
        get() = wasHeuristicUsed && candidates.size > 1

    /**
     * Check if we should immediately ask user (Scenario 3).
     *
     * True when:
     * - Confidence is NONE or LOW
     * - AND heuristic was used
     */
    val shouldAskUserImmediately: Boolean
        get() = wasHeuristicUsed && (confidence == Confidence.NONE || confidence == Confidence.LOW)

    /**
     * Get alternative candidates (excluding the one we showed).
     */
    val alternativeCandidates: List<TextCandidate>
        get() = if (candidates.size > 1) candidates.drop(1) else emptyList()
}

/**
 * How we parsed the response - useful for debugging and analytics.
 */
enum class ParseStrategy {
    /** Matched OpenAI schema exactly */
    KNOWN_SCHEMA_OPENAI,

    /** Matched Gemini schema exactly */
    KNOWN_SCHEMA_GEMINI,

    /** Matched Anthropic schema exactly */
    KNOWN_SCHEMA_ANTHROPIC,

    /** Used user-provided format hint */
    USER_HINT,

    /** Used JSON walk heuristic to find best string */
    HEURISTIC_JSON_WALK,

    /** Response wasn't JSON, treated as plain text */
    NON_JSON_TEXT,

    /** User manually selected content via correction dialog */
    USER_SELECTED,

    /** Detected as an error response */
    ERROR_DETECTED,

    /** Parsing failed completely */
    FAILED
}

/**
 * Known response schemas we can detect.
 */
enum class DetectedSchema {
    OPENAI_CHAT,
    OPENAI_COMPLETION,
    GEMINI,
    ANTHROPIC,
    UNKNOWN
}

/**
 * Token usage information from the response.
 */
data class TokenUsage(
    val promptTokens: Int?,
    val completionTokens: Int?,
    val totalTokens: Int?
)