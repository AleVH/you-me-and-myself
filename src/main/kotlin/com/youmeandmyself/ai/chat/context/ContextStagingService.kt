package com.youmeandmyself.ai.chat.context

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.youmeandmyself.dev.Dev
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages the context staging area — a per-tab preview of what context
 * will be included in the next message.
 *
 * ## Purpose
 *
 * The badge tray is the staging area. Badges represent "this is what will
 * be included in the next message if you hit Send." The staging service
 * holds the state behind those badges.
 *
 * ## Lifecycle
 *
 * 1. User types → background gathering starts → entries are added progressively
 * 2. Badges appear in the tray as entries arrive
 * 3. User can remove individual entries (Pro tier only)
 * 4. User clicks Send → staging area is snapshotted → snapshot becomes RequestBlocks.context
 * 5. After send: staging area clears, entries migrate to sidebar (Phase 3)
 *
 * ## Per-Tab State
 *
 * Each tab has its own staging area. Entries are keyed by tabId. Switching
 * tabs does not affect other tabs' staging state.
 *
 * ## Early-Exit Optimization (Design Doc §10.2)
 *
 * Before adding an entry, [addEntry] checks if the same file path + content
 * hash already exists in the tab's staging area. If yes, the entry is skipped
 * entirely — no gathering, no summarization, no wasted cycles.
 *
 * ## Thread Safety
 *
 * The service uses [ConcurrentHashMap] for the top-level tab mapping.
 * Per-tab entry lists are synchronized on the list itself. This is safe
 * because:
 * - Background gathering adds entries from a coroutine (single writer per tab)
 * - UI removal happens from the EDT via bridge dispatch (serialized per tab)
 * - Snapshot is a copy — reads don't block writes
 *
 * @param project The IntelliJ project this service is scoped to
 *
 * @see ContextBlock — the structured output of [snapshot]
 * @see com.youmeandmyself.ai.chat.orchestrator.RequestBlocks — consumes the snapshot
 */
@Service(Service.Level.PROJECT)
class ContextStagingService(private val project: Project) {

    private val log = Dev.logger(ContextStagingService::class.java)

    /**
     * Per-tab staging state. Each tab has its own list of context entries
     * that will be included in the next message.
     *
     * Key: tabId (from the frontend tab system)
     * Value: mutable list of entries currently staged for that tab
     */
    private val tabEntries = ConcurrentHashMap<String, MutableList<ContextEntry>>()

    // ── Public API ──────────────────────────────────────────────────────

    /**
     * Add a context entry to a tab's staging area.
     *
     * Called by the background gatherer as entries are detected and enriched.
     * Entries appear progressively — each call may trigger a CONTEXT_BADGE_UPDATE
     * bridge event (handled by the caller, not this service).
     *
     * ## Early-Exit Hash Check
     *
     * If an entry with the same [ContextEntry.path] and [ContextEntry.contentHash]
     * already exists in the staging area, the new entry is silently skipped.
     * This avoids re-gathering and re-summarizing files that are already staged
     * with identical content.
     *
     * @param tabId The tab to add the entry to
     * @param entry The context entry to stage
     * @return true if the entry was added, false if it was skipped (duplicate)
     */
    fun addEntry(tabId: String, entry: ContextEntry): Boolean {
        val entries = tabEntries.getOrPut(tabId) { mutableListOf() }

        synchronized(entries) {
            // Early-exit: skip if same path + hash already staged
            if (entry.path != null && entry.contentHash != null) {
                val duplicate = entries.any { existing ->
                    existing.path == entry.path && existing.contentHash == entry.contentHash
                }
                if (duplicate) {
                    Dev.info(log, "staging.skip_duplicate",
                        "tabId" to tabId,
                        "path" to entry.path,
                        "reason" to "same path + hash already staged"
                    )
                    return false
                }
            }

            entries.add(entry)

            Dev.info(log, "staging.entry_added",
                "tabId" to tabId,
                "entryId" to entry.id,
                "name" to entry.name,
                "kind" to entry.kind.name,
                "stagedCount" to entries.size
            )
            return true
        }
    }

    /**
     * Remove a context entry from a tab's staging area.
     *
     * Called when the user dismisses a badge (X button). The caller is
     * responsible for tier gating — this method does not check permissions.
     *
     * @param tabId The tab to remove the entry from
     * @param entryId The unique ID of the entry to remove
     * @return true if the entry was found and removed, false if not found
     */
    fun removeEntry(tabId: String, entryId: String): Boolean {
        val entries = tabEntries[tabId] ?: return false

        synchronized(entries) {
            val removed = entries.removeAll { it.id == entryId }

            if (removed) {
                Dev.info(log, "staging.entry_removed",
                    "tabId" to tabId,
                    "entryId" to entryId,
                    "remainingCount" to entries.size
                )
            } else {
                Dev.info(log, "staging.entry_not_found",
                    "tabId" to tabId,
                    "entryId" to entryId
                )
            }
            return removed
        }
    }

