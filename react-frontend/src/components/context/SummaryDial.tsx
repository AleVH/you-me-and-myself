/**
 * SummaryDial — compact rotary-style toggle for per-tab summary mode.
 *
 * ═══════════════════════════════════════════════════════════════════════
 * CONTEXT vs SUMMARY — These are TWO INDEPENDENT features. Never conflate them.
 *
 * CONTEXT = WHAT gets gathered from the IDE.
 *   It defines the SCOPE of files sent to the AI: the open file, related files,
 *   the whole class, a method, a radial reach through the project tree, etc.
 *   The Context Dial controls this scope. More context = more files included.
 *   Without context, the AI only sees the user's raw message.
 *
 * SUMMARY = HOW COMPACT those files are.
 *   Whatever context was gathered, the summary pipeline COMPRESSES those files
 *   into shorter representations so they use fewer tokens. It does NOT add
 *   anything. It does NOT enrich anything. It just SHRINKS what's already there.
 *   Without summary, the gathered files go to the AI as full raw text.
 *
 * They are SEQUENTIAL: context decides WHAT is included, summary decides
 * HOW COMPACT it is. Summary without context is meaningless (nothing to
 * compress). Context without summary means full raw files go to the AI.
 *
 * Each feature has TWO LEVELS of control:
 *   GLOBAL (Settings page) — master kill-switch, overrules everything.
 *   LOCAL  (per-tab dial)  — user can override per conversation.
 * ═══════════════════════════════════════════════════════════════════════
 *
 * ## Visual Design
 *
 * A 24px SVG circle with a notch indicator that rotates to one of 2
 * positions (clock analogy):
 *
 *   12 o'clock = OFF  (grey #808080)  — context files sent as-is (raw text)
 *    6 o'clock = ON   (green #6a9955) — context files compressed before sending
 *
 * ## Two Levels of Control
 *
 * GLOBAL (Settings page):
 *   - Master kill-switch: summaryEnabled in SummaryConfigService.
 *   - When OFF globally, the dial is greyed out and non-interactive.
 *   - Global overrules local — always.
 *
 * LOCAL (per-tab, this dial):
 *   - The user can choose OFF / ON for each individual tab.
 *   - OFF = context files go to the AI as full raw text (no compression).
 *   - ON  = context files are summarised (compressed) before going to the AI.
 *
 * Having OFF on the per-tab dial is NOT contradictory: the user wants
 * summary enabled globally but may disable it for a specific conversation
 * (e.g. they want the AI to see full raw code for a detailed review).
 *
 * ## Cycle
 *
 *   Launch: OFF → ON → OFF (2 positions)
 *   Future (Pro): OFF → ON → CUSTOM → OFF (3 positions, post-launch)
 *
 * ## Integration
 *
 * Used inside ContextDialStrip, to the right of the ContextDial.
 * The strip reads the active tab's summaryEnabled from TabData
 * and converts it to SummaryMode ("ON"/"OFF") for this component.
 *
 * @see ContextDialStrip — parent wrapper (holds both context and summary dials)
 * @see ContextDial — the context scope dial (independent, do not conflate)
 * @see ContextAssembler.assemble — backend skips enrichment when summaryEnabled=false
 */
import "./SummaryDial.css";

// ── Types ────────────────────────────────────────────────────────────────

/**
 * Per-tab summary mode (user perspective).
 *
 * - "OFF": Context files go to the AI as full raw text (no compression).
 * - "ON":  Context files are summarised (compressed) before going to the AI.
 *
 * Future (Pro, post-launch): "CUSTOM" position with lever for granular
 * control over compression level. Placeholder only — not built yet.
 */
export type SummaryMode = "OFF" | "ON";

