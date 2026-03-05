/**
 * Main chat application component.
 *
 * ## Layout (R4, unchanged in R5)
 *
 * ┌──────────────────────────────┐
 * │  MetricsBar (token usage)    │  ← top bar, hidden until first exchange
 * ├──────────────────────────────┤
 * │  ProviderSelector (dropdown) │  ← provider dropdown only
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
 * ## R5 Changes
 *
 * - Passes scrollPosition and onBookmarkCodeBlock to MessageList
 * - R5: Bookmark/Library integration, code block save-to-library
 * - R6: Feature parity flag, legacy UI removal
 */
import { useBridge } from "../hooks/useBridge";
import MessageList from "./MessageList";
import InputBar from "./InputBar";
import ProviderSelector from "./ProviderSelector";
import MetricsBar from "./MetricsBar";
import TabBar from "./TabBar.tsx";

function ChatApp() {
    const bridge = useBridge();

    return (
        <div className="ymm-chat-app">
            {bridge.metrics && <MetricsBar metrics={bridge.metrics} />}

            <ProviderSelector
                providers={bridge.providers}
                selectedId={bridge.selectedProviderId}
                onSelect={bridge.switchProvider}
            />

            <TabBar
                tabs={bridge.tabs}
                onSwitchTab={bridge.switchTab}
                onCloseTab={bridge.closeTab}
                onNewTab={bridge.newConversation}
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