/**
 * ─── ContextDial.test.tsx ──────────────────────────────────────────
 *
 * WHAT THIS TESTS:
 *   Visible: The context dial widget — a 24px rotary toggle that the
 *            user clicks to control context gathering per tab.
 *   Visible process: clicking the dial cycles through modes
 *            (OFF → ON for Basic, OFF → ON → CUSTOM → OFF for Pro).
 *   Behind the scenes: the onModeChange callback fires with the
 *            correct next mode value, disabled state blocks clicks.
 *
 * LAYER: 2 — Component Tests (React rendering + interaction)
 *
 * DEPENDENCIES:
 *   - ContextDial.tsx — the component under test
 *   - ContextDial.css — imported by the component but ignored in tests
 *     (Vitest css:false setting treats CSS imports as empty modules)
 *
 * @see ContextDial.tsx — source component
 * @see ContextDialStrip.tsx — parent that provides mode + callbacks
 */

import { describe, it, expect, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import ContextDial from "../../../src/components/context/ContextDial";

// ═══════════════════════════════════════════════════════════════════════
//  VISIBLE STATE: what the user sees in each mode
// ═══════════════════════════════════════════════════════════════════════

describe("ContextDial — visible state", () => {
    it("renders a button element", () => {
        // VISIBLE: the dial is a clickable button in the DOM.
        render(
            <ContextDial mode="OFF" onModeChange={() => {}} canUseSelective={false} />
        );
        expect(screen.getByRole("button")).toBeInTheDocument();
    });

    it("shows the correct aria-label for OFF mode", () => {
        // VISIBLE: screen readers and tooltips announce the mode.
        render(
            <ContextDial mode="OFF" onModeChange={() => {}} canUseSelective={false} />
        );
        expect(screen.getByRole("button")).toHaveAttribute(
            "aria-label",
            "Context: OFF — no IDE context for this tab"
        );
    });

    it("shows the correct aria-label for ON mode", () => {
        render(
            <ContextDial mode="ON" onModeChange={() => {}} canUseSelective={false} />
        );
        expect(screen.getByRole("button")).toHaveAttribute(
            "aria-label",
            "Context: ON — full context gathering"
        );
    });

    it("shows the correct aria-label for CUSTOM mode", () => {
        render(
            <ContextDial mode="CUSTOM" onModeChange={() => {}} canUseSelective={true} />
        );
        expect(screen.getByRole("button")).toHaveAttribute(
            "aria-label",
            "Context: Custom — control context reach (Pro)"
        );
    });

    it("applies the correct CSS class for each mode", () => {
        // VISIBLE: each mode has a CSS class that controls the ring color
        // (grey for OFF, blue for ON, amber for CUSTOM).
        const { rerender } = render(
            <ContextDial mode="OFF" onModeChange={() => {}} canUseSelective={false} />
        );
        expect(screen.getByRole("button")).toHaveClass("ymm-context-dial--off");

        rerender(
            <ContextDial mode="ON" onModeChange={() => {}} canUseSelective={false} />
        );
        expect(screen.getByRole("button")).toHaveClass("ymm-context-dial--on");

        rerender(
            <ContextDial mode="CUSTOM" onModeChange={() => {}} canUseSelective={true} />
        );
        expect(screen.getByRole("button")).toHaveClass("ymm-context-dial--custom");
    });

    it("renders an SVG element inside the button", () => {
        // VISIBLE: the dial is drawn as an SVG circle with a notch.
        render(
            <ContextDial mode="ON" onModeChange={() => {}} canUseSelective={false} />
        );
        const button = screen.getByRole("button");
        const svg = button.querySelector("svg");
        expect(svg).toBeInTheDocument();
    });
});

// ═══════════════════════════════════════════════════════════════════════
//  VISIBLE PROCESS: what happens when the user clicks
// ═══════════════════════════════════════════════════════════════════════

describe("ContextDial — click cycling (Basic tier: 2 positions)", () => {
    it("cycles OFF → ON", async () => {
        // VISIBLE PROCESS: Basic user clicks when OFF → dial turns blue.
        // BEHIND THE SCENES: onModeChange fires with "ON".
        const user = userEvent.setup();
        const onModeChange = vi.fn();

        render(
            <ContextDial mode="OFF" onModeChange={onModeChange} canUseSelective={false} />
        );

        await user.click(screen.getByRole("button"));
        expect(onModeChange).toHaveBeenCalledWith("ON");
        expect(onModeChange).toHaveBeenCalledTimes(1);
    });

    it("cycles ON → OFF (skips CUSTOM)", async () => {
        // VISIBLE PROCESS: Basic user clicks when ON → dial turns grey.
        // The cycle skips CUSTOM because canUseSelective is false.
        const user = userEvent.setup();
        const onModeChange = vi.fn();

        render(
            <ContextDial mode="ON" onModeChange={onModeChange} canUseSelective={false} />
        );

        await user.click(screen.getByRole("button"));
        expect(onModeChange).toHaveBeenCalledWith("OFF");
    });
});

describe("ContextDial — click cycling (Pro tier: 3 positions)", () => {
    it("cycles OFF → ON", async () => {
        const user = userEvent.setup();
        const onModeChange = vi.fn();

        render(
            <ContextDial mode="OFF" onModeChange={onModeChange} canUseSelective={true} />
        );

        await user.click(screen.getByRole("button"));
        expect(onModeChange).toHaveBeenCalledWith("ON");
    });

    it("cycles ON → CUSTOM", async () => {
        // VISIBLE PROCESS: Pro user clicks when ON → dial turns amber,
        // and the ContextLever slider should appear (tested separately).
        const user = userEvent.setup();
        const onModeChange = vi.fn();

        render(
            <ContextDial mode="ON" onModeChange={onModeChange} canUseSelective={true} />
        );

        await user.click(screen.getByRole("button"));
        expect(onModeChange).toHaveBeenCalledWith("CUSTOM");
    });

    it("cycles CUSTOM → OFF", async () => {
        const user = userEvent.setup();
        const onModeChange = vi.fn();

        render(
            <ContextDial mode="CUSTOM" onModeChange={onModeChange} canUseSelective={true} />
        );

        await user.click(screen.getByRole("button"));
        expect(onModeChange).toHaveBeenCalledWith("OFF");
    });
});

// ═══════════════════════════════════════════════════════════════════════
//  DISABLED STATE: global kill-switch
// ═══════════════════════════════════════════════════════════════════════

describe("ContextDial — disabled state (global context OFF)", () => {
    it("does NOT fire onModeChange when disabled", async () => {
        // VISIBLE: the dial is greyed out.
        // VISIBLE PROCESS: clicking does nothing.
        // BEHIND THE SCENES: the global kill-switch in Settings overrides
        // any per-tab preference. Silent click rejection is deliberate UX.
        const user = userEvent.setup();
        const onModeChange = vi.fn();

        render(
            <ContextDial
                mode="ON"
                onModeChange={onModeChange}
                canUseSelective={true}
                disabled={true}
            />
        );

        await user.click(screen.getByRole("button"));
        expect(onModeChange).not.toHaveBeenCalled();
    });

    it("shows disabled tooltip instead of mode tooltip", () => {
        // VISIBLE: instead of "Context: ON — full context gathering",
        // the user sees a message pointing them to Settings.
        render(
            <ContextDial
                mode="ON"
                onModeChange={() => {}}
                canUseSelective={true}
                disabled={true}
            />
        );

        expect(screen.getByRole("button")).toHaveAttribute(
            "aria-label",
            "Context gathering disabled (see Settings → Context)"
        );
    });

    it("applies the disabled CSS class", () => {
        render(
            <ContextDial
                mode="ON"
                onModeChange={() => {}}
                canUseSelective={false}
                disabled={true}
            />
        );

        expect(screen.getByRole("button")).toHaveClass("ymm-context-dial--disabled");
    });

    it("sets aria-disabled attribute", () => {
        render(
            <ContextDial
                mode="ON"
                onModeChange={() => {}}
                canUseSelective={false}
                disabled={true}
            />
        );

        expect(screen.getByRole("button")).toHaveAttribute("aria-disabled", "true");
    });
});
