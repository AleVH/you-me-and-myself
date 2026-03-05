/**
 * System message component.
 *
 * Renders non-AI notifications like:
 * - "Context ready in 120 ms" (INFO)
 * - "✓ Format confirmed" (INFO)
 * - "Failed to switch provider" (ERROR)
 * - "Response auto-detected. Use the buttons above..." (INFO)
 *
 * ## Styling
 *
 * System messages are visually distinct from user/assistant messages:
 * - Centered, muted text
 * - Smaller font size
 * - Level-based color (INFO = dim, WARN = yellow, ERROR = red)
 *
 * @see BridgeMessage.SystemMessageEvent — the event that creates these
 */
import type { ChatMessage } from "../hooks/useBridge";

interface SystemMessageProps {
    message: ChatMessage;
}

function SystemMessage({ message }: SystemMessageProps) {
    return (
        <div
            className={`ymm-system-message${message.isError ? " ymm-system-message--error" : ""}`}
        >
            {message.content}
        </div>
    );
}

export default SystemMessage;