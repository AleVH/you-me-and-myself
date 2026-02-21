package com.youmeandmyself.storage.search

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.youmeandmyself.dev.Dev
import com.youmeandmyself.storage.LocalStorageFacade
import java.time.Instant

/**
 * High-level search service for the Library tab.
 *
 * Builds SQL queries from [SearchCriteria], executes against SQLite,
 * and returns scored results with transparent match reasons.
 */
@Service(Service.Level.PROJECT)
class SearchService(private val project: Project) {

    private val log = Dev.logger(SearchService::class.java)

    fun search(criteria: SearchCriteria): SearchResults {
        val facade = LocalStorageFacade.getInstance(project)
        val projectId = facade.resolveProjectId()

        return try {
            facade.withReadableDatabase { db ->
                val conditions = mutableListOf("ce.project_id = ?")
                val params = mutableListOf<Any?>(projectId)
                val appliedFilters = mutableListOf<String>()

                // Track if we need to JOIN the bookmarks table
                // Two conditions require it: collectionId and isBookmarked
                val needsBookmarkJoin = criteria.collectionId != null || criteria.isBookmarked == true

                // Text search on cached assistant_text
                val queryText = criteria.query.trim()
                if (queryText.isNotBlank()) {
                    val escaped = queryText.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
                    conditions.add("ce.assistant_text LIKE ? ESCAPE '\\'")
                    params.add("%$escaped%")
                    appliedFilters.add("text: $queryText")
                }

                if (criteria.hasCode == true) {
                    conditions.add("ce.has_code_block = 1")
                    appliedFilters.add("has code")
                }

                criteria.languages?.forEach { lang ->
                    conditions.add("ce.code_languages LIKE ?")
                    params.add("%$lang%")
                    appliedFilters.add("lang: $lang")
                }

                criteria.dateFrom?.let {
                    conditions.add("ce.timestamp >= ?")
                    params.add(it.toString())
                    appliedFilters.add("from: $it")
                }

                criteria.dateTo?.let {
                    conditions.add("ce.timestamp <= ?")
                    params.add(it.toString())
                    appliedFilters.add("to: $it")
                }

                // Bookmarked filter — uses real bookmarks table instead of flags
                if (criteria.isBookmarked == true && criteria.collectionId == null) {
                    // Just needs ANY bookmark to exist — the JOIN handles it
                    appliedFilters.add("bookmarked")
                }

                // Collection filter — restricts to exchanges in a specific collection
                criteria.collectionId?.let {
                    conditions.add("b.collection_id = ?")
                    params.add(it)
                    appliedFilters.add("collection: $it")
                }

                criteria.providerId?.let {
                    conditions.add("ce.provider_id = ?")
                    params.add(it)
                    appliedFilters.add("provider: $it")
                }

                val fromClause = if (needsBookmarkJoin) {
                    "FROM chat_exchanges ce JOIN bookmarks b ON b.source_id = ce.id"
                } else {
                    "FROM chat_exchanges ce"
                }
                val whereClause = "WHERE ${conditions.joinToString(" AND ")}"

                // Total count
                val countParams = params.toTypedArray()
                val totalCount = db.queryOne(
                    "SELECT COUNT(DISTINCT ce.id) as cnt $fromClause $whereClause",
                    *countParams
                ) { rs -> rs.getInt("cnt") } ?: 0

                // Fetch page
                val fetchParams = params.toMutableList().apply {
                    add(criteria.limit)
                    add(criteria.offset)
                }.toTypedArray()

                val results = db.query(
                    """
                    SELECT DISTINCT ce.id, ce.provider_id, ce.model_id, ce.timestamp,
                           ce.prompt_tokens, ce.completion_tokens, ce.total_tokens,
                           ce.assistant_text, ce.has_code_block, ce.code_languages,
                           ce.detected_topics, ce.file_paths, ce.flags
                    $fromClause
                    $whereClause
                    ORDER BY ce.timestamp DESC
                    LIMIT ? OFFSET ?
                    """.trimIndent(),
                    *fetchParams
                ) { rs ->
                    val exchangeId = rs.getString("id")
                    val assistantText = rs.getString("assistant_text")
                    val hasCode = rs.getInt("has_code_block") == 1
                    val languages = rs.getString("code_languages")
                        ?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
                    val topics = rs.getString("detected_topics")
                        ?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
                    val flags = rs.getString("flags") ?: ""
                    // If we joined against bookmarks table, these results are bookmarked by definition.
                    // Otherwise, isBookmarked will be enriched by LibraryPanel.enrichWithBookmarkStatus()
                    val isBookmarked = needsBookmarkJoin
                    val totalTokens = rs.getInt("total_tokens").takeIf { !rs.wasNull() }

                    // Match reasons for transparency
                    val matchReasons = mutableListOf<MatchType>()
                    if (queryText.isNotBlank() && assistantText?.contains(queryText, ignoreCase = true) == true) {
                        matchReasons.add(MatchType.RESPONSE_TEXT)
                    }
                    if (criteria.hasCode == true && hasCode) matchReasons.add(MatchType.CODE_LANGUAGE)
                    if (criteria.isBookmarked == true && isBookmarked) matchReasons.add(MatchType.BOOKMARKED)
                    if (criteria.collectionId != null) matchReasons.add(MatchType.BOOKMARKED)
                    criteria.languages?.forEach { lang ->
                        if (languages.any { it.equals(lang, ignoreCase = true) }) {
                            matchReasons.add(MatchType.CODE_LANGUAGE)
                        }
                    }
                    if (matchReasons.isEmpty()) matchReasons.add(MatchType.RECENT)

                    ScoredExchange(
                        exchangeId = exchangeId,
                        profileName = rs.getString("model_id"),
                        providerId = rs.getString("provider_id"),
                        promptPreview = assistantText?.take(120),
                        responsePreview = assistantText?.take(200),
                        timestamp = Instant.parse(rs.getString("timestamp")),
                        score = matchReasons.size.toDouble(),
                        matchReasons = matchReasons,
                        hasCode = hasCode,
                        languages = languages,
                        topics = topics,
                        tokensUsed = totalTokens,
                        isBookmarked = isBookmarked
                    )
                }

                SearchResults(
                    results = results,
                    totalCount = totalCount,
                    query = criteria.query,
                    appliedFilters = appliedFilters
                )
            }
        } catch (e: Exception) {
            Dev.error(log, "search.failed", e, "query" to criteria.query)
            SearchResults(emptyList(), 0, criteria.query)
        }
    }

    companion object {
        fun getInstance(project: Project): SearchService =
            project.getService(SearchService::class.java)
    }
}