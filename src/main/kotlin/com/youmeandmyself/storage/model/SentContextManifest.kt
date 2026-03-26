package com.youmeandmyself.storage.model

import com.youmeandmyself.ai.chat.context.ContextKind
import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * Record of what context was sent in a specific conversation turn.
 *
 * ## Purpose
 *
 * After each Send, the staging area is snapshotted and the entries are
 * recorded here. This manifest serves two purposes:
 *
 * 1. **Sidebar display (Phase 3):** The context sidebar shows all context
 *    sent across the entire conversation. Each manifest corresponds to one
 *    turn's contribution to the sidebar.
 *
 * 2. **Staleness detection (Phase 3):** When a file changes, the staleness
 *    tracker checks if that file was sent in any previous turn by scanning
 *    the manifests. If found, the sidebar entry is flagged as stale.
 *
 * ## Persistence
 *
 * Persisted alongside conversation data:
 * - **JSONL:** Embedded in the exchange record (as part of ExchangeRequest)
 * - **SQLite:** For fast queries ("what context was sent in conversation X?")
 *
 * ## Lifecycle
 *
 * Created in Phase 2 (staging area snapshot at send time).
 * Consumed by Phase 3 (sidebar + staleness tracking).
 *
 * @property turnIndex The conversation turn this manifest belongs to (0-based)
 * @property entries The context entries that were sent in this turn
 *
 * @see com.youmeandmyself.ai.chat.context.ContextStagingService — creates manifests at send time
 */
data class SentContextManifest(
    val turnIndex: Int,
    val entries: List<SentContextEntry>
)

/**
 * A single entry in a [SentContextManifest].
 *
 * Lightweight record of what was sent — carries identity and hash for
 * staleness detection, but NOT the full content (that's in the JSONL
 * exchange record and can be retrieved if needed).
 *
 * @property entryId Unique ID of the context entry (matches [ContextEntry.id])
 * @property path File path (null for non-file entries like framework info)
 * @property contentHash Hash of the content at the time it was sent (for staleness comparison)
 * @property kind Entry type: SUMMARY, RAW, or OTHER
 * @property sentAt When this entry was included in the request
 */
@Serializable
data class SentContextEntry(
    val entryId: String,
    val path: String?,
    val contentHash: String?,
    val kind: String,   // Stored as string for serialization stability (not coupled to ContextKind enum)
    val sentAt: Long    // Epoch millis (Instant is not directly serializable with kotlinx)
) {
    companion object {
        /**
         * Create a [SentContextEntry] from a [ContextEntry] at send time.
         *
         * Convenience factory that extracts the relevant fields from the
         * full context entry for lightweight persistence.
         *
         * @param entry The full context entry from the staging area snapshot
         * @return A lightweight sent record
         */
        fun from(entry: com.youmeandmyself.ai.chat.context.ContextEntry): SentContextEntry {
            return SentContextEntry(
                entryId = entry.id,
                path = entry.path,
                contentHash = entry.contentHash,
                kind = entry.kind.name,
                sentAt = entry.gatheredAt.toEpochMilli()
            )
        }
    }
}
