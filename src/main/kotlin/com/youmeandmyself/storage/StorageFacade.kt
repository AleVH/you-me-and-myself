package com.youmeandmyself.storage

import com.youmeandmyself.context.orchestrator.config.SummaryConfig
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
 * ## Project Awareness
 *
 * The new centralized storage architecture keeps data from all projects under
 * a single storage root. Methods that write or read data require a projectId
 * so the facade knows which project subfolder and database records to target.
 *
 * The IntelliJ [Project] instance is still used for service scoping (one facade
 * per open project), but the projectId string is what links data in storage.
 *
 * ## Storage Modes
 *
 * - OFF: No persistence, data lives only in memory for current session
 * - LOCAL: Write to disk (centralized root for JSONL + SQLite for metadata)
 * - CLOUD: Future — remote sync
 *
 * Implementation: [LocalStorageFacade]
 */
interface StorageFacade {

    /**
     * Persist a complete AI exchange.
     *
     * Writes raw content to a weekly JSONL file under chat/{projectId}/,
     * and inserts metadata into the chat_exchanges SQLite table.
     *
     * @param exchange The full exchange to store
     * @param projectId The project this exchange belongs to
     * @return The ID of the stored exchange, or null if storage mode is OFF
     */
    suspend fun saveExchange(exchange: AiExchange, projectId: String): String?

    /**
     * Retrieve full exchange by ID.
     *
     * Looks up the metadata in SQLite to find which JSONL file holds the content,
     * then reads the full exchange from that file.
     *
     * @param id Exchange ID
     * @param projectId The project this exchange belongs to
     * @return The exchange, or null if not found or raw data unavailable
     */
    suspend fun getExchange(id: String, projectId: String): AiExchange?

    /**
     * Query exchange metadata (lightweight, fast).
     *
     * Reads from SQLite only — never touches JSONL files.
     * Does not load full request/response content.
     *
     * @param filter Criteria to filter by (purpose, provider, flags, date range, etc.)
     * @return List of matching metadata records, ordered by timestamp descending (newest first)
     */
    suspend fun queryMetadata(filter: MetadataFilter = MetadataFilter()): List<ExchangeMetadata>

    /**
     * Update flags or labels on an existing exchange.
     *
     * Only modifies the metadata in SQLite, not raw JSONL data.
     *
     * @param id Exchange ID
     * @param flags New flags (replaces existing). Pass null to leave unchanged.
     * @param labels New labels (replaces existing). Pass null to leave unchanged.
     * @return True if metadata was found and updated, false otherwise
     */
    suspend fun updateMetadata(id: String, flags: Set<String>? = null, labels: Set<String>? = null): Boolean

    /**
     * Search exchanges by text content.
     *
     * Delegates to the configured search engine for finding matching IDs,
     * then loads full content from JSONL for matches.
     *
     * @param query The search text
     * @param projectId The project to search within
     * @param searchIn Where to search (request, response, or both)
     * @param limit Max results to return
     * @return List of matching exchanges (full content loaded). Empty if no matches.
     */
    suspend fun searchExchanges(
        query: String,
        projectId: String,
        searchIn: SearchScope = SearchScope.BOTH,
        limit: Int = 20
    ): List<AiExchange>

    /**
     * Check raw data availability and update metadata accordingly.
     *
     * Scans JSONL files referenced by chat_exchanges and marks any missing
     * files as raw_available = 0 in SQLite.
     *
     * @param projectId The project to validate
     * @return Number of metadata records marked as raw_available = 0
     */
    suspend fun validateRawDataAvailability(projectId: String): Int

    /**
     * Current storage mode.
     */
    fun getMode(): StorageMode

    // ── Summary Config ──

    /**
     * Load the summary configuration for a project.
     *
     * @param projectId The project's unique identifier
     * @return The stored config, or null if no config exists yet
     */
    fun loadSummaryConfig(projectId: String): SummaryConfig?

    /**
     * Save (upsert) the summary configuration for a project.
     *
     * @param projectId The project's unique identifier
     * @param config The configuration to persist
     */
    fun saveSummaryConfig(projectId: String, config: SummaryConfig)
}