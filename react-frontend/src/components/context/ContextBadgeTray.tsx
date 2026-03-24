/**
 * ContextBadgeTray — displays context badges below the prompt input.
 *
 * Shows what context was/will be attached to the request:
 * - Ghost badges: preview of forced context (before Send, from RESOLVE_FORCE_CONTEXT_RESULT)
 * - Real badges: from contextFiles[] in ChatResultEvent (after Send)
 * - Progress indicator: during context gathering (between Send and response)
 *
 * Each badge shows: scope icon + truncated name + token estimate + freshness colour.
 * Hover shows custom tooltip with full details. Click navigates to source in the IDE.
 *
 * @see ContextFileDetail — data type for each badge
 * @see Context System — Complete UI & Behaviour Specification.md
 */
import { useState } from "react";
import "./ContextBadgeTray.css";

// ── Types ────────────────────────────────────────────────────────────

export interface ContextFileDetail {
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
}: ContextBadgeTrayProps) {
    const [tooltip, setTooltip] = useState<TooltipState | null>(null);

    // Nothing to show
    if (badges.length === 0 && !ghostBadge && !isThinking) return null;

    const showTooltip = (text: string, e: React.MouseEvent) => {
        const rect = (e.currentTarget as HTMLElement).getBoundingClientRect();
        setTooltip({
            text,
            x: rect.left + rect.width / 2,
            y: rect.top,
        });
    };

    const hideTooltip = () => setTooltip(null);

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

            {/* Progress indicator during context gathering */}
            {isThinking && badges.length === 0 && (
                <div className="ymm-badge-tray__progress">
                    <div className="ymm-badge-tray__progress-bar" />
                </div>
            )}

            {/* Ghost badge (before Send, from force context) */}
            {ghostBadge && (
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
                        `${badge.scope}: ${badge.name}\n${badge.kind} | ${badge.freshness} | ~${badge.tokens}t${badge.isStale ? "\n⚠ STALE" : ""}${badge.forced ? "\n★ FORCED" : ""}\nClick to navigate`,
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
                </span>
            ))}
        </div>
    );
}

export default ContextBadgeTray;
