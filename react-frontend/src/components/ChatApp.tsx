/**
 * Main chat application component.
 *
 * ## Layout
 *
 * ┌──────────────────────────────┐
 * │  MetricsBar (token usage)    │  ← top bar, hidden until first exchange
 * ├──────────────────────────────┤
 * │  ProviderSelector (dropdown) │  ← global provider dropdown
 * ├──────────────────────────────┤
 * │  TabBar (conversation tabs)  │  ← R4: tab bar with +/× buttons
 * ├──────────────────────────────┤
 * │  Collapse toolbar            │  ← shown when 2+ assistant messages
 * ├──────────────────────────────┤
 * │                              │
 * │  MessageList (scrollable)    │  ← flex-grow, auto-scroll
 * │    └── ThinkingIndicator     │  ← inside scroll area
 * │    └── ScrollToBottom btn    │  ← R4: floating when scrolled up
 * │                              │
 * ├──────────────────────────────┤
 * │  ContextDialStrip            │  ← Block 5: bypass mode toggle
 * ├──────────────────────────────┤
 * │  InputBar (text + send)      │  ← fixed at bottom
 * └──────────────────────────────┘
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
 */
import { useBridge } from "../hooks/useBridge";
import MessageList from "./MessageList";
import InputBar from "./InputBar";
import ProviderSelector from "./ProviderSelector";
import MetricsBar from "../metrics/MetricsBar";
import TabBar from "./TabBar";
import ContextDialStrip from "./context/ContextDialStrip";
import { log } from "../utils/log";

log.info("ChatApp", `[BUILD] ${__BUILD_DATE__} ${__BUILD_TIMESTAMP__}`);

function ChatApp() {
    const bridge = useBridge();

    return (
        <div className="ymm-chat-app">
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
            />

            <InputBar
                onSend={bridge.sendMessage}
                disabled={bridge.isThinking}
            />

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