export interface SummaryDialProps {
    /** Current summary mode for the active tab. */
    mode: SummaryMode;
    /** Called when the user clicks to cycle to the next mode. */
    onModeChange: (mode: SummaryMode) => void;
    /**
     * When true, the dial is greyed out and clicks are rejected.
     * Driven by globalSummaryEnabled from SummaryConfigService.
     * The kill-switch in Settings → Summary disables this.
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
 * Two positions use opposite poles for clear visual distinction.
 * - OFF:   0° (12 o'clock — straight up)
 * - ON:  180° (6 o'clock — straight down)
 *
 * Future (Pro): CUSTOM at 270° (9 o'clock) when 3-position cycle is added.
 */
const MODE_ANGLES: Record<SummaryMode, number> = {
    OFF: 0,
    ON: 180,
};

/**
 * Ring stroke colors per mode — matches the Darcula theme palette.
 * - OFF: muted grey (no compression, raw files)
 * - ON:  green (compressed, smaller token footprint)
 *
 * Green (#6a9955) is deliberately different from the Context Dial's blue
 * (#569cd6) to visually distinguish the two independent features.
 */
const MODE_COLORS: Record<SummaryMode, string> = {
    OFF: "#808080",
    ON: "#6a9955",
};

/**
 * Human-readable labels shown in the tooltip on hover.
 */
const MODE_LABELS: Record<SummaryMode, string> = {
    OFF: "Summary: OFF — context files sent as-is (full text)",
    ON: "Summary: ON — context files compressed before sending",
};

// ── Component ────────────────────────────────────────────────────────────

function SummaryDial({ mode, onModeChange, disabled = false }: SummaryDialProps) {
    /**
     * Toggle between OFF and ON.
     *
     * Launch: 2-position cycle — OFF → ON → OFF.
     * Future (Pro, post-launch): 3-position cycle with CUSTOM for
     * granular compression control via a lever. Not built yet.
     *
     * Clicks are silently rejected when disabled=true (summary globally off).
     */
    const handleClick = () => {
        if (disabled) return;

        // Launch: simple 2-position toggle
        onModeChange(mode === "OFF" ? "ON" : "OFF");

        // Future (Pro): 3-position cycle when canUseSelective is added
        // const next: Record<SummaryMode, SummaryMode> = {
        //     OFF: "ON", ON: "CUSTOM", CUSTOM: "OFF",
        // };
        // onModeChange(next[mode]);
    };

    const angle = MODE_ANGLES[mode];
    // When disabled, grey out the dial regardless of mode
    const color = disabled ? "#4a4a4a" : MODE_COLORS[mode];
    const label = disabled
        ? "Summary disabled (see Settings → Summary)"
        : MODE_LABELS[mode];

    // Calculate notch position from angle.
    // SVG: 0° = 12 o'clock = negative Y direction.
    // Math: convert degrees to radians, offset by -90° so 0° points up.
    const rad = ((angle - 90) * Math.PI) / 180;
    const cx = SIZE / 2 + NOTCH_DIST * Math.cos(rad);
    const cy = SIZE / 2 + NOTCH_DIST * Math.sin(rad);

    return (
        <button
            className={`ymm-summary-dial ymm-summary-dial--${mode.toLowerCase()}${disabled ? " ymm-summary-dial--disabled" : ""}`}
            onClick={handleClick}
            title={label}
            aria-label={label}
            aria-disabled={disabled}
            type="button"
        >
            <svg
                className="ymm-summary-dial__svg"
                width={SIZE}
                height={SIZE}
                viewBox={`0 0 ${SIZE} ${SIZE}`}
                xmlns="http://www.w3.org/2000/svg"
            >
                {/* Outer ring — color indicates current mode */}
                <circle
                    className="ymm-summary-dial__ring"
                    cx={SIZE / 2}
                    cy={SIZE / 2}
                    r={RING_R}
                    fill="none"
                    stroke={color}
                    strokeWidth={1.5}
                />

                {/* Notch indicator — rotates to the active position */}
                <circle
                    className="ymm-summary-dial__notch"
                    cx={cx}
                    cy={cy}
                    r={NOTCH_R}
                    fill={color}
                />

                {/* Center dot — subtle anchor point */}
                <circle
                    className="ymm-summary-dial__center"
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

export default SummaryDial;
