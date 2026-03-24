/**
 * ContextBadgeTray.test.tsx
 *
 * WHAT THIS TESTS:
 *   Visible: The badge tray between the control strip and input bar.
 *            Shows context badges (scope icon + name + tokens + freshness).
 *   Visible process: badges appear after CHAT_RESULT, ghost badges appear
 *            when Force Context is set, progress bar during thinking.
 *   Behind the scenes: component renders correct badge data from props.
 *
 * LAYER: 2 — Component Tests (React rendering)
 *
 * DEPENDENCIES:
 *   - ContextBadgeTray.tsx — the component under test
 *   - ContextBadgeTray.css — imported by component (ignored in tests)
 *
 * @see ContextBadgeTray.tsx — source component
 * @see ChatApp.tsx — parent that provides badges + ghostBadge props
 */

import { describe, it, expect } from "vitest";
import { render, screen } from "@testing-library/react";
import ContextBadgeTray from "../../../src/components/context/ContextBadgeTray";
import type { ContextFileDetail, GhostBadge } from "../../../src/components/context/ContextBadgeTray";

// ═══════════════════════════════════════════════════════════════════════
//  VISIBLE STATE: what the user sees
// ═══════════════════════════════════════════════════════════════════════

describe("ContextBadgeTray — visible state", () => {
    it("renders nothing when there are no badges, no ghost, and not thinking", () => {
        // VISIBLE: tray is invisible when there's nothing to show.
        const { container } = render(
            <ContextBadgeTray badges={[]} ghostBadge={null} isThinking={false} />
        );
        expect(container.firstChild).toBeNull();
    });

    it("renders a progress bar when thinking with no badges yet", () => {
        // VISIBLE: animated progress bar while context is being gathered.
        const { container } = render(
            <ContextBadgeTray badges={[]} ghostBadge={null} isThinking={true} />
        );
        expect(container.querySelector(".ymm-badge-tray__progress")).toBeInTheDocument();
    });

    it("does not render progress bar when thinking but badges are present", () => {
        // VISIBLE: once badges arrive, progress bar disappears.
        const badge: ContextFileDetail = {
            path: "/src/Foo.kt",
            name: "Foo.kt",
            scope: "file",
            lang: "kotlin",
            kind: "RAW",
            freshness: "fresh",
            tokens: 250,
            isStale: false,
            forced: false,
            elementSignature: null,
        };
        const { container } = render(
            <ContextBadgeTray badges={[badge]} ghostBadge={null} isThinking={true} />
        );
        expect(container.querySelector(".ymm-badge-tray__progress")).not.toBeInTheDocument();
    });
});

// ═══════════════════════════════════════════════════════════════════════
//  REAL BADGES: from contextFiles in ChatResultEvent
// ═══════════════════════════════════════════════════════════════════════

describe("ContextBadgeTray — real badges", () => {
    const fileBadge: ContextFileDetail = {
        path: "/src/UserService.kt",
        name: "UserService.kt",
        scope: "file",
        lang: "kotlin",
        kind: "RAW",
        freshness: "fresh",
        tokens: 500,
        isStale: false,
        forced: false,
        elementSignature: null,
    };

    const methodBadge: ContextFileDetail = {
        path: "/src/UserService.kt",
        name: "processRefund",
        scope: "method",
        lang: "kotlin",
        kind: "SUMMARY",
        freshness: "cached",
        tokens: 120,
        isStale: false,
        forced: false,
        elementSignature: "UserService#processRefund(String,Int)",
    };

    it("renders the correct number of badges", () => {
        render(
            <ContextBadgeTray badges={[fileBadge, methodBadge]} ghostBadge={null} isThinking={false} />
        );
        const badges = screen.getAllByText(/~\d+t/);
        expect(badges).toHaveLength(2);
    });

    it("displays the badge name", () => {
        render(
            <ContextBadgeTray badges={[methodBadge]} ghostBadge={null} isThinking={false} />
        );
        expect(screen.getByText("processRefund")).toBeInTheDocument();
    });

    it("displays token count", () => {
        render(
            <ContextBadgeTray badges={[methodBadge]} ghostBadge={null} isThinking={false} />
        );
        expect(screen.getByText("~120t")).toBeInTheDocument();
    });

    it("displays kind indicator (S for SUMMARY, R for RAW)", () => {
        render(
            <ContextBadgeTray badges={[fileBadge, methodBadge]} ghostBadge={null} isThinking={false} />
        );
        expect(screen.getByText("R")).toBeInTheDocument();
        expect(screen.getByText("S")).toBeInTheDocument();
    });

    it("applies freshness CSS class", () => {
        const { container } = render(
            <ContextBadgeTray badges={[methodBadge]} ghostBadge={null} isThinking={false} />
        );
        const badge = container.querySelector(".ymm-badge");
        expect(badge?.classList.contains("ymm-badge--cached")).toBe(true);
    });

    it("applies stale CSS class when isStale is true", () => {
        const staleBadge: ContextFileDetail = { ...methodBadge, isStale: true, freshness: "rough" };
        const { container } = render(
            <ContextBadgeTray badges={[staleBadge]} ghostBadge={null} isThinking={false} />
        );
        const badge = container.querySelector(".ymm-badge");
        expect(badge?.classList.contains("ymm-badge--stale")).toBe(true);
    });

    it("applies forced CSS class when forced is true", () => {
        const forcedBadge: ContextFileDetail = { ...methodBadge, forced: true };
        const { container } = render(
            <ContextBadgeTray badges={[forcedBadge]} ghostBadge={null} isThinking={false} />
        );
        const badge = container.querySelector(".ymm-badge");
        expect(badge?.classList.contains("ymm-badge--forced")).toBe(true);
    });
});

// ═══════════════════════════════════════════════════════════════════════
//  GHOST BADGE: from RESOLVE_FORCE_CONTEXT_RESULT (before Send)
// ═══════════════════════════════════════════════════════════════════════

describe("ContextBadgeTray — ghost badge", () => {
    const ghost: GhostBadge = {
        elementName: "processRefund",
        elementScope: "method",
        estimatedTokens: 150,
    };

    it("renders ghost badge when provided", () => {
        render(
            <ContextBadgeTray badges={[]} ghostBadge={ghost} isThinking={false} />
        );
        expect(screen.getByText("processRefund")).toBeInTheDocument();
        expect(screen.getByText("forced")).toBeInTheDocument();
    });

    it("shows estimated tokens on ghost badge", () => {
        render(
            <ContextBadgeTray badges={[]} ghostBadge={ghost} isThinking={false} />
        );
        expect(screen.getByText("~150t")).toBeInTheDocument();
    });

    it("applies ghost CSS class", () => {
        const { container } = render(
            <ContextBadgeTray badges={[]} ghostBadge={ghost} isThinking={false} />
        );
        const badge = container.querySelector(".ymm-badge--ghost");
        expect(badge).toBeInTheDocument();
    });

    it("renders ghost badge alongside real badges", () => {
        const fileBadge: ContextFileDetail = {
            path: "/src/Foo.kt",
            name: "Foo.kt",
            scope: "file",
            lang: "kotlin",
            kind: "RAW",
            freshness: "fresh",
            tokens: 300,
            isStale: false,
            forced: false,
            elementSignature: null,
        };
        render(
            <ContextBadgeTray badges={[fileBadge]} ghostBadge={ghost} isThinking={false} />
        );
        expect(screen.getByText("Foo.kt")).toBeInTheDocument();
        expect(screen.getByText("processRefund")).toBeInTheDocument();
    });
});
