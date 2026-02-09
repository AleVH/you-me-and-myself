package com.youmeandmyself.storage.model

import java.time.Instant

/**
 * Lightweight metadata about an AI exchange — what's stored in SQLite.
 *
 * This is the "fast path" data: everything needed for listing, filtering,
 * and displaying exchanges WITHOUT reading the full content from JSONL.
 *
 * ## What Changed from v1
 *
 * - Added [projectId] — required for the centralized multi-project storage
 * - [rawFile] now contains weekly filenames (exchanges-2026-W05.jsonl)
 *   instead of monthly ones (exchanges-2026-01.jsonl)
 * - Flags and labels are stored as comma-separated strings in SQLite
 *   (simpler than JSON arrays, easy to query with LIKE)
 *
 * ## Relationship to SQLite
 *
 * Maps directly to the `chat_exchanges` table. The facade converts between
 * this domain model and SQL rows — no separate serializable class needed.
 *
 * @param id Unique exchange identifier
 * @param projectId Project this exchange belongs to (FK → projects.id)
 * @param timestamp When the exchange occurred
 * @param providerId AI provider identifier (e.g., "deepseek")
 * @param modelId Model identifier (e.g., "deepseek-chat")
 * @param purpose What the exchange was for (CHAT, SUMMARY, etc.)
 * @param tokensUsed Token count if known, null otherwise
 * @param flags User-applied flags (starred, archived, etc.)
 * @param labels User-applied labels (freeform strings)
 * @param rawFile JSONL filename containing the full content
 * @param rawDataAvailable Whether the JSONL file still exists on disk
 */
data class ExchangeMetadata(
    val id: String,
    val projectId: String,
    val timestamp: Instant,
    val providerId: String,
    val modelId: String,
    val purpose: ExchangePurpose,
    val tokensUsed: Int?,
    val flags: MutableSet<String>,
    val labels: MutableSet<String>,
    val rawFile: String,
    val rawDataAvailable: Boolean
) {
    companion object {
        /**
         * Encode a set of strings as a comma-separated string for SQLite storage.
         *
         * Empty set → empty string (not null), so we can always decode without null checks.
         * Individual values must not contain commas — this is enforced at the UI layer.
         */
        fun encodeSet(values: Set<String>): String = values.joinToString(",")

        /**
         * Decode a comma-separated string from SQLite back into a mutable set.
         *
         * Empty string → empty set. Handles null gracefully (returns empty set).
         */
        fun decodeSet(csv: String?): MutableSet<String> {
            if (csv.isNullOrBlank()) return mutableSetOf()
            return csv.split(",").filter { it.isNotBlank() }.toMutableSet()
        }
    }
}