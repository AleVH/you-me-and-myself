package com.youmeandmyself.storage.model

import java.time.Instant

/**
 * Filter criteria for querying exchange metadata.
 * All fields are optional — null means "don't filter by this field".
 *
 * Filters are combined with AND logic (all specified criteria must match).
 *
 * @property purpose Filter by exchange purpose
 * @property providerId Filter by AI provider
 * @property modelId Filter by specific model
 * @property hasFlag Only include exchanges that have this flag
 * @property hasLabel Only include exchanges that have this label
 * @property after Only include exchanges after this timestamp
 * @property before Only include exchanges before this timestamp
 * @property rawDataAvailable Filter by raw data availability (null = don't filter)
 * @property limit Maximum number of results to return
 */
data class MetadataFilter(
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