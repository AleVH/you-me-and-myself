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
 * - Double-click tab title   → rename inline [PLACEHOLDER — not yet wired]
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
 * ## PLACEHOLDER: Tab Rename
 *
 * Double-clicking a tab title should trigger inline rename editing.
 * When the user commits (Enter / blur), onRenameTab(tabId, newTitle)
 * is called. This is wired to a visible interaction below but the
 * backend command (RENAME_TAB → BridgeDispatcher.kt → UPDATE
 * conversations SET title = ? WHERE id = ?) is NOT YET IMPLEMENTED.
 *
 * To implement:
 * 1. Add RENAME_TAB command to CommandType in types.ts
 * 2. Handle in BridgeDispatcher.kt → update conversations table title
 * 3. Wire onRenameTab prop here to sendCommand(RENAME_TAB)
 * 4. Remove [PLACEHOLDER] markers below
 *
 * ## PLACEHOLDER: Context Usage Indicator
 *
 * Each tab should show a small coloured chip indicating context window
 * usage for the active conversation:
 * - No chip    → usage unknown or < 75%
 * - Amber chip → usage >= 75%
 * - Red chip   → usage >= 90%
 *
 * To implement:
 * 1. Populate contextUsagePct from metrics (totalTokens / modelContextLimit * 100)
 * 2. modelContextLimit needs to come from provider config
 * 3. Remove [PLACEHOLDER] markers below
 *
 * ## Max Tabs
 *
 * maxTabs prop controls when "+" is disabled. Default 5, range 2–20.
 * Value comes from user settings (General config page).
 * Config page NOT YET IMPLEMENTED — hardcoded default used for now.
 *
 * @see useBridge.ts — Tab state management
 * @see types.ts — RENAME_TAB command (to be added)
 */

import { useState, useCallback, useRef } from "react";
import type { TabInfo } from "../hooks/useBridge";

// ── Constants ────────────────────────────────────────────────────────

/**
 * Default max open tabs.
 * TODO: Replace with value from General settings config when implemented.
 * Config page: Tools → YMM Assistant → General → "Maximum open tabs"
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
     * TODO: Read from General settings config when page is implemented.
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

    /**
     * PLACEHOLDER: Double-click starts inline rename.
     * Shows an input field in place of the tab title.
     * Committing calls onRenameTab (not yet wired to backend).
     */
    const handleTitleDoubleClick = useCallback(
        (e: React.MouseEvent, tabId: string, currentTitle: string) => {
            e.stopPropagation();

            if (!onRenameTab) {
                alert(
                    "[PLACEHOLDER] Tab rename:\n\n" +
                    "Double-clicking a tab title will allow inline renaming.\n" +
                    "This will trigger a RENAME_TAB command → BridgeDispatcher.kt → " +
                    "UPDATE conversations SET title = ? WHERE id = ?\n\n" +
                    "Not yet implemented — add RENAME_TAB to CommandType in types.ts " +
                    "and handle in BridgeDispatcher.kt."
                );
                return;
            }

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

                            {/*
                             * PLACEHOLDER: Context usage indicator chip.
                             * Currently always invisible — contextUsagePct not yet populated.
                             * To implement: populate from metrics.totalTokens / modelContextLimit * 100
                             */}
                            <ContextChip
                                usagePct={null /* PLACEHOLDER: tab.contextUsagePct */}
                            />

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
 * PLACEHOLDER: Small coloured dot showing context window usage.
 *
 * When usagePct is null (not yet implemented), renders nothing visible.
 * When implemented:
 * - null / < 75% → no chip
 * - >= 75%       → amber dot with tooltip
 * - >= 90%       → red dot with tooltip
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