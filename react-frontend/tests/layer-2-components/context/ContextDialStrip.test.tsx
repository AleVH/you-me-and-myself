/**
 * ContextDialStrip.test.tsx
 *
 * WHAT THIS TESTS:
 *   Visible: The control strip bar containing context dial, summary dial,
 *            Force Context button, and expand toggle.
 *   Visible process: Force Context button cycles through Nothing / Method / Class.
 *            The button is only visible when context is enabled and not OFF.
 *   Behind the scenes: onForceContextChange callback fires with correct values.
 *
 * LAYER: 2 — Component Tests (React rendering + interaction)
 *
 * DEPENDENCIES:
 *   - ContextDialStrip.tsx — the component under test
 *   - ContextDial, SummaryDial, ContextLever — child components
 *   - ContextDialStrip.css — imported (ignored in tests)
 *
 * NOTE: ContextDial and SummaryDial have their own test files.
 *       This file tests the STRIP-level behaviour: Force Context button,
 *       layout composition, and prop threading. It does NOT re-test
 *       individual dial cycling (covered in ContextDial.test.tsx etc.).
 *
 * @see ContextDialStrip.tsx — source component
 * @see ChatApp.tsx — parent that provides all props
 */

import { describe, it, expect, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import ContextDialStrip from "../../../src/components/context/ContextDialStrip";

// ═══════════════════════════════════════════════════════════════════════
//  FORCE CONTEXT BUTTON: visible state
// ═══════════════════════════════════════════════════════════════════════

describe("ContextDialStrip — Force Context button visibility", () => {
    it("shows Force Context button when context is ON", () => {
        render(
            <ContextDialStrip
                mode="ON"
                onModeChange={() => {}}
                canUseSelective={false}
                globalContextEnabled={true}
                forceContextScope={null}
                onForceContextChange={() => {}}
            />
        );
        expect(screen.getByText("Force: -")).toBeInTheDocument();
    });

    it("shows Force Context button when context is CUSTOM", () => {
        render(
            <ContextDialStrip
                mode="CUSTOM"
                onModeChange={() => {}}
                canUseSelective={true}
                globalContextEnabled={true}
                forceContextScope={null}
                onForceContextChange={() => {}}
            />
        );
        expect(screen.getByText("Force: -")).toBeInTheDocument();
    });

    it("shows Force Context button even when per-tab context is OFF", () => {
        // Force overrides per-tab dial — user can force context even if tab dial is OFF.
        // Only the global kill switch hides the Force button.
        render(
            <ContextDialStrip
                mode="OFF"
                onModeChange={() => {}}
                canUseSelective={false}
                globalContextEnabled={true}
                forceContextScope={null}
                onForceContextChange={() => {}}
            />
        );
        expect(screen.getByText("Force: -")).toBeInTheDocument();
    });

    it("hides Force Context button when context is globally disabled", () => {
        render(
            <ContextDialStrip
                mode="ON"
                onModeChange={() => {}}
                canUseSelective={false}
                globalContextEnabled={false}
                forceContextScope={null}
                onForceContextChange={() => {}}
            />
        );
        expect(screen.queryByText("Force: -")).not.toBeInTheDocument();
    });
});

// ═══════════════════════════════════════════════════════════════════════
//  FORCE CONTEXT BUTTON: cycling behaviour
// ═══════════════════════════════════════════════════════════════════════

describe("ContextDialStrip — Force Context cycling", () => {
    it("displays 'Force: -' when scope is null", () => {
        render(
            <ContextDialStrip
                mode="ON"
                onModeChange={() => {}}
                canUseSelective={false}
                globalContextEnabled={true}
                forceContextScope={null}
                onForceContextChange={() => {}}
            />
        );
        expect(screen.getByText("Force: -")).toBeInTheDocument();
    });

    it("displays 'Force: Method' when scope is method", () => {
        render(
            <ContextDialStrip
                mode="ON"
                onModeChange={() => {}}
                canUseSelective={false}
                globalContextEnabled={true}
                forceContextScope="method"
                onForceContextChange={() => {}}
            />
        );
        expect(screen.getByText("Force: Method")).toBeInTheDocument();
    });

    it("displays 'Force: Class' when scope is class", () => {
        render(
            <ContextDialStrip
                mode="ON"
                onModeChange={() => {}}
                canUseSelective={false}
                globalContextEnabled={true}
                forceContextScope="class"
                onForceContextChange={() => {}}
            />
        );
        expect(screen.getByText("Force: Class")).toBeInTheDocument();
    });

    it("cycles null → method on first click", async () => {
        const user = userEvent.setup();
        const onChange = vi.fn();
        render(
            <ContextDialStrip
                mode="ON"
                onModeChange={() => {}}
                canUseSelective={false}
                globalContextEnabled={true}
                forceContextScope={null}
                onForceContextChange={onChange}
            />
        );
        await user.click(screen.getByText("Force: -"));
        expect(onChange).toHaveBeenCalledWith("method");
    });

    it("cycles method → class on second click", async () => {
        const user = userEvent.setup();
        const onChange = vi.fn();
        render(
            <ContextDialStrip
                mode="ON"
                onModeChange={() => {}}
                canUseSelective={false}
                globalContextEnabled={true}
                forceContextScope="method"
                onForceContextChange={onChange}
            />
        );
        await user.click(screen.getByText("Force: Method"));
        expect(onChange).toHaveBeenCalledWith("class");
    });

    it("cycles class → null on third click", async () => {
        const user = userEvent.setup();
        const onChange = vi.fn();
        render(
            <ContextDialStrip
                mode="ON"
                onModeChange={() => {}}
                canUseSelective={false}
                globalContextEnabled={true}
                forceContextScope="class"
                onForceContextChange={onChange}
            />
        );
        await user.click(screen.getByText("Force: Class"));
        expect(onChange).toHaveBeenCalledWith(null);
    });

    it("applies active CSS class when force scope is set", () => {
        const { container } = render(
            <ContextDialStrip
                mode="ON"
                onModeChange={() => {}}
                canUseSelective={false}
                globalContextEnabled={true}
                forceContextScope="method"
                onForceContextChange={() => {}}
            />
        );
        const forceBtn = container.querySelector(".ymm-context-strip__force");
        expect(forceBtn?.classList.contains("ymm-context-strip__force--active")).toBe(true);
    });

    it("does not apply active CSS class when force scope is null", () => {
        const { container } = render(
            <ContextDialStrip
                mode="ON"
                onModeChange={() => {}}
                canUseSelective={false}
                globalContextEnabled={true}
                forceContextScope={null}
                onForceContextChange={() => {}}
            />
        );
        const forceBtn = container.querySelector(".ymm-context-strip__force");
        expect(forceBtn?.classList.contains("ymm-context-strip__force--active")).toBe(false);
    });
});
