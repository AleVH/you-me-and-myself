/**
 * Thinking indicator component.
 *
 * Shown while the AI provider is generating a response. Displayed
 * between the message list and the input bar so it appears at the
 * bottom of the visible message area.
 *
 * ## Animation
 *
 * Uses a CSS pulse animation on three dots. The animation is defined
 * in index.css and referenced by the `ymm-thinking__dot` class.
 *
 * ## Lifecycle
 *
 * - Shown when SHOW_THINKING event is received (dispatched immediately
 *   when SEND_MESSAGE is processed, before the provider call)
 * - Hidden when CHAT_RESULT is received (response arrived)
 * - Hidden when HIDE_THINKING is received (error or cancellation)
 *
 * @see BridgeMessage.ShowThinkingEvent — triggers display
 * @see BridgeMessage.HideThinkingEvent — triggers removal (error path)
 */

function ThinkingIndicator() {
    return (
        <div className="ymm-thinking">
            <span className="ymm-thinking__label">Thinking</span>
            <span className="ymm-thinking__dot" style={{ animationDelay: "0ms" }}>
        .
      </span>
            <span className="ymm-thinking__dot" style={{ animationDelay: "200ms" }}>
        .
      </span>
            <span className="ymm-thinking__dot" style={{ animationDelay: "400ms" }}>
        .
      </span>
        </div>
    );
}

export default ThinkingIndicator;