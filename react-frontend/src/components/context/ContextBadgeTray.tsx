/**
 * ContextBadgeTray — displays context badges below the prompt input.
 *
 * Shows what context was/will be attached to the request:
 * - Ghost badges: preview of forced context (before Send, from RESOLVE_FORCE_CONTEXT_RESULT)
 * - Real badges: from contextFiles[] in ChatResultEvent (after Send)
 * - Live badges: from CONTEXT_BADGE_UPDATE during context gathering (mock or real pipeline)
 * - Progress bar: determinate bar (red→green) driven by CONTEXT_PROGRESS events
 * - Indeterminate progress: sliding bar during thinking with no badges
 *
 * Each badge shows: scope icon + truncated name + token estimate + freshness colour.
 * Hover shows custom tooltip with full details. Click navigates to source in the IDE.
 *
 * ## Progress Bar
 *
 * The progress bar is 4px tall, spans the full tray width, and fills left-to-right.
 * The fill colour transitions through a red→amber→yellow→green gradient as percent
 * increases from 0 to 100. This gives an intuitive "getting closer to done" feel.
 *
 * Two modes:
 * - Determinate: contextProgress is set → bar fills to contextProgress.percent
 * - Indeterminate: isThinking with no badges and no contextProgress → sliding animation
 *
 * @see ContextFileDetail — data type for each badge
 * @see Context System — Complete UI & Behaviour Specification.md
 */
import { useState } from "react";
import "./ContextBadgeTray.css";

// ── Types ────────────────────────────────────────────────────────────

export interface ContextFileDetail {
    /** Unique entry ID for staging area operations. Null for legacy/mock badges. */
    id?: string | null;
    path: string;
    name: string;
    scope: "method" | "class" | "file" | "module" | "config" | string;
    lang: string;
    kind: "RAW" | "SUMMARY";
    freshness: "fresh" | "cached" | "rough";
    tokens: number;
    isStale: boolean;
    forced: boolean;
    elementSignature: string | null;
}

export interface GhostBadge {
    elementName: string;
    elementScope: string;
    estimatedTokens: number;
}

export interface ContextProgressState {
    stage: string;
    percent: number;
    message?: string;
}

export interface ContextBadgeTrayProps {
    /** Real badges from the last ChatResultEvent.contextFiles */
    badges: ContextFileDetail[];
    /** Ghost badge from RESOLVE_FORCE_CONTEXT_RESULT (before Send) */
    ghostBadge: GhostBadge | null;
    /** Whether the AI is currently thinking (show progress indicator) */
    isThinking: boolean;
    /** Called when user clicks a badge — navigates to source in the IDE */
    onBadgeClick?: (badge: ContextFileDetail) => void;
    /** Called when user clicks the ghost badge — navigates to forced element */
    onGhostBadgeClick?: (ghost: GhostBadge) => void;
    /**
     * Determinate progress from CONTEXT_PROGRESS events.
     * When set, replaces the indeterminate sliding bar with a filling bar.
     * Null = no active context gathering (or use indeterminate fallback).
     */
    contextProgress?: ContextProgressState | null;
    /**
     * Called when user clicks the X button on a badge to remove it.
     *
     * Phase 2 — Context Staging Area. When provided, an X button appears on
     * each real badge (not ghost badges — those are removed by cycling the
     * Force Context button). The callback sends a REMOVE_CONTEXT_ENTRY
     * command to the backend.
     *
     * Tier-gated: only passed for Pro tier users. When null/undefined, no X
     * button is rendered (Basic tier behavior).
     *
     * @param badge The badge being dismissed
     * @param entryId The unique context entry ID for backend removal
     */
    onRemoveBadge?: (badge: ContextFileDetail, entryId: string) => void;
}

// ── Scope icons ──────────────────────────────────────────────────────

const SCOPE_ICON: Record<string, string> = {
    method: "\u{1F527}",   // wrench
    class: "\u{1F4E6}",    // package
    file: "\u{1F4C4}",     // page
    module: "\u{1F4C1}",   // folder
    config: "\u{2699}",    // gear
};

// ── Freshness colours ────────────────────────────────────────────────

const FRESHNESS_CLASS: Record<string, string> = {
    fresh: "ymm-badge--fresh",
    cached: "ymm-badge--cached",
    rough: "ymm-badge--rough",
};

// ── Progress bar colour ──────────────────────────────────────────────

/**
 * Map percent (0–100) to a colour on a red→amber→yellow→green gradient.
 *
 * Uses HSL: hue rotates from 0 (red) to 120 (green) as percent increases.
 * Saturation and lightness are fixed for consistent visibility on dark backgrounds.
 */
function progressColour(percent: number): string {
    const clamped = Math.max(0, Math.min(100, percent));
    const hue = Math.round((clamped / 100) * 120); // 0=red, 60=yellow, 120=green
    return `hsl(${hue}, 80%, 45%)`;
}

// ── Tooltip component ────────────────────────────────────────────────

interface TooltipState {
    text: string;
    x: number;
    y: number;
}

// ── Component ────────────────────────────────────────────────────────

