package com.youmeandmyself.ai.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project

/**
 * Persistent settings state for Context Control (Block 5).
 *
 * ## Location in Settings Tree
 *
 * Settings → Tools → YMM Assistant → Context
 * Managed by [ContextConfigurable] (the UI page).
 *
 * ## What Lives Here
 *
 * User preferences for context gathering behavior. STUB at launch —
 * the backend currently only uses per-message bypassMode from
 * SendMessage. These global defaults will be wired when the full
 * context control system ships.
 *
 * ### Launch (Individual Basic) — STUB
 * - contextEnabled — master toggle for context gathering
 * - defaultBypassMode — default mode for new tabs ("OFF" or "FULL")
 *
 * ### Post-Launch (Pro Tier) — fields ready, UI not yet built
 * - defaultBypassMode can also be "SELECTIVE" for Pro users
 *
 * ## Persistence
 *
 * Stored in IntelliJ's project-level XML configuration via
 * @State/@Storage annotations. Survives IDE restarts. Each project
 * has its own context settings.
 *
 * ## Working Copy Pattern
 *
 * ContextConfigurable loads from here on reset(), and writes back
 * on apply(). Users can change fields and cancel without saving.
 *
 * @param project The IntelliJ project these settings belong to
 */
@Service(Service.Level.PROJECT)
@State(
    name = "YmmContextSettings",
    storages = [Storage("ymmContextSettings.xml")]
)
class ContextSettingsState(
    private val project: Project
) : PersistentStateComponent<ContextSettingsState.State> {

    /**
     * The serializable state container.
     *
     * All fields use `var` with defaults so IntelliJ's XML persistence
     * can instantiate and populate them. New fields MUST have defaults
     * to maintain backward compatibility with existing settings files.
     */
    data class State(
        // ── Launch (Individual Basic) ─────────────────────────────────

        /**
         * Master kill-switch for context gathering.
         *
         * When false, ContextAssembler returns the user's raw input with zero
         * context attached, regardless of the per-tab bypassMode dial position.
         * Default: true (context gathering enabled).
         *
         * Propagation: instant — ContextAssembler reads this at call time (not cached).
         * The ContextConfigurable ActionListener also updates this immediately on checkbox
         * toggle so the kill-switch takes effect before Apply is clicked.
         */
        var contextEnabled: Boolean = true,

        /**
         * Default dial position for new tabs (dial perspective, not backend perspective).
         *
         * Values (dial view): "OFF" = no context, "FULL" = full context gathering.
         * Pro tier can also have "SELECTIVE" as a default once the combo option is added.
         * Default: "FULL" (new tabs start with full context on).
         *
         * Read by BridgeDispatcher.handleRequestContextSettings() on startup.
         * Sent to React as defaultBypassMode in ContextSettingsEvent.
         * Basic-tier users always receive "FULL" regardless of stored value.
         */
        var defaultBypassMode: String = "FULL",

        // ── Future: Traversal Radius ──────────────────────────────────

        /**
         * How far out from the current focus point the context assembler
         * reaches into the dependency graph.
         *
         * Think of it as the number of hops: radius=1 means only immediate
         * callers and callees of the current class/method are included.
         * radius=2 includes their callers/callees too, and so on.
         * Wider radius = more surrounding context = more tokens.
         * The developer controls this as an explicit cost/quality trade-off.
         *
         * This is the project-level default. Individual tabs can override it
         * (see TabStateDto.traversalRadius).
         *
         * Design doc: "AI-Assisted Code Reasoning Plugin.md" — Architecture section.
         *
         * Range: 1–5 (TBD based on empirical token cost testing).
         * Default: 1 (immediate neighbours only — safe, predictable cost).
         *
         * STUB: Not yet read by ContextAssembler or exposed in ContextConfigurable UI.
         */
        var traversalRadius: Int = 1,

        // ── Future: Infrastructure Visibility ────────────────────────

        /**
         * Controls how cross-cutting infrastructure classes are included in context.
         *
         * ## The Problem
         *
         * Some classes are referenced from nearly everywhere: Auth guards, Logger
         * utilities, DB connection factories, DI containers, event buses, base
         * repositories. These are high fan-in nodes — not because they are central
         * to the current question, but because they are shared plumbing.
         *
         * When the context assembler pulls in surrounding code via the traversal
         * radius, these infrastructure nodes appear connected to almost every class.
         * Including them at full detail introduces noise that distorts the AI's
         * understanding of the actual call chain — everything looks related to Auth
         * because Auth is referenced everywhere.
         *
         * ## The Solution
         *
         * Three visibility levels:
         *
         *   "OFF"    — infrastructure nodes are excluded from context entirely.
         *              Use when you want laser focus on business logic with no noise.
         *
         *   "BRIEF"  — infrastructure nodes are included as their summary/contract only
         *              (one-line description of what the class does). The AI knows they
         *              exist but doesn't mistake them for central participants.
         *              This is the recommended default.
         *
         *   "DETAIL" — infrastructure nodes are included at full context depth,
         *              same as any other class. Use when the question is specifically
         *              about infrastructure behaviour (e.g. "how does our Auth work?").
         *
         * ## What counts as infrastructure?
         *
         * Detection is based on fan-in score from the structural skeleton (PSI).
         * A class exceeding a configurable fan-in threshold is classified as
         * infrastructure. The threshold is project-level and can be tuned.
         * (Fan-in threshold configuration is a separate future field.)
         *
         * This is the project-level default. Individual tabs can override it
         * (see TabStateDto.infrastructureVisibility).
         *
         * Design doc: "AI-Assisted Code Reasoning Plugin.md" — Architecture section.
         *
         * STUB: Not yet read by ContextAssembler or exposed in ContextConfigurable UI.
         */
        var infrastructureVisibility: String = "BRIEF"
    )

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }

    companion object {
        /**
         * Get the ContextSettingsState instance for a project.
         */
        fun getInstance(project: Project): ContextSettingsState {
            return project.getService(ContextSettingsState::class.java)
        }
    }
}
