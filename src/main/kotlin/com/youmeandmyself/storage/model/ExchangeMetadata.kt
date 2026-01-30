package com.youmeandmyself.storage.model

import java.time.Instant

/**
 * Lightweight index record for an exchange.
 * Stored in IntelliJ's system area for fast querying without loading full content.
 *
 * Links to raw data via [rawFile] — if that file is deleted, [rawDataAvailable] becomes false
 * but this metadata (and any derived value like summaries) is retained.
 *
 * @property id Matches [AiExchange.id]
 * @property timestamp When the exchange occurred
 * @property providerId Which AI provider handled this
 * @property modelId Specific model used
 * @property purpose Why this exchange happened
 * @property tokensUsed Token count (copied here to avoid loading raw data for stats)
 * @property flags System or user flags (e.g., "starred", "archived")
 * @property labels User-defined tags for organization
 * @property rawFile Filename in project directory containing the full exchange (e.g., "exchanges-2026-01.jsonl")
 * @property rawDataAvailable False if the raw file has been deleted
 */
data class ExchangeMetadata(
    val id: String,
    val timestamp: Instant,
    val providerId: String,
    val modelId: String,
    val purpose: ExchangePurpose,
    val tokensUsed: Int?,
    val flags: MutableSet<String> = mutableSetOf(),
    val labels: MutableSet<String> = mutableSetOf(),
    val rawFile: String,
    val rawDataAvailable: Boolean = true
)