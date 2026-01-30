package com.youmeandmyself.storage

import com.youmeandmyself.storage.model.AiExchange
import com.youmeandmyself.storage.model.ExchangeMetadata
import com.youmeandmyself.storage.model.MetadataFilter
import com.youmeandmyself.storage.search.SearchScope

/**
 * Central persistence gateway for all plugin storage operations.
 *
 * All code that needs to read/write exchanges or summaries goes through here.
 * The rest of the plugin doesn't know or care how/where things are stored.
 *
 * Supports multiple modes (configured via settings):
 * - OFF: No persistence, data lives only in memory for current session
 * - LOCAL: Write to disk (project dir for raw data, system area for metadata)
 * - CLOUD: Future — remote sync
 *
 * Implementation: [LocalStorageFacade]
 */
interface StorageFacade {

    /**
     * Persist a complete AI exchange.
     * Writes raw data to project directory, metadata to system area.
     *
     * @param exchange The full exchange to store
     * @return The ID of the stored exchange, or null if storage mode is OFF
     */
    suspend fun saveExchange(exchange: AiExchange): String?

    /**
     * Retrieve full exchange by ID.
     * Reads from raw JSONL file in project directory.
     *
     * @param id Exchange ID
     * @return The exchange, or null if not found or raw data unavailable
     */
    suspend fun getExchange(id: String): AiExchange?

    /**
     * Query exchange metadata (lightweight, fast).
     * Does not load full request/response content.
     *
     * @param filter Criteria to filter by (purpose, provider, flags, date range, etc.)
     * @return List of matching metadata records, ordered by timestamp descending (newest first)
     */
    suspend fun queryMetadata(filter: MetadataFilter = MetadataFilter()): List<ExchangeMetadata>

    /**
     * Update flags or labels on an existing exchange.
     * Only modifies metadata index, not raw data.
     *
     * @param id Exchange ID
     * @param flags New flags (replaces existing). Pass null to leave unchanged.
     * @param labels New labels (replaces existing). Pass null to leave unchanged.
     * @return True if metadata was found and updated, false otherwise
     */
    suspend fun updateMetadata(id: String, flags: Set<String>? = null, labels: Set<String>? = null): Boolean

    /**
     * Search exchanges by text content.
     * Delegates to the configured [ExchangeSearchEngine].
     *
     * @param query The search text
     * @param searchIn Where to search (request, response, or both)
     * @param limit Max results to return
     * @return List of matching exchanges (full content loaded). Empty if no matches.
     */
    suspend fun searchExchanges(
        query: String,
        searchIn: SearchScope = SearchScope.BOTH,
        limit: Int = 20
    ): List<AiExchange>

    /**
     * Check raw data availability and update metadata accordingly.
     * Call this on startup or periodically to detect deleted raw files.
     *
     * @return Number of metadata records marked as rawDataAvailable=false
     */
    suspend fun validateRawDataAvailability(): Int

    /**
     * Current storage mode.
     */
    fun getMode(): StorageMode
}