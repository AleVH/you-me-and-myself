package com.youmeandmyself.ai.providers.parsing

/**
 * Confidence level in our parsing result.
 *
 * Used to decide whether to show correction options to the user.
 */
enum class Confidence {
    /** No confidence - couldn't find anything reasonable */
    NONE,

    /** Low confidence - found something but very uncertain */
    LOW,

    /** Medium confidence - heuristic found a plausible match */
    MEDIUM,

    /** High confidence - matched a known schema or user hint */
    HIGH
}