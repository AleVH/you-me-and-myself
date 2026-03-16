/**
 * ContextLever — per-component context bypass slider (STUB).
 *
 * ## Purpose
 *
 * When the ContextDial is set to SELECTIVE mode (Pro tier only), the
 * lever appears and lets the user fine-tune which context detectors
 * are active. Each snap position corresponds to a different level of
 * context richness:
 *
 *   0 = Minimal (skip most detectors, keep only current file)
 *   1 = Partial (skip project structure, keep files + summaries)
 *   2 = Full    (all detectors active — effectively same as OFF mode)
 *
 * ## Current State: STUB
 *
 * The backend's SELECTIVE bypass mode is a stub — it logs and treats
 * as OFF (full context gathering runs). This component renders the
 * visual lever with a "Coming soon" overlay. The drag interaction
 * works (for visual polish) but has no backend effect.
 *
 * ## Visual Design
 *
 * A horizontal bar (120px wide, 8px tall) with a color gradient
 * (green → amber → red) and a draggable handle that auto-snaps
 * to one of the 3 positions.
 *
 * ## Integration
 *
 * Rendered by ContextDialStrip only when `mode === "SELECTIVE"`.
 * The strip passes `visible={true/false}` to control mount/unmount.
 *
 * @see ContextDialStrip — parent wrapper
 * @see ContextAssembler.assemble — SELECTIVE stub in backend
 * @see Feature.CONTEXT_SELECTIVE_BYPASS — tier gate
 */
import { useState, useCallback, useRef } from "react";
import "./ContextLever.css";

// ── Types ────────────────────────────────────────────────────────────────

export interface ContextLeverProps {
    /** Whether the lever should be visible (only when mode is SELECTIVE). */
    visible: boolean;
    /**
     * Called when the user drags the handle to a new snap position.
     * Currently a STUB — the value is captured but has no backend effect.
     *
     * @param level 0 = Minimal, 1 = Partial, 2 = Full
     */
    onLevelChange?: (level: number) => void;
}

// ── Constants ────────────────────────────────────────────────────────────

/** Number of snap positions on the lever track. */
const SNAP_COUNT = 3;

/** Labels for each snap position, shown in the tooltip. */
const LEVEL_LABELS = ["Minimal", "Partial", "Full"];

/** Handle width in pixels (matches CSS). */
const HANDLE_WIDTH = 14;

// ── Component ────────────────────────────────────────────────────────────

function ContextLever({ visible, onLevelChange }: ContextLeverProps) {
    // Current snap position (0-indexed). Defaults to Full (2) — all context.
    const [level, setLevel] = useState(2);

    // Ref for the track element — used to calculate drag positions.
    const trackRef = useRef<HTMLDivElement>(null);

    /**
     * Calculate the snap position closest to the given X offset
     * within the track. Clamps to valid range [0, SNAP_COUNT-1].
     */
    const snapFromX = useCallback((clientX: number): number => {
        if (!trackRef.current) return level;
        const rect = trackRef.current.getBoundingClientRect();
        const x = clientX - rect.left;
        const ratio = Math.max(0, Math.min(1, x / rect.width));
        return Math.round(ratio * (SNAP_COUNT - 1));
    }, [level]);

    /**
     * Handle click on the track — snap to the nearest position.
     */
    const handleTrackClick = useCallback((e: React.MouseEvent) => {
        const newLevel = snapFromX(e.clientX);
        setLevel(newLevel);
        onLevelChange?.(newLevel);
    }, [snapFromX, onLevelChange]);

    /**
     * Handle drag start on the handle.
     * Uses pointer events for unified mouse/touch support.
     */
    const handlePointerDown = useCallback((e: React.PointerEvent) => {
        e.preventDefault();
        const target = e.currentTarget as HTMLElement;
        target.setPointerCapture(e.pointerId);

        const onMove = (ev: PointerEvent) => {
            const newLevel = snapFromX(ev.clientX);
            setLevel(newLevel);
        };

        const onUp = (ev: PointerEvent) => {
            target.releasePointerCapture(ev.pointerId);
            target.removeEventListener("pointermove", onMove);
            target.removeEventListener("pointerup", onUp);
            // Fire the change callback on drag end (final snap position)
            const finalLevel = snapFromX(ev.clientX);
            setLevel(finalLevel);
            onLevelChange?.(finalLevel);
        };

        target.addEventListener("pointermove", onMove);
        target.addEventListener("pointerup", onUp);
    }, [snapFromX, onLevelChange]);

    // Don't render anything when not visible
    if (!visible) return null;

    // Calculate handle position as a percentage of the track width.
    // The handle slides between 0% (left) and 100% (right).
    const handlePercent = (level / (SNAP_COUNT - 1)) * 100;
    // Offset so the handle center aligns with the snap position
    const handleLeft = `calc(${handlePercent}% - ${(HANDLE_WIDTH * handlePercent) / 100}px)`;

    return (
        <div className="ymm-context-lever">
            {/* Track — the colored bar the handle slides along */}
            <div
                className="ymm-context-lever__track"
                ref={trackRef}
                onClick={handleTrackClick}
                title={`Context level: ${LEVEL_LABELS[level]}`}
            >
                {/* Snap markers — subtle dots showing valid positions */}
                {Array.from({ length: SNAP_COUNT }, (_, i) => (
                    <div
                        key={i}
                        className={`ymm-context-lever__snap ${i === level ? "ymm-context-lever__snap--active" : ""}`}
                        style={{ left: `${(i / (SNAP_COUNT - 1)) * 100}%` }}
                    />
                ))}

                {/* Draggable handle */}
                <div
                    className="ymm-context-lever__handle"
                    style={{ left: handleLeft }}
                    onPointerDown={handlePointerDown}
                    role="slider"
                    aria-label="Context detail level"
                    aria-valuemin={0}
                    aria-valuemax={SNAP_COUNT - 1}
                    aria-valuenow={level}
                    aria-valuetext={LEVEL_LABELS[level]}
                    tabIndex={0}
                />
            </div>

            {/* Level label */}
            <span className="ymm-context-lever__label">
                {LEVEL_LABELS[level]}
            </span>

            {/* STUB overlay — indicates this feature is not yet functional */}
            <span className="ymm-context-lever__stub" title="Per-component bypass is coming soon">
                Coming soon
            </span>
        </div>
    );
}

export default ContextLever;
