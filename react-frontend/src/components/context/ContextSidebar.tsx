/**
 * Context Sidebar — collapsible panel showing all sent context in the conversation.
 *
 * ## Purpose (§5.1)
 *
 * In a 75-turn conversation with context added at 5 different exchanges,
 * scrolling through messages to find context badges is unacceptable UX.
 * The sidebar gives a single overview; per-message badges give per-turn detail.
 *
 * ## Behaviour (§5.2)
 *
 * - Always available but collapsible — not in the way, one click to open
 * - Shows all context entries across the conversation, regardless of turn
 * - Each entry: file/element name, kind, turn, state (fresh/stale)
 * - Staleness indicators when a file changes after being sent
 * - Actions: dismiss staleness, refresh stale entry
 * - Deduplication: same file sent in multiple turns shows once with count
 * - Ordering: by origin distance (nearest context at top)
 *
 * ## Layout
 *
 * Collapsed: 32px-wide strip with a toggle icon.
 * Expanded: 240px-wide panel with entry list and actions.
 */
import { useMemo } from "react";
import type { ContextFileDetail } from "../../bridge/types";
import "./ContextSidebar.css";

/** A sent context entry with deduplication info. */
interface DeduplicatedEntry {
    /** Most recent version of this entry. */
    entry: ContextFileDetail;
    /** How many turns this file appeared in. */
    turnCount: number;
    /** All turn indices where this file was sent. */
    turns: number[];
    /** Whether this entry is currently stale. */
    isStale: boolean;
}

export interface ContextSidebarProps {
    /** All sent context entries for the current conversation. */
    sentContext: ContextFileDetail[];
    /** Whether the sidebar is currently expanded. */
    isExpanded: boolean;
    /** Toggle sidebar open/closed. */
    onToggle: () => void;
    /** Called when user clicks an entry to navigate to source. */
    onEntryClick?: (entry: ContextFileDetail) => void;
    /** Called when user dismisses a staleness flag. */
    onDismissStaleness?: (entryId: string) => void;
    /** Called when user requests a refresh of a stale entry. */
    onRefreshEntry?: (entry: ContextFileDetail) => void;
}

/**
 * Deduplicate entries by file path.
 *
 * Same file sent in multiple turns shows once with a count.
 * The most recent version (highest turn index) is kept as the display entry.
 */
function deduplicateEntries(entries: ContextFileDetail[]): DeduplicatedEntry[] {
    const byPath = new Map<string, DeduplicatedEntry>();

    for (const entry of entries) {
        const key = entry.path;
        const existing = byPath.get(key);

        if (existing) {
            existing.turnCount++;
            existing.turns.push(0); // Turn index not available on ContextFileDetail yet
            // Keep the most recent version
            existing.entry = entry;
            // Stale if any version is stale
            if (entry.isStale) existing.isStale = true;
        } else {
            byPath.set(key, {
                entry,
                turnCount: 1,
                turns: [0],
                isStale: entry.isStale ?? false,
            });
        }
    }

    return Array.from(byPath.values());
}

/** Extract just the filename from a full path. */
function fileName(path: string): string {
    const parts = path.split("/");
    return parts[parts.length - 1] || path;
}

/** Short scope label for display. */
function scopeLabel(scope: string): string {
    switch (scope) {
        case "method": return "method";
        case "class": return "class";
        case "file": return "file";
        default: return scope;
    }
}

export default function ContextSidebar({
    sentContext,
    isExpanded,
    onToggle,
    onEntryClick,
    onDismissStaleness,
    onRefreshEntry,
}: ContextSidebarProps) {
    const deduplicated = useMemo(
        () => deduplicateEntries(sentContext),
        [sentContext]
    );

    const entryCount = deduplicated.length;
    const staleCount = deduplicated.filter((d) => d.isStale).length;

    // ── Collapsed state: thin strip with toggle ──
    if (!isExpanded) {
        return (
            <div
                className="ymm-context-sidebar ymm-context-sidebar--collapsed"
                onClick={onToggle}
                title={`Context sidebar (${entryCount} entries${staleCount > 0 ? `, ${staleCount} stale` : ""})`}
            >
                <span className="ymm-context-sidebar__toggle-icon">◂</span>
                {entryCount > 0 && (
                    <span className="ymm-context-sidebar__collapsed-count">
                        {entryCount}
                    </span>
                )}
                {staleCount > 0 && (
                    <span className="ymm-context-sidebar__collapsed-stale">
                        !
                    </span>
                )}
            </div>
        );
    }

    // ── Expanded state: full sidebar ──
    return (
        <div className="ymm-context-sidebar ymm-context-sidebar--expanded">
            {/* Header */}
            <div className="ymm-context-sidebar__header">
                <span className="ymm-context-sidebar__title">
                    Context ({entryCount})
                </span>
                <button
                    className="ymm-context-sidebar__close-btn"
                    onClick={onToggle}
                    title="Collapse sidebar"
                >
                    ▸
                </button>
            </div>

            {/* Entry list */}
            <div className="ymm-context-sidebar__list">
                {deduplicated.length === 0 ? (
                    <div className="ymm-context-sidebar__empty">
                        No context sent yet
                    </div>
                ) : (
                    deduplicated.map((dedup) => (
                        <div
                            key={dedup.entry.path}
                            className={`ymm-context-sidebar__entry${
                                dedup.isStale ? " ymm-context-sidebar__entry--stale" : ""
                            }`}
                            onClick={() => onEntryClick?.(dedup.entry)}
                            title={dedup.entry.path}
                        >
                            {/* File name + scope */}
                            <div className="ymm-context-sidebar__entry-name">
                                <span className="ymm-context-sidebar__entry-file">
                                    {fileName(dedup.entry.path)}
                                </span>
                                {dedup.entry.scope !== "file" && (
                                    <span className="ymm-context-sidebar__entry-scope">
                                        {scopeLabel(dedup.entry.scope)}
                                    </span>
                                )}
                            </div>

                            {/* Metadata row: kind, turn count, tokens */}
                            <div className="ymm-context-sidebar__entry-meta">
                                <span className="ymm-context-sidebar__entry-kind">
                                    {dedup.entry.kind}
                                </span>
                                {dedup.turnCount > 1 && (
                                    <span className="ymm-context-sidebar__entry-turns">
                                        ×{dedup.turnCount}
                                    </span>
                                )}
                                {dedup.entry.tokens != null && dedup.entry.tokens > 0 && (
                                    <span className="ymm-context-sidebar__entry-tokens">
                                        {dedup.entry.tokens}t
                                    </span>
                                )}
                            </div>

                            {/* Staleness actions */}
                            {dedup.isStale && (
                                <div className="ymm-context-sidebar__entry-actions">
                                    <button
                                        className="ymm-context-sidebar__action-btn ymm-context-sidebar__action-btn--dismiss"
                                        onClick={(e) => {
                                            e.stopPropagation();
                                            if (dedup.entry.id) {
                                                onDismissStaleness?.(dedup.entry.id);
                                            }
                                        }}
                                        title="Dismiss staleness"
                                    >
                                        ✕
                                    </button>
                                    <button
                                        className="ymm-context-sidebar__action-btn ymm-context-sidebar__action-btn--refresh"
                                        onClick={(e) => {
                                            e.stopPropagation();
                                            onRefreshEntry?.(dedup.entry);
                                        }}
                                        title="Refresh this context"
                                    >
                                        ↻
                                    </button>
                                </div>
                            )}
                        </div>
                    ))
                )}
            </div>
        </div>
    );
}
