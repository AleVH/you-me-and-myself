/**
 * ContextLever — per-component context bypass slider.
 *
 * ## Purpose
 *
 * When the ContextDial is set to SELECTIVE mode (Pro tier only), the
 * lever appears and lets the user fine-tune which context detectors
 * are active. Each snap position corresponds to a different level of
 * context richness:
 *
 *   0 = Minimal (open file only, no detectors)
 *   1 = Partial (Language + Framework + RelevantFiles, skip ProjectStructure)
 *   2 = Full    (all 4 detectors — same as bypassMode null/FULL dial)
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
 * The `level` prop is controlled by the parent (ContextDialStrip reads
 * it from useBridge.selectiveLevel). onLevelChange is wired to
 * useBridge.setSelectiveLevel which persists via SAVE_TAB_STATE.
 *
 * @see ContextDialStrip — parent wrapper
 * @see ContextAssembler.buildDetectorsForLevel — backend uses selectiveLevel
 * @see Feature.CONTEXT_SELECTIVE_BYPASS — tier gate
 */
import { useCallback, useRef } from "react";
import "./ContextLever.css";

// ── Types ────────────────────────────────────────────────────────────────

export interface ContextLeverProps {
    /** Whether the lever should be visible (only when mode is SELECTIVE). */
    visible: boolean;
    /**
     * Controlled current snap position (0-2).
     * Provided by the parent (from useBridge.selectiveLevel).
     * If not provided, the lever manages its own state starting at 2 (Full).
     */
    level?: number;
    /**
     * Called when the user drags the handle to a new snap position.
     * Wired to useBridge.setSelectiveLevel which persists via SAVE_TAB_STATE.
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

function ContextLever({ visible, level: controlledLevel, onLevelChange }: ContextLeverProps) {
    // Use controlled level prop when provided, otherwise 2 (Full).
    const level = controlledLevel ?? 2;

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
        onLevelChange?.(newLevel);
    }, [snapFromX, onLevelChange]);

    /**
     * Handle drag start on the handle.
     * Uses pointer events for unified mouse/touch support.
     * Controlled mode: we don't call setLevel, we call onLevelChange.
     * The parent updates the level prop on re-render.
     */
    const handlePointerDown = useCallback((e: React.PointerEvent) => {
        e.preventDefault();
        const target = e.currentTarget as HTMLElement;
        target.setPointerCapture(e.pointerId);

        const onMove = (_ev: PointerEvent) => {
            // Visual feedback during drag would require local state;
            // for now we wait for drag end (same as before).
        };

        const onUp = (ev: PointerEvent) => {
            target.releasePointerCapture(ev.pointerId);
            target.removeEventListener("pointermove", onMove);
            target.removeEventListener("pointerup", onUp);
            // Fire the change callback on drag end (final snap position)
            const finalLevel = snapFromX(ev.clientX);
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
        </div>
    );
}

export default ContextLever;
