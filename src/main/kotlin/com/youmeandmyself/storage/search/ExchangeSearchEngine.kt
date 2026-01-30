package com.youmeandmyself.storage.search

import com.youmeandmyself.storage.model.AiExchange

/**
 * Pluggable search engine for exchange content.
 *
 * Implementations can range from simple substring matching to full-text indexing.
 * The facade doesn't care how search works internally — swap implementations
 * without changing calling code.
 *
 * Default implementation: [SimpleSearchEngine] (substring matching, no index).
 * Future options: Lucene-based, SQLite FTS, external service, etc.
 */
interface ExchangeSearchEngine {

    /**
     * Search exchanges by text content.
     *
     * @param query The search text (implementation decides matching rules)
     * @param searchIn Where to search (request, response, or both)
     * @param limit Max results to return
     * @return List of exchange IDs that match, ordered by relevance (best first).
     *         Empty list if no matches or search unavailable.
     */
    suspend fun search(
        query: String,
        searchIn: SearchScope = SearchScope.BOTH,
        limit: Int = 20
    ): List<String>

    /**
     * Notify the engine that a new exchange was saved.
     * Implementations may use this to update indexes.
     *
     * @param exchange The newly saved exchange
     */
    suspend fun onExchangeSaved(exchange: AiExchange)

    /**
     * Notify the engine that raw data for an exchange is no longer available.
     * Implementations may use this to clean up indexes.
     *
     * @param id The exchange ID whose raw data was removed
     */
    suspend fun onRawDataRemoved(id: String)
}