    /**
     * Snapshot the current staging area for a tab.
     *
     * Called at send time. Returns a [ContextBlock] containing all staged
     * entries, partitioned by kind (summaries, raw, other). The snapshot is
     * a copy — further modifications to the staging area do not affect it.
     *
     * @param tabId The tab to snapshot
     * @return A ContextBlock with all staged entries, or [ContextBlock.empty] if nothing is staged
     */
    fun snapshot(tabId: String): ContextBlock {
        val entries = tabEntries[tabId] ?: return ContextBlock.empty()

        synchronized(entries) {
            if (entries.isEmpty()) return ContextBlock.empty()

            // Partition entries by kind into the three ContextBlock lists
            val summaries = mutableListOf<ContextEntry>()
            val raw = mutableListOf<ContextEntry>()
            val other = mutableListOf<ContextEntry>()

            for (entry in entries) {
                when (entry.kind) {
                    ContextKind.SUMMARY -> summaries.add(entry)
                    ContextKind.RAW -> raw.add(entry)
                    ContextKind.OTHER -> other.add(entry)
                }
            }

            Dev.info(log, "staging.snapshot",
                "tabId" to tabId,
                "summaries" to summaries.size,
                "raw" to raw.size,
                "other" to other.size,
                "totalTokens" to entries.sumOf { it.tokenEstimate }
            )

            // Return copies so the snapshot is immutable
            return ContextBlock(
                summaries = summaries.toList(),
                raw = raw.toList(),
                other = other.toList()
            )
        }
    }

    /**
     * Mark all entries matching a file path as stale across all tabs.
     *
     * Called by [VfsSummaryWatcher] when a file's content changes. Any
     * staging area entry whose [ContextEntry.path] matches the changed
     * file is marked stale.
     *
     * @param filePath The path of the file that changed
     * @return List of (tabId, entryId) pairs that were marked stale
     */
    fun markEntriesStale(filePath: String): List<Pair<String, String>> {
        val staleEntries = mutableListOf<Pair<String, String>>()

        for ((tabId, entries) in tabEntries) {
            synchronized(entries) {
                for (i in entries.indices) {
                    val entry = entries[i]
                    if (entry.path == filePath && !entry.isStale) {
                        entries[i] = entry.copy(isStale = true)
                        staleEntries.add(tabId to entry.id)
                    }
                }
            }
        }

        if (staleEntries.isNotEmpty()) {
            Dev.info(log, "staging.staleness_detected",
                "filePath" to filePath,
                "affectedEntries" to staleEntries.size
            )
        }

        return staleEntries
    }

    /**
     * Clear all staged entries for a tab.
     *
     * Called after send — the staging area is emptied so the badge tray
     * clears. The sent entries are recorded separately in [SentContextManifest]
     * by the orchestrator before calling this.
     *
     * @param tabId The tab to clear
     */
    fun clear(tabId: String) {
        val entries = tabEntries.remove(tabId)
        val count = entries?.size ?: 0

        Dev.info(log, "staging.cleared",
            "tabId" to tabId,
            "entriesCleared" to count
        )
    }

    /**
     * Get the current staging state for a tab.
     *
     * Used for UI rendering — returns the list of entries currently staged.
     * Returns a copy to prevent external modification.
     *
     * @param tabId The tab to query
     * @return List of currently staged entries, or empty list if nothing is staged
     */
    fun getState(tabId: String): List<ContextEntry> {
        val entries = tabEntries[tabId] ?: return emptyList()
        synchronized(entries) {
            return entries.toList()
        }
    }

    /**
     * Check if a tab has any staged entries.
     *
     * @param tabId The tab to check
     * @return true if the tab has at least one staged entry
     */
    fun hasEntries(tabId: String): Boolean {
        val entries = tabEntries[tabId] ?: return false
        synchronized(entries) {
            return entries.isNotEmpty()
        }
    }

    companion object {
        /**
         * Get the ContextStagingService instance for a project.
         *
         * Uses IntelliJ's service infrastructure — one instance per project.
         */
        fun getInstance(project: Project): ContextStagingService {
            return project.getService(ContextStagingService::class.java)
        }
    }
}
