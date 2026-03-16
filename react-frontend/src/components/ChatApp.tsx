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

            {/* Block 5: Context bypass mode toggle — sits above the input
                bar so the user sees the current mode before sending. */}
            <ContextDialStrip
                mode={bridge.bypassMode}
                onModeChange={bridge.setBypassMode}
                canUseSelective={false} /* Basic tier — SELECTIVE gated behind Pro */
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
        </div>
    );
}

export default ChatApp;