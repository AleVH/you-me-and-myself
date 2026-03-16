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
         * Master toggle for context gathering.
         *
         * When false, the ContextAssembler skips all context gathering
         * regardless of the per-message bypassMode. Default: true.
         *
         * STUB: Not yet read by ContextAssembler. The per-message
         * bypassMode from SendMessage is the only active control.
         */
        var contextEnabled: Boolean = true,

        /**
         * Default bypass mode for new tabs.
         *
         * Values: "OFF" (full context gathering), "FULL" (no context).
         * Pro tier can also use "SELECTIVE" (per-component).
         *
         * STUB: Not yet read by the frontend. New tabs always start
         * with bypassMode = "OFF" in TabData.
         */
        var defaultBypassMode: String = "OFF"
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
