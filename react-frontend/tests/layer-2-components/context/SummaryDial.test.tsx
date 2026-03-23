/**
 * ─── SummaryDial.test.tsx ──────────────────────────────────────────
 *
 * WHAT THIS TESTS:
 *   Visible: The summary dial widget — a 24px rotary toggle that
 *            controls whether context files are compressed before
 *            being sent to the AI.
 *   Visible process: clicking toggles between OFF and ON. The ring
 *            changes from grey (OFF) to green (ON).
 *   Behind the scenes: onModeChange fires with the correct next mode,
 *            disabled state blocks clicks.
 *
 * LAYER: 2 — Component Tests (React rendering + interaction)
 *
 * NOTE: SummaryDial is structurally similar to ContextDial but simpler
 * (2 positions only, no tier gating). Tests are parallel in structure
 * for consistency.
 *
 * @see SummaryDial.tsx — source component
 * @see ContextDialStrip.tsx — parent that provides mode + callbacks
 */

import { describe, it, expect, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import SummaryDial from "../../../src/components/context/SummaryDial";

// ═══════════════════════════════════════════════════════════════════════
//  VISIBLE STATE: what the user sees in each mode
// ═══════════════════════════════════════════════════════════════════════

describe("SummaryDial — visible state", () => {
    it("renders a button element", () => {
        render(<SummaryDial mode="OFF" onModeChange={() => {}} />);
        expect(screen.getByRole("button")).toBeInTheDocument();
    });

    it("shows the correct aria-label for OFF mode", () => {
        // VISIBLE: tooltip tells the user that files are sent as-is.
        render(<SummaryDial mode="OFF" onModeChange={() => {}} />);
        expect(screen.getByRole("button")).toHaveAttribute(
            "aria-label",
            "Summary: OFF — context files sent as-is (full text)"
        );
    });

    it("shows the correct aria-label for ON mode", () => {
        // VISIBLE: tooltip tells the user that files are compressed.
        render(<SummaryDial mode="ON" onModeChange={() => {}} />);
        expect(screen.getByRole("button")).toHaveAttribute(
            "aria-label",
            "Summary: ON — context files compressed before sending"
        );
    });

    it("applies the correct CSS class for OFF mode", () => {
        render(<SummaryDial mode="OFF" onModeChange={() => {}} />);
        expect(screen.getByRole("button")).toHaveClass("ymm-summary-dial--off");
    });

    it("applies the correct CSS class for ON mode", () => {
        render(<SummaryDial mode="ON" onModeChange={() => {}} />);
        expect(screen.getByRole("button")).toHaveClass("ymm-summary-dial--on");
    });

    it("renders an SVG element inside the button", () => {
        render(<SummaryDial mode="ON" onModeChange={() => {}} />);
        const button = screen.getByRole("button");
        expect(button.querySelector("svg")).toBeInTheDocument();
    });
});

// ═══════════════════════════════════════════════════════════════════════
//  VISIBLE PROCESS: click toggling
// ═══════════════════════════════════════════════════════════════════════

describe("SummaryDial — click cycling (2 positions)", () => {
    it("toggles OFF → ON", async () => {
        // VISIBLE PROCESS: user clicks when OFF → ring turns green.
        // BEHIND THE SCENES: onModeChange fires with "ON".
        const user = userEvent.setup();
        const onModeChange = vi.fn();

        render(<SummaryDial mode="OFF" onModeChange={onModeChange} />);

        await user.click(screen.getByRole("button"));
        expect(onModeChange).toHaveBeenCalledWith("ON");
        expect(onModeChange).toHaveBeenCalledTimes(1);
    });

    it("toggles ON → OFF", async () => {
        // VISIBLE PROCESS: user clicks when ON → ring turns grey.
        const user = userEvent.setup();
        const onModeChange = vi.fn();

        render(<SummaryDial mode="ON" onModeChange={onModeChange} />);

        await user.click(screen.getByRole("button"));
        expect(onModeChange).toHaveBeenCalledWith("OFF");
    });
});

// ═══════════════════════════════════════════════════════════════════════
//  DISABLED STATE: global kill-switch
// ═══════════════════════════════════════════════════════════════════════

describe("SummaryDial — disabled state (global summary OFF)", () => {
    it("does NOT fire onModeChange when disabled", async () => {
        // VISIBLE: the dial is greyed out (darker than OFF grey).
        // VISIBLE PROCESS: clicking does nothing — no toggle.
        // BEHIND THE SCENES: the global summary kill-switch overrides
        // the per-tab setting. Clicks are silently rejected.
        const user = userEvent.setup();
        const onModeChange = vi.fn();

        render(
            <SummaryDial mode="ON" onModeChange={onModeChange} disabled={true} />
        );

        await user.click(screen.getByRole("button"));
        expect(onModeChange).not.toHaveBeenCalled();
    });

    it("shows disabled tooltip instead of mode tooltip", () => {
        render(
            <SummaryDial mode="ON" onModeChange={() => {}} disabled={true} />
        );

        expect(screen.getByRole("button")).toHaveAttribute(
            "aria-label",
            "Summary disabled (see Settings → Summary)"
        );
    });

    it("applies the disabled CSS class", () => {
        render(
            <SummaryDial mode="ON" onModeChange={() => {}} disabled={true} />
        );

        expect(screen.getByRole("button")).toHaveClass("ymm-summary-dial--disabled");
    });

    it("sets aria-disabled attribute", () => {
        render(
            <SummaryDial mode="ON" onModeChange={() => {}} disabled={true} />
        );

        expect(screen.getByRole("button")).toHaveAttribute("aria-disabled", "true");
    });
});
