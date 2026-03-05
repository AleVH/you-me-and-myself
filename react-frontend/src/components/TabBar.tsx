/**
 * Tab bar component for switching between conversations.
 *
 * ## Layout
 *
 * ```
 * ┌─────────────────────────────────────────────────────┐
 * │ [Tab 1 ×] [Tab 2 ×] [Tab 3 ×]              [ + ]  │
 * └─────────────────────────────────────────────────────┘
 * ```
 *
 * ## Behavior
 *
 * - Click a tab → switch to that conversation (via switchTab callback)
 * - Click × on a tab → close that tab (via closeTab callback)
 * - Click + → create a new tab (via onNewTab callback)
 * - Active tab is visually highlighted with an accent border
 * - Tabs with active thinking show a subtle pulsing indicator
 * - Middle-click on a tab closes it (standard browser convention)
 *
 * ## Overflow
 *
 * When there are too many tabs to fit, the tab bar scrolls horizontally.
 * The + button stays fixed on the right side. Horizontal scroll with
 * mouse wheel is supported for quick navigation.
 *
 * ## Data Flow
 *
 * This component is purely presentational — it receives tab descriptors
 * and callbacks from the parent (ChatApp, which gets them from useBridge).
 * No direct bridge communication happens here.
 *
 * @see useBridge.ts — TabInfo type and tab management functions
 * @see ChatApp.tsx — Parent that composes this into the layout
 */

import { useCallback, useRef } from "react";
import type { TabInfo } from "../hooks/useBridge";

interface TabBarProps {
    /** Tab descriptors in display order. */
    tabs: TabInfo[];

    /** Switch to a tab by ID. */
    onSwitchTab: (tabId: string) => void;

    /** Close a tab by ID. */
    onCloseTab: (tabId: string) => void;

    /** Create a new tab. */
    onNewTab: () => void;
}

function TabBar({ tabs, onSwitchTab, onCloseTab, onNewTab }: TabBarProps) {
    /** Ref to the scrollable tab container for wheel-to-horizontal-scroll. */
    const scrollRef = useRef<HTMLDivElement>(null);

    /**
     * Handle horizontal scrolling with the mouse wheel.
     *
     * Converts vertical wheel events to horizontal scroll on the tab bar.
     * This is a standard UX pattern for horizontal tab bars — users expect
     * to be able to scroll through tabs with their mouse wheel.
     */
    const handleWheel = useCallback((e: React.WheelEvent<HTMLDivElement>) => {
        if (scrollRef.current) {
            // Prevent page scroll, scroll tabs horizontally instead
            e.preventDefault();
            scrollRef.current.scrollLeft += e.deltaY;
        }
    }, []);

    /**
     * Handle middle-click to close a tab.
     *
     * Standard browser convention: middle-click on a tab closes it.
     * We use onMouseDown instead of onClick because middle-click
     * doesn't fire onClick in all browsers.
     */
    const handleMouseDown = useCallback(
        (e: React.MouseEvent<HTMLButtonElement>, tabId: string) => {
            // Middle mouse button = button 1
            if (e.button === 1) {
                e.preventDefault();
                onCloseTab(tabId);
            }
        },
        [onCloseTab],
    );

    /**
     * Handle close button click.
     *
     * stopPropagation prevents the click from also triggering the tab
     * switch (since the × button is inside the tab button).
     */
    const handleClose = useCallback(
        (e: React.MouseEvent<HTMLSpanElement>, tabId: string) => {
            e.stopPropagation();
            onCloseTab(tabId);
        },
        [onCloseTab],
    );

    return (
        <div className="ymm-tab-bar">
            {/* Scrollable tab container */}
            <div
                className="ymm-tab-bar__tabs"
                ref={scrollRef}
                onWheel={handleWheel}
            >
                {tabs.map((tab) => (
                    <button
                        key={tab.id}
                        className={`ymm-tab-bar__tab${
                            tab.isActive ? " ymm-tab-bar__tab--active" : ""
                        }${tab.isThinking ? " ymm-tab-bar__tab--thinking" : ""}`}
                        onClick={() => onSwitchTab(tab.id)}
                        onMouseDown={(e) => handleMouseDown(e, tab.id)}
                        title={tab.title}
                    >
                        {/* Tab title — truncated via CSS */}
                        <span className="ymm-tab-bar__title">{tab.title}</span>

                        {/* Close button — always visible on hover, always visible on active */}
                        <span
                            className="ymm-tab-bar__close"
                            onClick={(e) => handleClose(e, tab.id)}
                            title="Close tab"
                        >
                            ×
                        </span>
                    </button>
                ))}
            </div>

            {/* New tab button — fixed on the right side */}
            <button
                className="ymm-tab-bar__new"
                onClick={onNewTab}
                title="New conversation (Ctrl+T)"
            >
                +
            </button>
        </div>
    );
}

export default TabBar;