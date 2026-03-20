package com.youmeandmyself.ai.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project

/**
 * Persistent user preferences for the tab system.
 *
 * Stores **user preferences** (keepTabs, maxTabs) in IntelliJ XML.
 * The actual tab state data (which tabs are open, scroll positions,
 * conversation IDs) lives in SQLite `open_tabs` — that is runtime state
 * managed by [com.youmeandmyself.storage.TabStateService].
 *
 * ## Persistence Boundary
 *
 * - **XML (this class):** "Should tabs be restored?" and "How many tabs max?"
 * - **SQLite (open_tabs):** "Which tabs were open and where was the user scrolled to?"
 *
 * The XML preference controls whether the SQLite state is saved/loaded at all.
 *
 * @see com.youmeandmyself.storage.TabStateService — reads these preferences
 * @see com.youmeandmyself.ai.settings.GeneralConfigurable — UI for these preferences
 */
@Service(Service.Level.PROJECT)
@State(
    name = "YmmTabSettings",
    storages = [Storage("ymmTabSettings.xml")]
)
class TabSettingsState(
    @Suppress("unused") private val project: Project
) : PersistentStateComponent<TabSettingsState.State> {

    /**
     * Serialized state. All fields must have defaults for safe deserialization.
     */
    data class State(
        /**
         * Restore open conversation tabs when the IDE restarts.
         * Default: `true` (opt-out pattern — most users expect tab restoration).
         */
        var keepTabs: Boolean = true,

        /**
         * Maximum number of simultaneously open tabs (2–20).
         * Default: 5. Frontend enforces this cap when opening new tabs.
         */
        var maxTabs: Int = 5
    )

    private var myState = State()

    override fun getState(): State = myState
    override fun loadState(state: State) { myState = state }

    companion object {
        fun getInstance(project: Project): TabSettingsState =
            project.getService(TabSettingsState::class.java)
    }
}
