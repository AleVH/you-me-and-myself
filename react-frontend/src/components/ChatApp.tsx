/**
 * Main chat application component.
 *
 * ## Layout (Phase 3)
 *
 * ┌──────────────────────────────────────┐
 * │  MetricsBar (token usage)            │  ← top bar, hidden until first exchange
 * ├──────────────────────────────────────┤
 * │  ProviderSelector (dropdown)         │  ← global provider dropdown
 * ├──────────────────────────────────────┤
 * │  TabBar (conversation tabs)          │  ← R4: tab bar with +/× buttons
 * ├──────────────────────────┬───────────┤
 * │  Collapse toolbar        │           │
 * ├──────────────────────────┤  Context  │
 * │                          │  Sidebar  │
 * │  MessageList (scrollable)│  (32px    │
 * │    └── ThinkingIndicator │  collapsed│
 * │    └── ScrollToBottom btn│  240px    │
 * │                          │  expanded)│
 * ├──────────────────────────┤           │
 * │  ContextDialStrip        │           │
 * ├──────────────────────────┤           │
 * │  InputBar (text + send)  │           │
 * ├──────────────────────────┤           │
 * │  ContextBadgeTray        │           │
 * └──────────────────────────┴───────────┘
 *
 * ## Per-Tab Provider
 *
 * Per-tab provider override is planned but NOT YET in the UI.
 * The global ProviderSelector at the top is the only provider
 * dropdown. Per-tab overrides will be revisited once the core
 * tab functionality is stable.
 *
 * ## R5 Changes
 *
 * - Passes scrollPosition and onBookmarkCodeBlock to MessageList
 * - R5: Bookmark/Library integration, code block save-to-library
 *
 * ## Block 5 Changes
 *
 * - ContextDialStrip between MessageList and InputBar for bypass mode control
 * - bridge.bypassMode / bridge.setBypassMode for per-tab context bypass
 *
 * ## Phase 3 Changes
 *
 * - Layout split into top bars (full width) + body row (main + sidebar)
 * - ContextSidebar on the right, collapsible, shows sent context per conversation
 */
import { useState, useCallback } from "react";
import { useBridge } from "../hooks/useBridge";
import MessageList from "./MessageList";
import InputBar from "./InputBar";
import ProviderSelector from "./ProviderSelector";
import MetricsBar from "../metrics/MetricsBar";
import TabBar from "./TabBar";
import ContextDialStrip from "./context/ContextDialStrip";
import ContextBadgeTray from "./context/ContextBadgeTray";
import ContextSidebar from "./context/ContextSidebar";
import { log } from "../utils/log";

log.info("ChatApp", `[BUILD] ${__BUILD_DATE__} ${__BUILD_TIMESTAMP__}`);

