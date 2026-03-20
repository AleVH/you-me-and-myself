/**
 * Tab bar component for conversation management.
 *
 * ## R4 — Conversation Tabs
 *
 * Renders a horizontal strip of conversation tabs. Each tab shows:
 * - Title (auto-generated from first user message, or "New Chat")
 * - Context usage indicator chip (amber ≥75%, red ≥90%) [PLACEHOLDER]
 * - Close button (×)
 * - Active indicator (highlighted background)
 *
 * Tabs are always single-line — titles are truncated with ellipsis
 * when space is limited. The tab strip scrolls horizontally if needed.
 *
 * The "+" button creates a new tab. Disabled when maxTabs is reached.
 *
 * ## Interactions
 *
 * - Click tab                → onSwitchTab(tabId)
 * - Double-click tab title   → rename inline → RENAME_TAB → conversations.title
 * - Click ×                  → onCloseTab(tabId)
 * - Middle-click tab         → onCloseTab(tabId)
 * - Click +                  → onNewConversation() — disabled at maxTabs
 *
 * ## Per-Tab Provider
 *
 * Provider selection for the active tab is handled by a separate
 * component below the input bar (see ChatApp.tsx), NOT inside the
 * tab bar. Tabs do not display provider information.
 *
 * ## Tab Rename
 *
 * Double-clicking a tab title opens an inline text input.
 * Committing (Enter / blur) calls onRenameTab(tabId, newTitle), which:
 * 1. Sends RENAME_TAB to the backend → BridgeDispatcher → ConversationManager.updateTitle()
 * 2. Updates local tab state immediately (optimistic)
 * 3. Persists via SAVE_TAB_STATE on the next periodic save
 *
 * ## Context Usage Indicator
 *
 * Each tab shows a small coloured chip indicating context window usage:
 * - No chip    → usage unknown or < 75%
 * - Amber chip → usage >= 75%
 * - Red chip   → usage >= 90%
 *
 * Computed per-tab in useBridge via contextFillPercent() from the last
 * exchange's totalTokens / contextWindowSize. Null until first exchange.
 *
 * ## Max Tabs
 *
 * maxTabs prop controls when "+" is disabled. Default 5, range 2–20.
 * Synced from General settings via TAB_STATE → useBridge → ChatApp.
 *
 * @see useBridge.ts — Tab state management
 * @see types.ts — RENAME_TAB command
 * @see useBridge.ts — renameTab() handler
 */

import { useState, useCallback, useRef } from "react";
import type { TabInfo } from "../hooks/useBridge";

// ── Constants ────────────────────────────────────────────────────────

/**
 * Fallback max open tabs (used only when maxTabs prop is not provided).
 * In production, maxTabs is sent from the backend via TAB_STATE event
 * and passed through useBridge → ChatApp → TabBar.
 */
const DEFAULT_MAX_TABS = 5;

// ── Types ────────────────────────────────────────────────────────────

interface TabBarProps {
    /** Ordered list of tab descriptors for rendering. From useBridge().tabs */
    tabs: TabInfo[];

    /** Currently active tab ID. */
    activeTabId: string;

    /** Switch to a tab. */
    onSwitchTab: (tabId: string) => void;

    /** Close a tab. */
    onCloseTab: (tabId: string) => void;

    /** Create a new conversation tab. */
    onNewConversation: () => void;

    /**
     * Rename a tab title.
     *
     * PLACEHOLDER — not yet wired to backend. Called when user commits
     * an inline rename (Enter or blur after double-click).
     *
     * Backend command RENAME_TAB needs to be added to types.ts and
     * handled in BridgeDispatcher.kt before this does anything.
     */
    onRenameTab?: (tabId: string, newTitle: string) => void;

    /**
     * Maximum simultaneous open tabs. Default: 5.
     * Synced from General settings via TAB_STATE → useBridge → ChatApp.
     */
    maxTabs?: number;
}

// ── Component ────────────────────────────────────────────────────────

