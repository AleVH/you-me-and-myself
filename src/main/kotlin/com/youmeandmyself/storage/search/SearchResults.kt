package com.youmeandmyself.storage.search

import java.time.Instant

/**
 * Why a result matched a search query.
 * Shown to users for transparency ("why did this come up?").
 */
enum class MatchType {
    PROMPT_TEXT,
    RESPONSE_TEXT,
    CODE_LANGUAGE,
    TOPIC,
    FILE_PATH,
    BOOKMARKED,
    RECENT
}

/**
 * A single exchange result with relevance scoring.
 */
data class ScoredExchange(
    val exchangeId: String,
    val profileName: String?,
    val providerId: String?,
    val promptPreview: String?,
    val responsePreview: String?,
    val timestamp: Instant,
    val score: Double,
    val matchReasons: List<MatchType>,
    val hasCode: Boolean = false,
    val languages: List<String> = emptyList(),
    val topics: List<String> = emptyList(),
    val tokensUsed: Int? = null,
    val isBookmarked: Boolean = false
)

/**
 * Container for search results with metadata about the query.
 */
data class SearchResults(
    val results: List<ScoredExchange>,
    val totalCount: Int,
    val query: String,
    val appliedFilters: List<String> = emptyList()
)