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
 * │                              │
 * │  MessageList (scrollable)    │  ← flex-grow, auto-scroll
 * │    └── ThinkingIndicator     │  ← inside scroll area
 * │    └── ScrollToBottom btn    │  ← R4: floating when scrolled up
 * │                              │
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
 */
import { useBridge } from "../hooks/useBridge";
import MessageList from "./MessageList";
import InputBar from "./InputBar";
import ProviderSelector from "./ProviderSelector";
import MetricsBar from "../metrics/MetricsBar";
import TabBar from "./TabBar";

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

            <MessageList
                messages={bridge.messages}
                isThinking={bridge.isThinking}
                isScrolledUp={bridge.isScrolledUp}
                scrollPosition={bridge.scrollPosition}
                onConfirmCorrection={bridge.confirmCorrection}
                onRequestCorrection={bridge.requestCorrection}
                onToggleStar={bridge.toggleStar}
                onBookmarkCodeBlock={bridge.bookmarkCodeBlock}
                onScrolledUpChange={bridge.setScrolledUp}
                onScrollPositionChange={bridge.saveScrollPosition}
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