package com.youmeandmyself.storage.search

import com.intellij.openapi.diagnostic.Logger
import com.youmeandmyself.dev.Dev
import com.youmeandmyself.storage.model.AiExchange
import java.util.concurrent.ConcurrentHashMap

/**
 * Basic search implementation using in-memory index and substring matching.
 *
 * How it works:
 * - Maintains an in-memory map of exchange ID -> searchable text
 * - On search, scans all entries for substring matches (case-insensitive)
 * - No persistence of the index itself — rebuilt from exchanges on startup
 *
 * Limitations:
 * - No ranking/relevance scoring (matches are returned in insertion order)
 * - No fuzzy matching or stemming
 * - Memory usage grows with number of exchanges
 *
 * This is intentionally simple for v1. Replace with Lucene, SQLite FTS,
 * or similar when performance becomes an issue.
 */
class SimpleSearchEngine : ExchangeSearchEngine {

    private val log = Logger.getInstance(SimpleSearchEngine::class.java)

    /**
     * In-memory index: exchange ID -> IndexEntry
     * Contains pre-extracted searchable text to avoid re-parsing on every search.
     */
    private val index = ConcurrentHashMap<String, IndexEntry>()

    private data class IndexEntry(
        val id: String,
        val requestText: String,
        val responseText: String,
        val timestamp: Long  // For ordering results (newer first)
    )

    override suspend fun search(
        query: String,
        searchIn: SearchScope,
        limit: Int
    ): List<String> {
        if (query.isBlank()) {
            Dev.info(log, "search.skip", "reason" to "blank_query")
            return emptyList()
        }

        val queryLower = query.lowercase()
        Dev.info(log, "search.start",
            "query" to Dev.preview(query, 50),
            "scope" to searchIn,
            "indexSize" to index.size
        )

        val matches = index.values
            .filter { entry -> matchesQuery(entry, queryLower, searchIn) }
            .sortedByDescending { it.timestamp }
            .take(limit)
            .map { it.id }

        Dev.info(log, "search.complete",
            "query" to Dev.preview(query, 50),
            "matches" to matches.size
        )

        return matches
    }

    override suspend fun onExchangeSaved(exchange: AiExchange) {
        // For now, index only the request input.
        // Response content will be indexed once the extractor module exists.
        val entry = IndexEntry(
            id = exchange.id,
            requestText = exchange.request.input.lowercase(),
            responseText = "",  // TODO: Index extracted content when extractor is ready
            timestamp = exchange.timestamp.toEpochMilli()
        )
        index[exchange.id] = entry

        Dev.info(log, "index.add",
            "id" to exchange.id,
            "requestLen" to exchange.request.input.length,
            "responseText" to "pending_extractor"
        )
    }

    override suspend fun onRawDataRemoved(id: String) {
        val removed = index.remove(id)
        Dev.info(log, "index.remove",
            "id" to id,
            "found" to (removed != null)
        )
    }

    /**
     * Rebuild the index from a list of exchanges.
     * Call this on startup after loading metadata/raw data.
     */
    suspend fun rebuildIndex(exchanges: List<AiExchange>) {
        Dev.info(log, "index.rebuild.start", "count" to exchanges.size)

        index.clear()
        exchanges.forEach { exchange ->
            // For now, index only the request input.
            // Response content will be indexed once the extractor module exists.
            index[exchange.id] = IndexEntry(
                id = exchange.id,
                requestText = exchange.request.input.lowercase(),
                responseText = "",  // TODO: Index extracted content when extractor is ready
                timestamp = exchange.timestamp.toEpochMilli()
            )
        }

        Dev.info(log, "index.rebuild.complete", "indexSize" to index.size)
    }

    /**
     * Clear the entire index.
     * Useful for testing or when switching projects.
     */
    fun clear() {
        val previousSize = index.size
        index.clear()
        Dev.info(log, "index.clear", "previousSize" to previousSize)
    }

    /**
     * Current index size (for diagnostics).
     */
    fun indexSize(): Int = index.size

    // -------------------- Internal --------------------

    private fun matchesQuery(entry: IndexEntry, queryLower: String, scope: SearchScope): Boolean {
        return when (scope) {
            SearchScope.REQUEST_ONLY -> entry.requestText.contains(queryLower)
            SearchScope.RESPONSE_ONLY -> entry.responseText.contains(queryLower)
            SearchScope.BOTH -> entry.requestText.contains(queryLower) || entry.responseText.contains(queryLower)
        }
    }
}