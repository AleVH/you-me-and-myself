/**
 * ContextDialStrip — compact bar holding both dials, lever, and status labels.
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
 * ## Layout
 *
 * Compact (default):
 *   [ ContextDial ] Context: ON  |  [ SummaryDial ] Summary: ON   [ ⤢ ]
 *
 * Custom (Pro, context lever):
 *   [ ContextDial ] Context: Custom | [ SummaryDial ] Summary: ON [ ⤢ ]
 *   [ ContextLever ──────────── ] Partial  Coming soon
 *
 * Off (per-tab overrides):
 *   [ ContextDial ] Context: OFF | [ SummaryDial ] Summary: OFF
 *
 * Disabled (global kill-switches):
 *   [ ContextDial (greyed) ] Context gathering disabled | [ SummaryDial (greyed) ] Summary disabled
 *
 * ## Two Levels of Control (applies to BOTH dials independently)
 *
 * GLOBAL (Settings page) overrules LOCAL (per-tab dial). When a feature
 * is globally disabled, its dial is greyed out and non-interactive.
 * When globally enabled, each tab can individually choose OFF or ON.
 *
 * Context dial: OFF / ON / CUSTOM (Pro)
 * Summary dial: OFF / ON (CUSTOM post-launch)
 *
 * ## Positioning
 *
 * Sits between MessageList and InputBar in ChatApp's layout.
 * Fixed 28px tall in compact mode. Expands vertically when the
 * context lever is shown (CUSTOM mode + expanded toggle).
 *
 * ## Per-Tab State
 *
 * Each tab has its own contextMode AND summaryEnabled stored in TabData
 * (persisted to open_tabs via SAVE_TAB_STATE). The strip reads both
 * and calls the respective callbacks to update the active tab's TabData.
 *
 * @see ContextDial — the context scope dial (OFF/ON/CUSTOM per tab)
 * @see SummaryDial — the summary compression dial (OFF/ON per tab)
 * @see ContextLever — the per-component slider (STUB, context only)
 * @see ChatApp — mounts this between MessageList and InputBar
 */
import { useState, useCallback } from "react";
import ContextDial from "./ContextDial";
import type { BypassMode } from "./ContextDial";
import SummaryDial from "./SummaryDial";
import type { SummaryMode } from "./SummaryDial";
import ContextLever from "./ContextLever";
import "./ContextDialStrip.css";

// ── Re-export types for convenience ──────────────────────────────────
export type { BypassMode } from "./ContextDial";
export type { SummaryMode } from "./SummaryDial";

// ── Types ────────────────────────────────────────────────────────────────

export interface ContextDialStripProps {
    /** Current context mode for the active tab (user perspective). */
    mode: BypassMode;
    /** Called when the user changes the mode via the dial. */
    onModeChange: (mode: BypassMode) => void;
    /**
     * Whether CUSTOM mode is available (Pro tier).
     * When false, the dial cycles OFF ↔ ON (2 positions for Basic tier).
     * When true, the dial cycles OFF → ON → CUSTOM → OFF (3 positions).
     */
    canUseSelective: boolean;
    /**
     * Whether context gathering is globally enabled.
     * When false, the dial is greyed out and clicks are rejected.
     * Driven by ContextSettingsState.contextEnabled from the backend.
     * Global overrules local — always.
     */
    globalContextEnabled?: boolean;
    /**
     * Called when the user changes the lever position (CUSTOM mode).
     * @param level 0 = Minimal, 1 = Partial, 2 = Full
     */
    onLevelChange?: (level: number) => void;
    /** Current selective level for the active tab (0-2). */
    selectiveLevel?: number;

    // ── Summary dial props (independent from context) ────────────────
    /**
     * Current summary mode for the active tab.
     * OFF = context files go to the AI as full raw text (no compression).
     * ON  = context files are summarised (compressed) before sending.
     */
    summaryMode?: SummaryMode;
    /** Called when the user clicks the summary dial to toggle mode. */
    onSummaryModeChange?: (mode: SummaryMode) => void;
    /**
     * Whether summary is globally enabled.
     * When false, the summary dial is greyed out and clicks are rejected.
     * Driven by SummaryConfigService.enabled from the backend.
     * Global overrules local — always.
     */
    globalSummaryEnabled?: boolean;

    // ── Force Context props ──────────────────────────────────────────
    /**
     * Current force context scope for the active tab.
     * null = no force. "method" = force method at cursor. "class" = force class at cursor.
     * Cycles: null → method → class → null on button click.
     */
    forceContextScope?: "method" | "class" | null;
    /** Called when the user cycles the Force Context button. */
    onForceContextChange?: (scope: "method" | "class" | null) => void;
}

// ── Human-readable mode labels (user perspective) ────────────────────

