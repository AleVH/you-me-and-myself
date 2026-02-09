package com.youmeandmyself.storage

/**
 * Controls how the plugin persists data.
 *
 * OFF - No persistence. Data lives only in memory for current session.
 * LOCAL - Write to disk (centralized storage root for JSONL + SQLite).
 * CLOUD - Future: remote sync for cross-device access and backup.
 */
enum class StorageMode {
    OFF,
    LOCAL,
    CLOUD
}