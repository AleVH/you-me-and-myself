/**
 * ContextDialStrip — compact bar holding the dial, lever, and status.
 *
 * ## Layout
 *
 * Compact (default):
 *   [ ContextDial ]  Summarization: ON          [ ⤢ ]
 *
 * Bypass active:
 *   [ ContextDial ]  Bypass Summarization       [ ⤢ ]
 *
 * Expanded (toggle):
 *   [ ContextDial ]  Summarization: Selective   [ ⤢ ]
 *   [ ContextLever ──────────── ] Partial  Coming soon
 *
 * ## Positioning
 *
 * Sits between MessageList and InputBar in ChatApp's layout.
 * Fixed 28px tall in compact mode. Expands vertically when the
 * lever is shown (SELECTIVE mode + expanded toggle).
 *
 * ## Per-Tab State
 *
 * Each tab has its own bypassMode stored in TabData (ephemeral —
 * not persisted to SQLite). The strip reads `bypassMode` and calls
 * `onModeChange` to update the active tab's TabData.
 *
 * ## Tier Gating
 *
 * The `canUseSelective` prop controls whether SELECTIVE is available.
 * In Phase D, this will be wired to CompositeTierProvider.canUse(
 * Feature.CONTEXT_SELECTIVE_BYPASS) via a bridge query or local check.
 * For now, it's passed as a prop from ChatApp.
 *
 * @see ContextDial — the rotary toggle
 * @see ContextLever — the per-component slider (STUB)
 * @see ChatApp — mounts this between MessageList and InputBar (Phase D)
 */
import { useState, useCallback } from "react";
import ContextDial from "./ContextDial";
import type { BypassMode } from "./ContextDial";
import ContextLever from "./ContextLever";
import "./ContextDialStrip.css";

// ── Re-export BypassMode for convenience ─────────────────────────────
export type { BypassMode } from "./ContextDial";

// ── Types ────────────────────────────────────────────────────────────────

export interface ContextDialStripProps {
    /** Current bypass mode for the active tab. */
    mode: BypassMode;
    /** Called when the user changes the mode via the dial. */
    onModeChange: (mode: BypassMode) => void;
    /**
     * Whether SELECTIVE mode is available (Pro tier).
     * When false, the dial cycles OFF ↔ FULL only.
     */
    canUseSelective: boolean;
}

// ── Human-readable mode labels ───────────────────────────────────────

const MODE_DISPLAY: Record<BypassMode, string> = {
    OFF: "Summarization: ON",
    FULL: "Bypass Summarization",
    SELECTIVE: "Summarization: Selective",
};

// ── Component ────────────────────────────────────────────────────────────

function ContextDialStrip({ mode, onModeChange, canUseSelective }: ContextDialStripProps) {
    // Expanded state — controls whether the lever row is visible.
    // Only meaningful when mode is SELECTIVE. Persists while the
    // component is mounted (resets on tab switch / IDE restart).
    const [expanded, setExpanded] = useState(false);

    const toggleExpand = useCallback(() => {
        setExpanded((prev) => !prev);
    }, []);

    // The lever is only relevant in SELECTIVE mode
    const showLever = mode === "SELECTIVE" && expanded;

    return (
        <div className={`ymm-context-strip ${expanded ? "ymm-context-strip--expanded" : ""}`}>
            {/* ── Primary row: dial + label + expand toggle ─── */}
            <div className="ymm-context-strip__row">
                <ContextDial
                    mode={mode}
                    onModeChange={onModeChange}
                    canUseSelective={canUseSelective}
                />

                <span className={`ymm-context-strip__label ymm-context-strip__label--${mode.toLowerCase()}`}>
                    {MODE_DISPLAY[mode]}
                </span>

                {/* Expand toggle — only shown when SELECTIVE is the active mode,
                    since that's the only mode with additional details to show. */}
                {mode === "SELECTIVE" && (
                    <button
                        className="ymm-context-strip__expand"
                        onClick={toggleExpand}
                        title={expanded ? "Collapse detail" : "Expand detail"}
                        aria-label={expanded ? "Collapse context detail" : "Expand context detail"}
                        type="button"
                    >
                        {expanded ? "⤡" : "⤢"}
                    </button>
                )}
            </div>

            {/* ── Expanded row: lever detail (SELECTIVE only) ─── */}
            {showLever && (
                <div className="ymm-context-strip__detail">
                    <ContextLever
                        visible={true}
                        onLevelChange={(level) => {
                            // STUB: level is captured but has no backend effect.
                            // Phase D will wire this to per-component bypass config.
                            void level;
                        }}
                    />
                </div>
            )}
        </div>
    );
}

export default ContextDialStrip;
