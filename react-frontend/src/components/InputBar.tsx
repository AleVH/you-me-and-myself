/**
 * Chat input bar component.
 *
 * ## Behavior
 *
 * - Enter sends the message (if not empty and not disabled)
 * - Shift+Enter inserts a newline (multi-line input)
 * - Clears the input after sending
 * - Disabled while the AI is thinking (prevents double-sends)
 * - Auto-focuses on mount for immediate typing
 *
 * ## R1 Scope
 *
 * Plain textarea with send button. Future phases add:
 * - R2: Context attachment toggle
 * - R4: Per-tab input state (draft text preserved on tab switch)
 * - R5: Slash commands (/correct, /raw, etc.)
 */
import { useState, useRef, useCallback } from "react";

interface InputBarProps {
    /** Called when the user submits a message. */
    onSend: (text: string) => void;
    /** Whether input is disabled (e.g., while AI is thinking). */
    disabled: boolean;
    /**
     * Called when the user types (debounced externally).
     * Used to trigger background context gathering while the user composes.
     */
    onInputChange?: (text: string) => void;
}

function InputBar({ onSend, disabled, onInputChange }: InputBarProps) {
    const [text, setText] = useState("");
    const textareaRef = useRef<HTMLTextAreaElement>(null);

    /**
     * Handle the send action.
     *
     * Trims whitespace, validates non-empty, sends the command,
     * clears the input, and refocuses the textarea for the next message.
     */
    const handleSend = useCallback(() => {
        const trimmed = text.trim();
        if (trimmed.length === 0 || disabled) return;

        onSend(trimmed);
        setText("");

        // Refocus the textarea after sending so the user can type immediately.
        // requestAnimationFrame ensures the DOM has updated first.
        requestAnimationFrame(() => {
            textareaRef.current?.focus();
        });
    }, [text, disabled, onSend]);

    /**
     * Handle keyboard events in the textarea.
     *
     * - Enter (no modifier): Send the message
     * - Shift+Enter: Insert a newline (default textarea behavior)
     * - Other keys: Normal typing
     */
    const handleKeyDown = useCallback(
        (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
            if (e.key === "Enter" && !e.shiftKey) {
                e.preventDefault();
                handleSend();
            }
        },
        [handleSend],
    );

    return (
        <div className="ymm-input-bar">
      <textarea
          ref={textareaRef}
          className="ymm-input-bar__textarea"
          value={text}
          onChange={(e) => {
              setText(e.target.value);
              onInputChange?.(e.target.value);
          }}
          onKeyDown={handleKeyDown}
          placeholder={disabled ? "Waiting for response..." : "Type a message..."}
          disabled={disabled}
          rows={1}
          autoFocus
      />
            <button
                className="ymm-input-bar__send"
                onClick={handleSend}
                disabled={disabled || text.trim().length === 0}
                title="Send message (Enter)"
            >
                Send
            </button>
        </div>
    );
}

export default InputBar;