/**
 * ContextDial — compact rotary-style toggle for context bypass mode.
 *
 * ## Visual Design
 *
 * A 24px SVG circle with a notch indicator that rotates to one of 3
 * positions (clock analogy):
 *
 *   12 o'clock  = OFF        (grey #808080)   — no context sent
 *    4 o'clock  = FULL       (blue #569cd6)   — full context gathering
 *    8 o'clock  = SELECTIVE  (amber #dcdcaa)  — per-component, Pro only
 *
 * Click cycles clockwise: OFF → FULL → (SELECTIVE if Pro) → OFF.
 *
 * ## Tier Gating
 *
 * Basic-tier users only see OFF ↔ FULL (2-position toggle). The
 * SELECTIVE position is skipped when `canUseSelective` is false.
 * This is enforced purely in the click handler — no visual "locked"
 * state is shown (the position simply doesn't exist in the cycle).
 *
 * ## Integration
 *
 * Used inside ContextDialStrip. The strip reads the active tab's
 * bypassMode from TabData and passes it as the `mode` prop.
 *
 * @see ContextDialStrip — parent wrapper
 * @see ContextAssembler.assemble — backend checks bypassMode
 * @see Feature.CONTEXT_SELECTIVE_BYPASS — tier gate for SELECTIVE
 */
import "./ContextDial.css";

// ── Types ────────────────────────────────────────────────────────────────

/** The three bypass modes, matching the backend's string values. */
export type BypassMode = "OFF" | "FULL" | "SELECTIVE";

export interface ContextDialProps {
    /** Current bypass mode for the active tab. */
    mode: BypassMode;
    /** Called when the user clicks to cycle to the next mode. */
    onModeChange: (mode: BypassMode) => void;
    /**
     * Whether the SELECTIVE position is available (Pro tier).
     * When false, the cycle is OFF → FULL → OFF (2 positions).
     */
    canUseSelective: boolean;
}

// ── Constants ────────────────────────────────────────────────────────────

/** SVG viewBox size — the dial is drawn in a 24×24 coordinate space. */
const SIZE = 24;
/** Radius of the outer ring. */
const RING_R = 10;
/** Radius of the notch indicator dot. */
const NOTCH_R = 2.5;
/** Distance from center to notch center. */
const NOTCH_DIST = 7;

/**
 * Rotation angles for each mode position (degrees, clockwise from 12 o'clock).
 * - OFF:       0° (12 o'clock — straight up)
 * - FULL:    120° (4 o'clock)
 * - SELECTIVE: 240° (8 o'clock)
 */
const MODE_ANGLES: Record<BypassMode, number> = {
    OFF: 0,
    FULL: 120,
    SELECTIVE: 240,
};

/**
 * Ring stroke colors per mode — matches the Darcula theme palette.
 * - OFF:       muted grey (context disabled)
 * - FULL:      accent blue (full context active)
 * - SELECTIVE: warm amber (per-component, Pro)
 */
const MODE_COLORS: Record<BypassMode, string> = {
    OFF: "#808080",
    FULL: "#569cd6",
    SELECTIVE: "#dcdcaa",
};

/**
 * Human-readable labels shown in the tooltip on hover.
 */
const MODE_LABELS: Record<BypassMode, string> = {
    OFF: "Context: OFF — no IDE context sent",
    FULL: "Context: ON — full context gathering",
    SELECTIVE: "Context: Selective — per-component (Pro)",
};

// ── Component ────────────────────────────────────────────────────────────

function ContextDial({ mode, onModeChange, canUseSelective }: ContextDialProps) {
    /**
     * Determine the next mode in the clockwise cycle.
     *
     * Basic tier (canUseSelective=false): OFF → FULL → OFF
     * Pro tier   (canUseSelective=true):  OFF → FULL → SELECTIVE → OFF
     */
    const handleClick = () => {
        if (canUseSelective) {
            // Pro: 3-position cycle
            const cycle: BypassMode[] = ["OFF", "FULL", "SELECTIVE"];
            const idx = cycle.indexOf(mode);
            const next = cycle[(idx + 1) % cycle.length];
            onModeChange(next);
        } else {
            // Basic: 2-position toggle
            onModeChange(mode === "OFF" ? "FULL" : "OFF");
        }
    };

    const angle = MODE_ANGLES[mode];
    const color = MODE_COLORS[mode];
    const label = MODE_LABELS[mode];

    // Calculate notch position from angle.
    // SVG: 0° = 12 o'clock = negative Y direction.
    // Math: convert degrees to radians, offset by -90° so 0° points up.
    const rad = ((angle - 90) * Math.PI) / 180;
    const cx = SIZE / 2 + NOTCH_DIST * Math.cos(rad);
    const cy = SIZE / 2 + NOTCH_DIST * Math.sin(rad);

    return (
        <button
            className={`ymm-context-dial ymm-context-dial--${mode.toLowerCase()}`}
            onClick={handleClick}
            title={label}
            aria-label={label}
            type="button"
        >
            <svg
                className="ymm-context-dial__svg"
                width={SIZE}
                height={SIZE}
                viewBox={`0 0 ${SIZE} ${SIZE}`}
                xmlns="http://www.w3.org/2000/svg"
            >
                {/* Outer ring — color indicates current mode */}
                <circle
                    className="ymm-context-dial__ring"
                    cx={SIZE / 2}
                    cy={SIZE / 2}
                    r={RING_R}
                    fill="none"
                    stroke={color}
                    strokeWidth={1.5}
                />

                {/* Notch indicator — rotates to the active position */}
                <circle
                    className="ymm-context-dial__notch"
                    cx={cx}
                    cy={cy}
                    r={NOTCH_R}
                    fill={color}
                />

                {/* Center dot — subtle anchor point */}
                <circle
                    className="ymm-context-dial__center"
                    cx={SIZE / 2}
                    cy={SIZE / 2}
                    r={1.5}
                    fill={color}
                    opacity={0.5}
                />
            </svg>
        </button>
    );
}

export default ContextDial;