function ContextBadgeTray({
    badges,
    ghostBadge,
    isThinking,
    onBadgeClick,
    onGhostBadgeClick,
    contextProgress,
    onRemoveBadge,
}: ContextBadgeTrayProps) {
    const [tooltip, setTooltip] = useState<TooltipState | null>(null);

    // Nothing to show
    if (badges.length === 0 && !ghostBadge && !isThinking && !contextProgress) return null;

    const showTooltip = (text: string, e: React.MouseEvent) => {
        const rect = (e.currentTarget as HTMLElement).getBoundingClientRect();
        setTooltip({
            text,
            x: rect.left + rect.width / 2,
            y: rect.top,
        });
    };

    const hideTooltip = () => setTooltip(null);

    // Ghost badge is hidden when any real badge with forced=true arrives.
    // There's only ever one forced element per request, so if a forced badge
    // exists in the list, it IS the ghost badge's real counterpart.
    // The badge's name/scope are adopted from the ghost in useBridge.ts
    // (CONTEXT_BADGE_UPDATE handler) so the transition looks seamless.
    const ghostHidden = ghostBadge && badges.some((b) => b.forced);

    return (
        <div className="ymm-badge-tray">
            {/* Custom tooltip — positioned above the hovered badge */}
            {tooltip && (
                <div
                    className="ymm-badge-tooltip"
                    style={{
                        left: `${tooltip.x}px`,
                        top: `${tooltip.y}px`,
                    }}
                >
                    {tooltip.text.split("\n").map((line, i) => (
                        <div key={i}>{line}</div>
                    ))}
                </div>
            )}

            {/* ── Progress bar ───────────────────────────────────────── */}
            {/* Determinate: driven by CONTEXT_PROGRESS events (red→green fill) */}
            {contextProgress && (
                <div
                    className="ymm-badge-tray__progress ymm-badge-tray__progress--determinate"
                    title={contextProgress.message ?? `${contextProgress.stage} ${contextProgress.percent}%`}
                >
                    <div
                        className="ymm-badge-tray__progress-fill"
                        style={{
                            width: `${contextProgress.percent}%`,
                            backgroundColor: progressColour(contextProgress.percent),
                        }}
                    />
                </div>
            )}

            {/* Indeterminate: sliding bar when thinking with no CONTEXT_PROGRESS */}
            {!contextProgress && isThinking && badges.length === 0 && (
                <div className="ymm-badge-tray__progress">
                    <div className="ymm-badge-tray__progress-bar" />
                </div>
            )}

            {/* Ghost badge (before Send, from force context) — hidden once a matching real badge arrives */}
            {ghostBadge && !ghostHidden && (
                <span
                    className="ymm-badge ymm-badge--ghost"
                    onMouseEnter={(e) => showTooltip(
                        `Force: ${ghostBadge.elementScope}\n"${ghostBadge.elementName}"\n~${ghostBadge.estimatedTokens} tokens\nClick to navigate`,
                        e
                    )}
                    onMouseLeave={hideTooltip}
                    onClick={() => onGhostBadgeClick?.(ghostBadge)}
                    role="button"
                    tabIndex={0}
                    onKeyDown={(e) => {
                        if (e.key === "Enter" || e.key === " ") onGhostBadgeClick?.(ghostBadge);
                    }}
                >
                    <span className="ymm-badge__icon">
                        {SCOPE_ICON[ghostBadge.elementScope] ?? SCOPE_ICON.file}
                    </span>
                    <span className="ymm-badge__name">{ghostBadge.elementName}</span>
                    <span className="ymm-badge__tokens">~{ghostBadge.estimatedTokens}t</span>
                    <span className="ymm-badge__ghost-label">forced</span>
                </span>
            )}

            {/* Real badges from contextFiles */}
            {badges.map((badge, i) => (
                <span
                    key={`${badge.path}-${badge.elementSignature ?? "file"}-${i}`}
                    className={`ymm-badge ${FRESHNESS_CLASS[badge.freshness] ?? ""} ${badge.isStale ? "ymm-badge--stale" : ""} ${badge.forced ? "ymm-badge--forced" : ""}`}
                    onMouseEnter={(e) => showTooltip(
                        `${badge.scope}: ${badge.name}\n${badge.kind} | ${badge.freshness} | ~${badge.tokens}t${badge.isStale ? "\n\u26A0 STALE" : ""}${badge.forced ? "\n\u2605 FORCED" : ""}\nClick to navigate`,
                        e
                    )}
                    onMouseLeave={hideTooltip}
                    onClick={() => onBadgeClick?.(badge)}
                    role="button"
                    tabIndex={0}
                    onKeyDown={(e) => {
                        if (e.key === "Enter" || e.key === " ") onBadgeClick?.(badge);
                    }}
                >
                    <span className="ymm-badge__icon">
                        {SCOPE_ICON[badge.scope] ?? SCOPE_ICON.file}
                    </span>
                    <span className="ymm-badge__name">{badge.name}</span>
                    <span className="ymm-badge__kind">{badge.kind === "SUMMARY" ? "S" : "R"}</span>
                    <span className="ymm-badge__tokens">~{badge.tokens}t</span>
                    {/* X button — dismiss badge from staging area (Pro tier only) */}
                    {onRemoveBadge && badge.id && (
                        <button
                            className="ymm-badge__dismiss"
                            onClick={(e) => {
                                e.stopPropagation(); // Don't trigger navigate
                                onRemoveBadge(badge, badge.id!);
                            }}
                            title="Remove from context"
                            aria-label={`Remove ${badge.name} from context`}
                        >
                            ×
                        </button>
                    )}
                </span>
            ))}
        </div>
    );
}

export default ContextBadgeTray;
