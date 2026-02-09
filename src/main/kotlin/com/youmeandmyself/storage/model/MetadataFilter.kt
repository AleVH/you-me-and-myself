package com.youmeandmyself.storage.model

import java.time.Instant

/**
 * Criteria for querying exchange metadata.
 *
 * All fields are optional. When multiple fields are set, they combine with AND logic.
 * Pass an empty MetadataFilter() to get all records (subject to [limit]).
 *
 * ## How It Maps to SQL
 *
 * Each non-null field becomes a WHERE clause condition:
 * ```sql
 * SELECT * FROM chat_exchanges
 * WHERE project_id = ?       -- if projectId set
 *   AND purpose = ?           -- if purpose set
 *   AND provider_id = ?       -- if providerId set
 *   AND timestamp > ?         -- if after set
 *   AND timestamp < ?         -- if before set
 *   AND raw_available = ?     -- if rawDataAvailable set
 * ORDER BY timestamp DESC
 * LIMIT ?
 * ```
 *
 * Flags and labels use LIKE queries since they're stored as comma-separated strings:
 * ```sql
 *   AND flags LIKE '%starred%'   -- if hasFlag set
 *   AND labels LIKE '%urgent%'   -- if hasLabel set
 * ```
 *
 * @param projectId Filter by project (most common filter)
 * @param purpose Filter by exchange purpose (CHAT, SUMMARY, etc.)
 * @param providerId Filter by AI provider
 * @param modelId Filter by specific model
 * @param hasFlag Filter for exchanges with a specific flag
 * @param hasLabel Filter for exchanges with a specific label
 * @param after Only include exchanges after this timestamp
 * @param before Only include exchanges before this timestamp
 * @param rawDataAvailable Filter by whether raw JSONL content is still available
 * @param limit Maximum number of results (default 100)
 */
data class MetadataFilter(
    val projectId: String? = null,
    val purpose: ExchangePurpose? = null,
    val providerId: String? = null,
    val modelId: String? = null,
    val hasFlag: String? = null,
    val hasLabel: String? = null,
    val after: Instant? = null,
    val before: Instant? = null,
    val rawDataAvailable: Boolean? = null,
    val limit: Int = 100
)