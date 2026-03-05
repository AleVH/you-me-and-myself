package com.youmeandmyself.storage

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.youmeandmyself.ai.chat.bridge.BridgeMessage
import com.youmeandmyself.dev.Dev
import java.time.Instant

/**
 * Manages the open_tabs SQLite table for tab state persistence.
 *
 * ## What This Does
 *
 * Persists the frontend's open tab state (which tabs are open, their order,
 * which is active, scroll positions) so that tabs survive IDE restarts.
 * Controlled by the `keep_tabs` setting in storage_config.
 *
 * ## Storage Strategy
 *
 * Uses a full-replace approach: on save, all existing rows for the project
 * are deleted and re-inserted in a single transaction. This is simpler than
 * incremental updates and avoids sync issues between frontend and backend.
 * The table is small (typically <20 rows) so this is fast.
 *
 * ## Relationship to Other Services
 *
 * - [LocalStorageFacade] — provides database access
 * - [BridgeDispatcher] — calls this service from tab management command handlers
 * - Frontend useBridge.ts — sends SAVE_TAB_STATE / REQUEST_TAB_STATE commands
 *
 * @param project The IntelliJ project context
 */
@Service(Service.Level.PROJECT)
class TabStateService(private val project: Project) {

    private val log = Dev.logger(TabStateService::class.java)

    private val facade: LocalStorageFacade
        get() = LocalStorageFacade.getInstance(project)

    // ═══════════════════════════════════════════════════════════════════
    //  CONFIG
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Check whether tab persistence is enabled.
     *
     * Reads the `keep_tabs` key from storage_config. Defaults to true
     * if the key doesn't exist (opt-out rather than opt-in).
     *
     * @return True if tabs should be saved/restored across restarts
     */
    fun isKeepTabsEnabled(): Boolean {
        return try {
            facade.withReadableDatabase { db ->
                val value = db.queryOne(
                    "SELECT config_value FROM storage_config WHERE config_key = ?",
                    "keep_tabs"
                ) { rs -> rs.getString("config_value") }

                // Default to true if not set
                value?.lowercase() != "false"
            }
        } catch (e: Exception) {
            Dev.warn(log, "tab_state.keep_tabs_read_failed", e)
            true // Default to keeping tabs on error
        }
    }

    /**
     * Set the keep_tabs preference.
     *
     * @param enabled True to persist tabs, false to start fresh on restart
     */
    fun setKeepTabs(enabled: Boolean) {
        try {
            facade.withDatabase { db ->
                db.execute(
                    """INSERT INTO storage_config (config_key, config_value)
                       VALUES ('keep_tabs', ?)
                       ON CONFLICT(config_key) DO UPDATE SET config_value = ?""",
                    enabled.toString(), enabled.toString()
                )
            }
            Dev.info(log, "tab_state.keep_tabs_set", "enabled" to enabled)
        } catch (e: Exception) {
            Dev.warn(log, "tab_state.keep_tabs_set_failed", e)
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  SAVE / LOAD
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Save the full tab state for the current project.
     *
     * Replaces all existing rows in a single transaction. The frontend
     * sends the complete tab list on every meaningful change, so we don't
     * need incremental updates.
     *
     * @param tabs The complete tab state from the frontend
     */
    fun saveAll(tabs: List<BridgeMessage.TabStateDto>) {
        val projectId = facade.resolveProjectId()

        try {
            facade.withDatabase { db ->
                db.inTransaction {
                    // Delete all existing tabs for this project
                    db.execute(
                        "DELETE FROM open_tabs WHERE project_id = ?",
                        projectId
                    )

                    // Insert all current tabs
                    for (tab in tabs) {
                        db.execute(
                            """INSERT INTO open_tabs
                               (id, project_id, conversation_id, title, tab_order, is_active, scroll_position, created_at)
                               VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
                            tab.id,
                            projectId,
                            tab.conversationId,
                            tab.title,
                            tab.tabOrder,
                            tab.isActive,
                            tab.scrollPosition,
                            Instant.now().toString()
                        )
                    }
                }
            }

            Dev.info(log, "tab_state.saved",
                "tabCount" to tabs.size,
                "projectId" to projectId
            )
        } catch (e: Exception) {
            Dev.error(log, "tab_state.save_failed", e,
                "tabCount" to tabs.size
            )
        }
    }

    /**
     * Load all saved tabs for the current project.
     *
     * Returns tabs ordered by tab_order. If no tabs are saved (first run
     * or keep_tabs was false), returns an empty list — the frontend will
     * create a single fresh tab.
     *
     * @return Saved tab state, or empty list if none
     */
    fun loadAll(): List<BridgeMessage.TabStateDto> {
        val projectId = facade.resolveProjectId()

        return try {
            facade.withReadableDatabase { db ->
                db.query(
                    """SELECT id, conversation_id, title, tab_order, is_active, scroll_position
                       FROM open_tabs
                       WHERE project_id = ?
                       ORDER BY tab_order""",
                    projectId
                ) { rs ->
                    BridgeMessage.TabStateDto(
                        id = rs.getString("id"),
                        conversationId = rs.getString("conversation_id"),
                        title = rs.getString("title"),
                        tabOrder = rs.getInt("tab_order"),
                        isActive = rs.getInt("is_active") == 1,
                        scrollPosition = rs.getInt("scroll_position")
                    )
                }
            }
        } catch (e: Exception) {
            Dev.warn(log, "tab_state.load_failed", e)
            emptyList()
        }
    }

    /**
     * Remove a single tab from persistent state.
     *
     * Called when the user closes a tab. The conversation itself is NOT
     * deleted — it stays in the conversations table and is accessible
     * from the Library.
     *
     * @param tabId The frontend tab ID to remove
     */
    fun removeTab(tabId: String) {
        try {
            facade.withDatabase { db ->
                db.execute(
                    "DELETE FROM open_tabs WHERE id = ?",
                    tabId
                )
            }
            Dev.info(log, "tab_state.tab_removed", "tabId" to tabId)
        } catch (e: Exception) {
            Dev.warn(log, "tab_state.remove_failed", e, "tabId" to tabId)
        }
    }

    /**
     * Clear all saved tabs for the current project.
     *
     * Used when keep_tabs is toggled off, or for testing.
     */
    fun clearAll() {
        val projectId = facade.resolveProjectId()

        try {
            facade.withDatabase { db ->
                db.execute(
                    "DELETE FROM open_tabs WHERE project_id = ?",
                    projectId
                )
            }
            Dev.info(log, "tab_state.cleared", "projectId" to projectId)
        } catch (e: Exception) {
            Dev.warn(log, "tab_state.clear_failed", e)
        }
    }

    companion object {
        fun getInstance(project: Project): TabStateService =
            project.getService(TabStateService::class.java)
    }
}