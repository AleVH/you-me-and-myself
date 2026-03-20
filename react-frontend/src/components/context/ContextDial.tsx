/**
 * ContextDial — compact rotary-style toggle for per-tab context mode.
 *
 * ## Visual Design
 *
 * A 24px SVG circle with a notch indicator that rotates to one of 3
 * positions (clock analogy):
 *
 *   12 o'clock  = OFF     (grey #808080)   — no context for this tab
 *    4 o'clock  = ON      (blue #569cd6)   — context with default reach
 *    8 o'clock  = CUSTOM  (amber #dcdcaa)  — context with lever (Pro only)
 *
 * ## Two Levels of Control
 *
 * GLOBAL (Settings page):
 *   - Master kill-switch: contextEnabled in ContextSettingsState.
 *   - When OFF globally, the dial is greyed out and non-interactive.
 *   - Global overrules local — always.
 *
 * LOCAL (per-tab, this dial):
 *   - The user can choose OFF / ON / CUSTOM for each individual tab.
 *   - OFF = no context for this specific tab (valid even when global is ON).
 *   - ON  = context gathering with default settings (radius, scope from global).
 *   - CUSTOM = context ON + lever appears to control reach (Pro tier).
 *
 * Having OFF on the per-tab dial is NOT contradictory: the user wants
 * context enabled globally but may disable it for a specific conversation
 * (e.g. a casual chat that doesn't need IDE context).
 *
 * ## Cycle
 *
 *   Basic:  OFF → ON → OFF  (2 positions)
 *   Pro:    OFF → ON → CUSTOM → OFF  (3 positions)
 *
 * ## Tier Gating
 *
 * Basic-tier users see OFF and ON. The CUSTOM position is added when
 * `canUseSelective` is true. This is enforced in the click handler.
 *
 * ## Integration
 *
 * Used inside ContextDialStrip. The strip reads the active tab's
 * contextMode from TabData and passes it as the `mode` prop.
 *
 * @see ContextDialStrip — parent wrapper
 * @see ContextAssembler.assemble — backend checks bypassMode
 * @see Feature.CONTEXT_SELECTIVE_BYPASS — tier gate for CUSTOM
 */
import "./ContextDial.css";

// ── Types ────────────────────────────────────────────────────────────────

/**
 * Per-tab context mode (user perspective, NOT backend bypass perspective).
 *
 * - "OFF":    No context for this tab.
 * - "ON":     Context gathering with default reach.
 * - "CUSTOM": Context gathering + lever for reach control (Pro tier).
 *
 * Translated to backend bypassMode by dialToBackendBypass() in useBridge.ts:
 *   OFF    → "FULL" (full bypass — no context)
 *   ON     → null   (no bypass — context runs with defaults)
 *   CUSTOM → "SELECTIVE" (per-component bypass)
 */
export type BypassMode = "OFF" | "ON" | "CUSTOM";

export interface ContextDialProps {
    /** Current context mode for the active tab (user perspective). */
    mode: BypassMode;
    /** Called when the user clicks to cycle to the next mode. */
    onModeChange: (mode: BypassMode) => void;
    /**
     * Whether the CUSTOM position is available (Pro tier).
     * When false, the cycle is OFF → ON → OFF (2 positions).
     * When true, the cycle is OFF → ON → CUSTOM → OFF (3 positions).
     */
    canUseSelective: boolean;
    /**
     * When true, the dial is greyed out and clicks are rejected.
     * Driven by globalContextEnabled from ContextSettingsState.
     * The kill-switch in Settings → Tools → YMM Assistant → Context disables this.
     */
    disabled?: boolean;
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
 * - OFF:     0° (12 o'clock — straight up)
 * - ON:    120° (4 o'clock)
 * - CUSTOM: 240° (8 o'clock)
 */
const MODE_ANGLES: Record<BypassMode, number> = {
    OFF: 0,
    ON: 120,
    CUSTOM: 240,
};

/**
 * Ring stroke colors per mode — matches the Darcula theme palette.
 * - OFF:    muted grey (no context for this tab)
 * - ON:     accent blue (context on with default reach)
 * - CUSTOM: warm amber (context on with lever control, Pro)
 */
const MODE_COLORS: Record<BypassMode, string> = {
    OFF: "#808080",
    ON: "#569cd6",
    CUSTOM: "#dcdcaa",
};

/**
 * Human-readable labels shown in the tooltip on hover.
 */
const MODE_LABELS: Record<BypassMode, string> = {
    OFF: "Context: OFF — no IDE context for this tab",
    ON: "Context: ON — full context gathering",
    CUSTOM: "Context: Custom — control context reach (Pro)",
};

// ── Component ────────────────────────────────────────────────────────────

function ContextDial({ mode, onModeChange, canUseSelective, disabled = false }: ContextDialProps) {
    /**
     * Determine the next mode in the clockwise cycle.
     *
     * The per-tab dial allows the user to disable context for a specific
     * tab even when context is globally enabled. This is a valid use case:
     * e.g. a casual chat that doesn't need IDE context.
     *
     * Basic tier (canUseSelective=false): OFF → ON → OFF (2 positions)
     * Pro tier   (canUseSelective=true):  OFF → ON → CUSTOM → OFF (3 positions)
     *
     * Clicks are silently rejected when disabled=true (context globally off).
     */
    const handleClick = () => {
        if (disabled) return;

        if (canUseSelective) {
            // Pro: 3-position cycle — OFF → ON → CUSTOM → OFF
            const next: Record<BypassMode, BypassMode> = {
                OFF: "ON",
                ON: "CUSTOM",
                CUSTOM: "OFF",
            };
            onModeChange(next[mode]);
        } else {
            // Basic: 2-position cycle — OFF → ON → OFF
            onModeChange(mode === "OFF" ? "ON" : "OFF");
        }
    };

    const angle = MODE_ANGLES[mode];
    // When disabled, grey out the dial regardless of mode
    const color = disabled ? "#4a4a4a" : MODE_COLORS[mode];
    const label = disabled
        ? "Context gathering disabled (see Settings → Context)"
        : MODE_LABELS[mode];

    // Calculate notch position from angle.
    // SVG: 0° = 12 o'clock = negative Y direction.
    // Math: convert degrees to radians, offset by -90° so 0° points up.
    const rad = ((angle - 90) * Math.PI) / 180;
    const cx = SIZE / 2 + NOTCH_DIST * Math.cos(rad);
    const cy = SIZE / 2 + NOTCH_DIST * Math.sin(rad);

    return (
        <button
            className={`ymm-context-dial ymm-context-dial--${mode.toLowerCase()}${disabled ? " ymm-context-dial--disabled" : ""}`}
            onClick={handleClick}
            title={label}
            aria-label={label}
            aria-disabled={disabled}
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
