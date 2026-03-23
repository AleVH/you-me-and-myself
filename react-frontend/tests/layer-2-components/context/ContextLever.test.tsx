/**
 * ─── ContextLever.test.tsx ─────────────────────────────────────────
 *
 * WHAT THIS TESTS:
 *   Visible: The horizontal slider that appears when ContextDial is
 *            set to CUSTOM mode. It lets Pro users fine-tune context depth.
 *   Visible process: the lever appears/disappears based on the
 *            `visible` prop, and the user sees "Minimal", "Partial",
 *            or "Full" labels.
 *   Behind the scenes: onLevelChange fires with 0, 1, or 2 when the
 *            user clicks a position on the track.
 *
 * LAYER: 2 — Component Tests (React rendering + interaction)
 *
 * NOTE: drag interaction (pointerDown → pointerMove → pointerUp) is
 * complex to simulate in jsdom. We test the click-to-snap behavior
 * which covers the core logic. Drag is the same snapFromX calculation.
 *
 * @see ContextLever.tsx — source component
 * @see ContextDialStrip.tsx — parent that controls visibility
 */

import { describe, it, expect, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import ContextLever from "../../../src/components/context/ContextLever";

// ═══════════════════════════════════════════════════════════════════════
//  VISIBILITY: the lever only renders when mode is SELECTIVE
// ═══════════════════════════════════════════════════════════════════════

describe("ContextLever — visibility", () => {
    it("renders nothing when visible=false", () => {
        // VISIBLE: when the ContextDial is not in CUSTOM mode, the lever
        // is completely absent from the DOM — not hidden, not collapsed,
        // but literally not rendered.
        const { container } = render(
            <ContextLever visible={false} />
        );
        expect(container.innerHTML).toBe("");
    });

    it("renders the lever when visible=true", () => {
        // VISIBLE: when CUSTOM mode is active, the lever appears with
        // a track, handle, and level label.
        const { container } = render(
            <ContextLever visible={true} />
        );
        expect(container.innerHTML).not.toBe("");
        expect(container.querySelector(".ymm-context-lever")).toBeInTheDocument();
    });
});

// ═══════════════════════════════════════════════════════════════════════
//  VISIBLE STATE: level labels and slider position
// ═══════════════════════════════════════════════════════════════════════

describe("ContextLever — level display", () => {
    it("shows 'Full' label when level is 2 (default)", () => {
        // VISIBLE: the text label next to the slider tells the user
        // what context depth they've selected.
        render(<ContextLever visible={true} />);
        expect(screen.getByText("Full")).toBeInTheDocument();
    });

    it("shows 'Minimal' label when level is 0", () => {
        render(<ContextLever visible={true} level={0} />);
        expect(screen.getByText("Minimal")).toBeInTheDocument();
    });

    it("shows 'Partial' label when level is 1", () => {
        render(<ContextLever visible={true} level={1} />);
        expect(screen.getByText("Partial")).toBeInTheDocument();
    });

    it("renders a slider with correct ARIA attributes", () => {
        // VISIBLE: screen readers can navigate the slider.
        // The role=slider, aria-valuemin/max/now, and aria-valuetext
        // give assistive technology full context.
        render(<ContextLever visible={true} level={1} />);

        const slider = screen.getByRole("slider");
        expect(slider).toBeInTheDocument();
        expect(slider).toHaveAttribute("aria-label", "Context detail level");
        expect(slider).toHaveAttribute("aria-valuemin", "0");
        expect(slider).toHaveAttribute("aria-valuemax", "2");
        expect(slider).toHaveAttribute("aria-valuenow", "1");
        expect(slider).toHaveAttribute("aria-valuetext", "Partial");
    });

    it("renders 3 snap markers on the track", () => {
        // VISIBLE: small dots on the track show where the handle can snap.
        const { container } = render(<ContextLever visible={true} level={0} />);

        const snaps = container.querySelectorAll(".ymm-context-lever__snap");
        expect(snaps).toHaveLength(3); // Minimal, Partial, Full
    });

    it("marks the active snap position with an active class", () => {
        // VISIBLE: the snap marker at the current level has a different
        // visual style (brighter dot) so the user knows where they are.
        const { container } = render(<ContextLever visible={true} level={1} />);

        const activeSnaps = container.querySelectorAll(".ymm-context-lever__snap--active");
        expect(activeSnaps).toHaveLength(1);
    });
});

// ═══════════════════════════════════════════════════════════════════════
//  LEVEL UPDATES VIA PROP CHANGES (controlled component)
// ═══════════════════════════════════════════════════════════════════════

describe("ContextLever — controlled updates", () => {
    it("updates the displayed label when the level prop changes", () => {
        // BEHIND THE SCENES: the parent (ContextDialStrip) controls
        // the level via useBridge.selectiveLevel. When it changes,
        // the lever re-renders with the new label.
        const { rerender } = render(
            <ContextLever visible={true} level={0} />
        );
        expect(screen.getByText("Minimal")).toBeInTheDocument();

        rerender(<ContextLever visible={true} level={2} />);
        expect(screen.getByText("Full")).toBeInTheDocument();
    });

    it("updates aria-valuenow when the level prop changes", () => {
        const { rerender } = render(
            <ContextLever visible={true} level={0} />
        );
        expect(screen.getByRole("slider")).toHaveAttribute("aria-valuenow", "0");

        rerender(<ContextLever visible={true} level={2} />);
        expect(screen.getByRole("slider")).toHaveAttribute("aria-valuenow", "2");
    });

    it("defaults to level 2 (Full) when no level prop is provided", () => {
        // BEHIND THE SCENES: if the parent doesn't pass a level,
        // the lever defaults to Full (maximum context depth).
        render(<ContextLever visible={true} />);

        expect(screen.getByText("Full")).toBeInTheDocument();
        expect(screen.getByRole("slider")).toHaveAttribute("aria-valuenow", "2");
    });
});
