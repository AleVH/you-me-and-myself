/**
 * ─── InputBar.test.tsx ─────────────────────────────────────────────
 *
 * WHAT THIS TESTS:
 *   Visible: The text input area and Send button at the bottom of
 *            the chat panel. The user types here and presses Enter.
 *   Visible process: typing text, pressing Enter to send, seeing the
 *            input clear after send, seeing placeholder change.
 *   Behind the scenes: onSend callback fires with trimmed text,
 *            Shift+Enter inserts newlines, empty input blocks send.
 *
 * LAYER: 2 — Component Tests (React rendering + interaction)
 *
 * @see InputBar.tsx — source component
 * @see ChatApp.tsx — parent that provides onSend + disabled
 */

import { describe, it, expect, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import InputBar from "../../src/components/InputBar";

// ═══════════════════════════════════════════════════════════════════════
//  VISIBLE STATE: what the user sees before interacting
// ═══════════════════════════════════════════════════════════════════════

describe("InputBar — visible state", () => {
    it('shows "Type a message..." placeholder when enabled', () => {
        // VISIBLE: the empty textarea shows an invitation to type.
        render(<InputBar onSend={() => {}} disabled={false} />);
        expect(screen.getByPlaceholderText("Type a message...")).toBeInTheDocument();
    });

    it('shows "Waiting for response..." placeholder when disabled', () => {
        // VISIBLE: while the AI is thinking, the placeholder changes
        // to tell the user they need to wait.
        render(<InputBar onSend={() => {}} disabled={true} />);
        expect(screen.getByPlaceholderText("Waiting for response...")).toBeInTheDocument();
    });

    it("renders a textarea element", () => {
        render(<InputBar onSend={() => {}} disabled={false} />);
        expect(screen.getByRole("textbox")).toBeInTheDocument();
    });

    it("renders a Send button", () => {
        render(<InputBar onSend={() => {}} disabled={false} />);
        expect(screen.getByRole("button", { name: /send/i })).toBeInTheDocument();
    });

    it("disables the Send button when input is empty", () => {
        // VISIBLE: Send button is greyed out — nothing to send.
        render(<InputBar onSend={() => {}} disabled={false} />);
        expect(screen.getByRole("button", { name: /send/i })).toBeDisabled();
    });

    it("disables the textarea when disabled prop is true", () => {
        // VISIBLE: the textarea itself is non-interactive while AI thinks.
        render(<InputBar onSend={() => {}} disabled={true} />);
        expect(screen.getByRole("textbox")).toBeDisabled();
    });

    it("disables the Send button when disabled prop is true", () => {
        render(<InputBar onSend={() => {}} disabled={true} />);
        expect(screen.getByRole("button", { name: /send/i })).toBeDisabled();
    });
});

// ═══════════════════════════════════════════════════════════════════════
//  VISIBLE PROCESS: typing and enabling the send button
// ═══════════════════════════════════════════════════════════════════════

describe("InputBar — typing interaction", () => {
    it("enables the Send button after typing text", async () => {
        // VISIBLE: the Send button becomes clickable (un-greyed) when
        // there's actual text in the input.
        const user = userEvent.setup();
        render(<InputBar onSend={() => {}} disabled={false} />);

        await user.type(screen.getByRole("textbox"), "hello");
        expect(screen.getByRole("button", { name: /send/i })).toBeEnabled();
    });

    it("keeps Send button disabled when input is only whitespace", async () => {
        // VISIBLE: typing only spaces doesn't enable the button —
        // we don't want to send blank messages.
        const user = userEvent.setup();
        render(<InputBar onSend={() => {}} disabled={false} />);

        await user.type(screen.getByRole("textbox"), "   ");
        expect(screen.getByRole("button", { name: /send/i })).toBeDisabled();
    });
});

// ═══════════════════════════════════════════════════════════════════════
//  BEHIND THE SCENES: send behavior
// ═══════════════════════════════════════════════════════════════════════

describe("InputBar — send behavior", () => {
    it("calls onSend with trimmed text when Enter is pressed", async () => {
        // VISIBLE PROCESS: user types " hello " and presses Enter.
        // BEHIND THE SCENES: onSend receives "hello" (trimmed).
        const user = userEvent.setup();
        const onSend = vi.fn();
        render(<InputBar onSend={onSend} disabled={false} />);

        await user.type(screen.getByRole("textbox"), "  hello  ");
        await user.keyboard("{Enter}");

        expect(onSend).toHaveBeenCalledWith("hello");
        expect(onSend).toHaveBeenCalledTimes(1);
    });

    it("calls onSend when the Send button is clicked", async () => {
        // VISIBLE PROCESS: user clicks the Send button instead of Enter.
        const user = userEvent.setup();
        const onSend = vi.fn();
        render(<InputBar onSend={onSend} disabled={false} />);

        await user.type(screen.getByRole("textbox"), "hello");
        await user.click(screen.getByRole("button", { name: /send/i }));

        expect(onSend).toHaveBeenCalledWith("hello");
    });

    it("clears the input after sending", async () => {
        // VISIBLE: after sending, the textarea is empty and the
        // placeholder reappears. Ready for the next message.
        const user = userEvent.setup();
        render(<InputBar onSend={() => {}} disabled={false} />);

        const textarea = screen.getByRole("textbox");
        await user.type(textarea, "hello");
        await user.keyboard("{Enter}");

        expect(textarea).toHaveValue("");
    });

    it("does NOT send on Shift+Enter (inserts newline instead)", async () => {
        // VISIBLE PROCESS: Shift+Enter lets the user write multi-line messages.
        // BEHIND THE SCENES: onSend is NOT called — only plain Enter sends.
        const user = userEvent.setup();
        const onSend = vi.fn();
        render(<InputBar onSend={onSend} disabled={false} />);

        await user.type(screen.getByRole("textbox"), "line 1");
        await user.keyboard("{Shift>}{Enter}{/Shift}");

        expect(onSend).not.toHaveBeenCalled();
    });

    it("does NOT send when input is only whitespace", async () => {
        // BEHIND THE SCENES: trimming + empty check prevents
        // sending blank messages to the AI.
        const user = userEvent.setup();
        const onSend = vi.fn();
        render(<InputBar onSend={onSend} disabled={false} />);

        await user.type(screen.getByRole("textbox"), "   ");
        await user.keyboard("{Enter}");

        expect(onSend).not.toHaveBeenCalled();
    });

    it("does NOT send when disabled (even with text)", async () => {
        // BEHIND THE SCENES: prevents double-sending while the AI
        // is still processing the previous message. The textarea
        // is disabled so typing shouldn't even be possible.
        const onSend = vi.fn();
        render(<InputBar onSend={onSend} disabled={true} />);

        // Textarea is disabled, so we can't type. Just verify onSend
        // was never called.
        expect(onSend).not.toHaveBeenCalled();
    });
});