// Context dial labels:
// OFF    = no context for this tab (per-tab override, valid when global is ON)
// ON     = context gathering with default reach
// CUSTOM = context gathering with lever for reach control (Pro tier)
const MODE_DISPLAY: Record<BypassMode, string> = {
    OFF: "Context: OFF",
    ON: "Context: ON",
    CUSTOM: "Context: Custom",
};

// Summary dial labels:
// OFF = context files go to the AI as full raw text (no compression)
// ON  = context files are summarised (compressed) before sending
const SUMMARY_DISPLAY: Record<SummaryMode, string> = {
    OFF: "Summary: OFF",
    ON: "Summary: ON",
};

// ── Component ────────────────────────────────────────────────────────────

function ContextDialStrip({
    mode,
    onModeChange,
    canUseSelective,
    globalContextEnabled = true,
    onLevelChange,
    selectiveLevel = 2,
    summaryMode = "ON",
    onSummaryModeChange,
    globalSummaryEnabled = true,
    forceContextScope = null,
    onForceContextChange,
}: ContextDialStripProps) {
    // Expanded state — controls whether the lever row is visible.
    // Only meaningful when mode is SELECTIVE. Persists while the
    // component is mounted (resets on tab switch / IDE restart).
    const [expanded, setExpanded] = useState(false);

    const toggleExpand = useCallback(() => {
        setExpanded((prev) => !prev);
    }, []);

    // The lever is only relevant in CUSTOM mode — it controls the reach of context gathering
    const showLever = mode === "CUSTOM" && expanded;

    // Force Context button: cycles null → method → class → null
    const cycleForceContext = useCallback(() => {
        if (!onForceContextChange) return;
        const next = forceContextScope === null ? "method"
            : forceContextScope === "method" ? "class"
            : null;
        onForceContextChange(next as "method" | "class" | null);
    }, [forceContextScope, onForceContextChange]);

    const forceLabel = forceContextScope === null ? "Force: -"
        : forceContextScope === "method" ? "Force: Method"
        : "Force: Class";

    // ── Context label ────────────────────────────────────────────────
    // When globally disabled, show that context gathering is off
    const contextLabel = !globalContextEnabled
        ? "Context gathering disabled"
        : MODE_DISPLAY[mode];

    // ── Summary label ────────────────────────────────────────────────
    // Independent from context — when globally disabled, show that summary is off
    const summaryLabel = !globalSummaryEnabled
        ? "Summary disabled"
        : SUMMARY_DISPLAY[summaryMode];

    return (
        <div className={`ymm-context-strip ${expanded ? "ymm-context-strip--expanded" : ""}`}>
            {/* ── Primary row: context dial + summary dial + expand toggle ─── */}
            <div className="ymm-context-strip__row">
                {/* ── Context section (left) ── */}
                <ContextDial
                    mode={mode}
                    onModeChange={onModeChange}
                    canUseSelective={canUseSelective}
                    disabled={!globalContextEnabled}
                />

                <span className={`ymm-context-strip__label ymm-context-strip__label--${mode.toLowerCase()}${!globalContextEnabled ? " ymm-context-strip__label--disabled" : ""}`}>
                    {contextLabel}
                </span>

                {/* ── Separator between context and summary ── */}
                <span className="ymm-context-strip__separator" aria-hidden="true" />

                {/* ── Summary section (right) — independent from context ── */}
                <SummaryDial
                    mode={summaryMode}
                    onModeChange={onSummaryModeChange ?? (() => {})}
                    disabled={!globalSummaryEnabled}
                />

                <span className={`ymm-context-strip__summary-label ymm-context-strip__summary-label--${summaryMode.toLowerCase()}${!globalSummaryEnabled ? " ymm-context-strip__summary-label--disabled" : ""}`}>
                    {summaryLabel}
                </span>

                {/* ── Force Context button ── */}
                {/* Visible whenever global context is ON, regardless of per-tab dial.
                    The user can force a method/class even if the tab dial is OFF.
                    Force is per-tab and overrides the per-tab dial (but NOT the global kill switch). */}
                {globalContextEnabled && (
                    <>
                        <span className="ymm-context-strip__separator" aria-hidden="true" />
                        <button
                            className={`ymm-context-strip__force ${forceContextScope ? "ymm-context-strip__force--active" : ""}`}
                            onClick={cycleForceContext}
                            title="Force context: cycle through Nothing / Method / Class"
                            aria-label={forceLabel}
                            type="button"
                        >
                            {forceLabel}
                        </button>
                    </>
                )}

                {/* Expand toggle — only shown when CUSTOM is the active context mode,
                    since that's the only mode with additional details (the context lever).
                    This is for context only — summary has no lever yet (post-launch). */}
                {mode === "CUSTOM" && globalContextEnabled && (
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
                        level={selectiveLevel}
                        onLevelChange={onLevelChange}
                    />
                </div>
            )}
        </div>
    );
}

export default ContextDialStrip;