function ChatApp() {
    const bridge = useBridge();
    const [sidebarExpanded, setSidebarExpanded] = useState(false);
    const toggleSidebar = useCallback(() => setSidebarExpanded((v) => !v), []);

    return (
        <div className="ymm-chat-app">
            {/* ── Top bars: full width ── */}
            <MetricsBar metricsState={bridge.metricsState} />

            <ProviderSelector
                providers={bridge.providers}
                selectedId={bridge.selectedProviderId}
                onSelect={bridge.switchProvider}
            />

            <TabBar
                tabs={bridge.tabs}
                activeTabId={bridge.activeTabId}
                onSwitchTab={bridge.switchTab}
                onCloseTab={bridge.closeTab}
                onNewConversation={bridge.newConversation}
                onRenameTab={bridge.renameTab}
                maxTabs={bridge.maxTabs}
            />

            {/* ── Body: main content + sidebar ── */}
            <div className="ymm-chat-body">
                {/* Main content column */}
                <div className="ymm-chat-main">
                    {/* Collapse/Expand All — between tab bar and message list.
                        Only visible when 2+ assistant messages exist in active tab. */}
                    {bridge.messages.filter((m) => m.role === "assistant").length >= 2 && (
                        <div className="ymm-collapse-toolbar">
                            <button
                                className="ymm-collapse-toolbar__btn"
                                onClick={bridge.collapsedIds.size > 0 ? bridge.expandAll : bridge.collapseAll}
                                title={bridge.collapsedIds.size > 0 ? "Expand all responses" : "Collapse all responses"}
                            >
                                {bridge.collapsedIds.size > 0 ? "▸ Expand All" : "▾ Collapse All"}
                            </button>
                        </div>
                    )}

                    <MessageList
                        messages={bridge.messages}
                        isThinking={bridge.isThinking}
                        isScrolledUp={bridge.isScrolledUp}
                        scrollPosition={bridge.scrollPosition}
                        collapsedIds={bridge.collapsedIds}
                        onConfirmCorrection={bridge.confirmCorrection}
                        onRequestCorrection={bridge.requestCorrection}
                        onToggleStar={bridge.toggleStar}
                        onBookmarkCodeBlock={bridge.bookmarkCodeBlock}
                        onScrolledUpChange={bridge.setScrolledUp}
                        onScrollPositionChange={bridge.saveScrollPosition}
                        onToggleCollapse={bridge.toggleCollapse}
                    />

                    {/* ═══════════════════════════════════════════════════════════
                        CONTEXT vs SUMMARY — TWO INDEPENDENT features on the same strip.
                        Context (left dial)  = WHAT gets gathered from the IDE (scope).
                        Summary (right dial) = HOW COMPACT the files are (compression).
                        They are sequential: context decides what is included, summary
                        decides how compact it is. Each has its own global kill-switch
                        and per-tab dial. They must NEVER be conflated.
                        ═══════════════════════════════════════════════════════════ */}
                    <ContextDialStrip
                        /* ── Context dial props (scope control) ── */
                        mode={bridge.bypassMode}
                        onModeChange={bridge.setBypassMode}
                        canUseSelective={false} /* Basic tier — SELECTIVE gated behind Pro */
                        globalContextEnabled={bridge.globalContextEnabled}
                        selectiveLevel={bridge.selectiveLevel}
                        onLevelChange={bridge.setSelectiveLevel}
                        /* ── Summary dial props (compression control) ── */
                        summaryMode={bridge.summaryEnabled ? "ON" : "OFF"}
                        onSummaryModeChange={(mode) => bridge.setSummaryEnabled(mode === "ON")}
                        globalSummaryEnabled={bridge.globalSummaryEnabled}
                        /* ── Force Context props ── */
                        forceContextScope={bridge.forceContextScope}
                        onForceContextChange={bridge.setForceContextScope}
                    />

                    <InputBar
                        onSend={bridge.sendMessage}
                        disabled={bridge.isThinking}
                        onInputChange={bridge.triggerContextGathering}
                    />

                    {/* Badge tray sits BELOW the prompt input.
                        Shows ghost badges (from Force Context) and real badges (after Send).
                        See: Context System — Complete UI & Behaviour Specification.md */}
                    <ContextBadgeTray
                        badges={
                            // Live badges from CONTEXT_BADGE_UPDATE take priority when present.
                            // They represent real-time incremental context gathering (mock or real).
                            // When empty, fall back to the last message's contextFiles (post-Send).
                            bridge.contextBadges.length > 0
                                ? bridge.contextBadges
                                : bridge.messages.length > 0
                                    ? (bridge.messages[bridge.messages.length - 1] as any).contextFiles ?? []
                                    : []
                        }
                        ghostBadge={bridge.ghostBadge}
                        isThinking={bridge.isThinking}
                        contextProgress={bridge.contextProgress}
                        onBadgeClick={(badge) => {
                            // Navigate to the source file in the IDE editor.
                            // The backend opens the file and positions the cursor at the element.
                            bridge.navigateToSource(badge.path, badge.elementSignature);
                        }}
                        onGhostBadgeClick={() => {
                            // Ghost badge = forced element at cursor.
                            // The cursor is already on the element, but we still send the
                            // command so the backend can focus the editor (in case the user
                            // switched to another file after clicking Force).
                            bridge.navigateToSource(null, null);
                        }}
                        onRemoveBadge={(_badge, entryId) => {
                            // Phase 2: Remove badge from staging area.
                            // Tier-gated on the backend — Basic tier removals are rejected.
                            // The X button only renders on badges with an id (real staging
                            // entries, not mock badges).
                            bridge.removeContextEntry(entryId);
                        }}
                    />
                </div>

                {/* Context Sidebar — collapsible, shows all sent context in conversation */}
                <ContextSidebar
                    sentContext={bridge.sentContext ?? []}
                    isExpanded={sidebarExpanded}
                    onToggle={toggleSidebar}
                    onEntryClick={(entry) => {
                        bridge.navigateToSource(entry.path, entry.elementSignature);
                    }}
                    onDismissStaleness={(entryId) => {
                        bridge.dismissStaleness(entryId);
                    }}
                    onRefreshEntry={(entry) => {
                        bridge.refreshContextEntry(entry);
                    }}
                />
            </div>

            {!bridge.isProduction && (
                <div className="ymm-dev-banner">
                    DEV MODE — Mock transport active
                </div>
            )}

            {/* Build fingerprint — always visible so you can verify
             * the plugin is running the latest React build, not a cached one.
             * If the time shown here doesn't match your last `npm run build`,
             * JCEF is serving a stale bundle. */}
            <div
                className="ymm-build-stamp"
                title={`React build: ${__BUILD_DATE__} ${__BUILD_TIMESTAMP__}`}
            >
                build {__BUILD_TIMESTAMP__}
            </div>
        </div>
    );
}

export default ChatApp;