function TabBar({
                    tabs,
                    activeTabId,
                    onSwitchTab,
                    onCloseTab,
                    onNewConversation,
                    onRenameTab,
                    maxTabs = DEFAULT_MAX_TABS,
                }: TabBarProps) {
    /**
     * PLACEHOLDER: Rename state.
     * Tracks which tab is being renamed and the current input value.
     * Wired to UI below, but onRenameTab is not yet connected to backend.
     */
    const [renamingTabId, setRenamingTabId] = useState<string | null>(null);
    const [renameValue, setRenameValue] = useState("");
    const renameInputRef = useRef<HTMLInputElement>(null);

    const atMaxTabs = tabs.length >= maxTabs;

    // ── Handlers ──────────────────────────────────────────────────────

    /** Middle-click closes the tab. */
    const handleMouseDown = useCallback(
        (e: React.MouseEvent, tabId: string) => {
            if (e.button === 1) {
                e.preventDefault();
                onCloseTab(tabId);
            }
        },
        [onCloseTab],
    );

    /** Close button — stop propagation so tab click doesn't also fire. */
    const handleClose = useCallback(
        (e: React.MouseEvent, tabId: string) => {
            e.stopPropagation();
            onCloseTab(tabId);
        },
        [onCloseTab],
    );

    /** Double-click starts inline rename. Shows an input in place of the tab title. */
    const handleTitleDoubleClick = useCallback(
        (e: React.MouseEvent, tabId: string, currentTitle: string) => {
            e.stopPropagation();
            if (!onRenameTab) return;
            setRenamingTabId(tabId);
            setRenameValue(currentTitle);
            setTimeout(() => renameInputRef.current?.select(), 0);
        },
        [onRenameTab],
    );

    /** Commit rename on Enter or blur. */
    const commitRename = useCallback(() => {
        if (!renamingTabId) return;
        const trimmed = renameValue.trim();
        if (trimmed && onRenameTab) {
            onRenameTab(renamingTabId, trimmed);
        }
        setRenamingTabId(null);
    }, [renamingTabId, renameValue, onRenameTab]);

    const handleRenameKeyDown = useCallback(
        (e: React.KeyboardEvent) => {
            if (e.key === "Enter") commitRename();
            if (e.key === "Escape") setRenamingTabId(null);
        },
        [commitRename],
    );

    // ── Render ────────────────────────────────────────────────────────

    return (
        <div className="ymm-tab-bar">
            <div className="ymm-tab-bar__tabs">
                {tabs.map((tab) => {
                    const isActive = tab.id === activeTabId;
                    const isRenaming = renamingTabId === tab.id;

                    return (
                        <div
                            key={tab.id}
                            className={[
                                "ymm-tab",
                                isActive ? "ymm-tab--active" : "",
                                tab.isThinking ? "ymm-tab--thinking" : "",
                            ].filter(Boolean).join(" ")}
                            onClick={() => onSwitchTab(tab.id)}
                            onMouseDown={(e) => handleMouseDown(e, tab.id)}
                            title={tab.title}
                        >
                            {/* Loading spinner for tabs restoring history */}
                            {!tab.hasMessages && !isActive && (
                                <span className="ymm-tab__loading" title="Loading conversation...">
                                    ⟳
                                </span>
                            )}

                            {/* Tab title — inline rename input when editing */}
                            {isRenaming ? (
                                <input
                                    ref={renameInputRef}
                                    className="ymm-tab__rename-input"
                                    value={renameValue}
                                    onChange={(e) => setRenameValue(e.target.value)}
                                    onBlur={commitRename}
                                    onKeyDown={handleRenameKeyDown}
                                    onClick={(e) => e.stopPropagation()}
                                    maxLength={60}
                                    autoFocus
                                />
                            ) : (
                                <span
                                    className="ymm-tab__title"
                                    onDoubleClick={(e) => handleTitleDoubleClick(e, tab.id, tab.title)}
                                    title="Double-click to rename"
                                >
                                    {tab.title}
                                </span>
                            )}

                            {/* Context usage chip — amber ≥75%, red ≥90%.
                             * Computed in useBridge from lastExchange metrics via contextFillPercent().
                             * Null (hidden) until first exchange provides token data. */}
                            <ContextChip usagePct={tab.contextUsagePct} />

                            {/* Close button */}
                            <button
                                className="ymm-tab__close"
                                onClick={(e) => handleClose(e, tab.id)}
                                title="Close tab"
                                aria-label={`Close ${tab.title}`}
                            >
                                ×
                            </button>
                        </div>
                    );
                })}
            </div>

            {/*
             * "+" new tab button.
             * Disabled when tab count reaches maxTabs (default 5).
             */}
            <button
                className={[
                    "ymm-tab-bar__add",
                    atMaxTabs ? "ymm-tab-bar__add--disabled" : "",
                ].filter(Boolean).join(" ")}
                onClick={atMaxTabs ? undefined : onNewConversation}
                disabled={atMaxTabs}
                title={
                    atMaxTabs
                        ? `Maximum ${maxTabs} tabs open. Close a tab to open a new one. (Limit configurable in Settings → YMM Assistant → General)`
                        : "New conversation"
                }
                aria-label="New conversation"
            >
                +
            </button>
        </div>
    );
}

// ── Context Chip ─────────────────────────────────────────────────────

/**
 * Small coloured dot showing context window usage per tab.
 *
 * Thresholds:
 * - null / < 75% → no visible chip (placeholder span for layout stability)
 * - >= 75%       → amber dot with tooltip ("getting full")
 * - >= 90%       → red dot with tooltip ("approaching limit")
 *
 * @see contextFillPercent — computes the percentage from token counts
 * @see useBridge.ts — populates contextUsagePct in TabInfo
 */
function ContextChip({ usagePct }: { usagePct: number | null }) {
    if (usagePct === null) {
        return <span className="ymm-tab__context-chip ymm-tab__context-chip--placeholder" />;
    }

    if (usagePct < 75) return null;

    const isRed = usagePct >= 90;
    return (
        <span
            className={`ymm-tab__context-chip ${isRed ? "ymm-tab__context-chip--red" : "ymm-tab__context-chip--amber"}`}
            title={`Context usage: ${Math.round(usagePct)}%${isRed ? " — approaching limit" : " — getting full"}`}
        >
            ●
        </span>
    );
}

export default TabBar;