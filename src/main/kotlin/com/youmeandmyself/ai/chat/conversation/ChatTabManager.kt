// File: src/main/kotlin/com/youmeandmyself/ai/chat/conversation/ChatTabManager.kt
package com.youmeandmyself.ai.chat.conversation

import com.youmeandmyself.ai.chat.service.ChatState
import com.youmeandmyself.dev.Dev
import java.util.UUID

/**
 * Manages the lifecycle of conversation tabs in the Chat panel.
 *
 * Responsibilities:
 * - Track which tabs are open and which is active
 * - Create new tabs (empty or from a conversation ID)
 * - Switch between tabs (save/restore scroll state)
 * - Close tabs (with minimum-one-tab guarantee)
 * - Detect if a conversation is already open (for Library "Reopen" deduplication)
 *
 * ## Design
 *
 * This is a pure state manager — it does NOT touch the UI.
 * The ChatPanel reads state from here and updates Swing/JCEF accordingly.
 * This separation makes the logic testable without UI dependencies.
 *
 * ## Tab vs Conversation
 *
 * - A **tab** is ephemeral (lives in memory while the IDE is open)
 * - A **conversation** is persistent (stored in SQLite, survives IDE restarts)
 * - A tab may not have a conversation yet (fresh "New Chat" tab)
 * - A conversation may not have a tab (closed tab, viewable in Library)
 * - Multiple tabs cannot point to the same conversation (enforced by [openConversation])
 *
 * ## Listener
 *
 * The [TabChangeListener] callback notifies ChatPanel when tabs change,
 * so it can update the tab bar UI, swap browser content, etc.
 */
class ChatTabManager {

    private val log = Dev.logger(ChatTabManager::class.java)

    private val _tabs = mutableListOf<TabState>()
    private var _activeTabId: String? = null
    private var listener: TabChangeListener? = null

    /** Read-only view of all open tabs, in order. */
    val tabs: List<TabState> get() = _tabs.toList()

    /** The currently active tab, or null if no tabs exist (shouldn't happen). */
    val activeTab: TabState? get() = _tabs.find { it.id == _activeTabId }

    /** The currently active tab ID. */
    val activeTabId: String? get() = _activeTabId

    // ── Listener ────────────────────────────────────────────────────────

    fun setListener(listener: TabChangeListener) {
        this.listener = listener
    }

    // ── Initialization ──────────────────────────────────────────────────

    /**
     * Initialize with a single empty tab.
     * Called once when ChatPanel is created.
     */
    fun initialize() {
        if (_tabs.isNotEmpty()) return // already initialized

        val tab = createEmptyTabState()
        _tabs.add(tab)
        _activeTabId = tab.id

        Dev.info(log, "tabs.initialized", "tab_id" to tab.id)
    }

    // ── Tab Creation ────────────────────────────────────────────────────

    /**
     * Create a new empty tab and make it active.
     *
     * Called when the user clicks [+] in the tab bar.
     *
     * @return The new tab state
     */
    fun newTab(): TabState {
        val tab = createEmptyTabState()
        _tabs.add(tab)

        val previousTabId = _activeTabId
        _activeTabId = tab.id

        Dev.info(log, "tabs.new",
            "tab_id" to tab.id,
            "total_tabs" to _tabs.size
        )

        listener?.onTabCreated(tab)
        if (previousTabId != null && previousTabId != tab.id) {
            listener?.onActiveTabChanged(previousTabId, tab.id)
        }

        return tab
    }

    /**
     * Open an existing conversation in a tab.
     *
     * If the conversation is already open in a tab, switch to that tab instead
     * of creating a duplicate.
     *
     * Called when the user clicks "Reopen Chat" in the Library.
     *
     * @param conversationId The conversation to open
     * @param title The conversation title for the tab
     * @return The tab state (new or existing)
     */
    fun openConversation(conversationId: String, title: String): TabState {
        // Check if already open
        val existingTab = _tabs.find { it.conversationId == conversationId }
        if (existingTab != null) {
            Dev.info(log, "tabs.open_existing",
                "conversation_id" to conversationId,
                "tab_id" to existingTab.id
            )
            switchTo(existingTab.id)
            return existingTab
        }

        // Check if the active tab is an empty "New Chat" — reuse it instead of creating another
        val active = activeTab
        val tab = if (active != null && active.isEmpty) {
            val reused = active.copy(
                conversationId = conversationId,
                title = title
            )
            val index = _tabs.indexOf(active)
            _tabs[index] = reused

            Dev.info(log, "tabs.reused_empty",
                "tab_id" to reused.id,
                "conversation_id" to conversationId
            )

            reused
        } else {
            val newTab = TabState(
                id = generateTabId(),
                conversationId = conversationId,
                title = title
            )
            _tabs.add(newTab)
            newTab
        }

        val previousTabId = _activeTabId
        _activeTabId = tab.id

        Dev.info(log, "tabs.open_conversation",
            "conversation_id" to conversationId,
            "tab_id" to tab.id,
            "total_tabs" to _tabs.size
        )

        listener?.onTabCreated(tab)
        if (previousTabId != null && previousTabId != tab.id) {
            listener?.onActiveTabChanged(previousTabId, tab.id)
        }

        return tab
    }

    // ── Tab Switching ───────────────────────────────────────────────────

    /**
     * Switch to a different tab.
     *
     * The ChatPanel should:
     * 1. Save current scroll position on the outgoing tab
     * 2. Clear the browser
     * 3. Re-render the incoming tab's messages
     * 4. Restore scroll position
     *
     * @param tabId The tab to switch to
     */
    fun switchTo(tabId: String) {
        if (tabId == _activeTabId) return

        val target = _tabs.find { it.id == tabId } ?: run {
            Dev.warn(log, "tabs.switch_failed", null, "tab_id" to tabId, "reason" to "not_found")
            return
        }

        val previousTabId = _activeTabId
        _activeTabId = target.id

        Dev.info(log, "tabs.switch",
            "from" to (previousTabId ?: "none"),
            "to" to target.id,
            "conversation_id" to (target.conversationId ?: "new")
        )

        if (previousTabId != null) {
            listener?.onActiveTabChanged(previousTabId, target.id)
        }
    }

    // ── Tab Closing ─────────────────────────────────────────────────────

    /**
     * Close a tab.
     *
     * If this is the last tab, a new empty tab is created automatically
     * (the chat panel always has at least one tab).
     *
     * @param tabId The tab to close
     */
    fun closeTab(tabId: String) {
        val tab = _tabs.find { it.id == tabId } ?: return
        val index = _tabs.indexOf(tab)

        _tabs.remove(tab)

        Dev.info(log, "tabs.close",
            "tab_id" to tabId,
            "conversation_id" to (tab.conversationId ?: "new"),
            "remaining" to _tabs.size
        )

        listener?.onTabClosed(tab)

        // Guarantee at least one tab
        if (_tabs.isEmpty()) {
            val newTab = createEmptyTabState()
            _tabs.add(newTab)
            _activeTabId = newTab.id
            listener?.onTabCreated(newTab)
            listener?.onActiveTabChanged(tabId, newTab.id)
            return
        }

        // If the closed tab was active, switch to an adjacent tab
        if (_activeTabId == tabId) {
            val newIndex = minOf(index, _tabs.size - 1)
            val newActive = _tabs[newIndex]
            _activeTabId = newActive.id
            listener?.onActiveTabChanged(tabId, newActive.id)
        }
    }

    // ── State Updates ───────────────────────────────────────────────────

    /**
     * Update tab state (e.g., after first message assigns a conversationId).
     *
     * Finds the tab by ID and replaces it with the updated state.
     */
    fun updateTab(tabId: String, update: (TabState) -> TabState) {
        val index = _tabs.indexOfFirst { it.id == tabId }
        if (index < 0) {
            Dev.warn(log, "tabs.update_failed", null, "tab_id" to tabId, "reason" to "not_found")
            return
        }
        _tabs[index] = update(_tabs[index])

        // Notify listener that tab metadata changed (title, etc.)
        listener?.onTabUpdated(_tabs[index])
    }

    /**
     * Save scroll position for a tab (called before switching away).
     */
    fun saveScrollPosition(tabId: String, exchangeId: String?, pixelOffset: Int?) {
        updateTab(tabId) { it.copy(scrollExchangeId = exchangeId, scrollPixelOffset = pixelOffset) }
    }

    /**
     * Update the loaded exchange count for a tab (for lazy loading tracking).
     */
    fun updateLoadedCount(tabId: String, count: Int) {
        updateTab(tabId) { it.copy(loadedExchangeCount = count) }
    }

    // ── Queries ─────────────────────────────────────────────────────────

    /**
     * Check if a conversation is already open in any tab.
     */
    fun isConversationOpen(conversationId: String): Boolean {
        return _tabs.any { it.conversationId == conversationId }
    }

    /**
     * Find the tab for a given conversation, if open.
     */
    fun findTabForConversation(conversationId: String): TabState? {
        return _tabs.find { it.conversationId == conversationId }
    }

    /**
     * Get tab count.
     */
    val tabCount: Int get() = _tabs.size

    // ── Private helpers ─────────────────────────────────────────────────

    private fun createEmptyTabState(): TabState {
        return TabState(
            id = generateTabId(),
            conversationId = null,
            title = "New Chat"
        )
    }

    private fun generateTabId(): String = "tab_${UUID.randomUUID().toString().take(8)}"
}

/**
 * Callback interface for ChatPanel to react to tab state changes.
 *
 * ChatPanel implements this to update the Swing tab bar and JCEF browser
 * when tabs are created, switched, closed, or updated.
 */
interface TabChangeListener {
    /** A new tab was added. Update the tab bar UI. */
    fun onTabCreated(tab: TabState)

    /** The active tab changed. Clear browser, render new tab's content, restore scroll. */
    fun onActiveTabChanged(fromTabId: String, toTabId: String)

    /** A tab was closed. Remove it from the tab bar UI. */
    fun onTabClosed(tab: TabState)

    /** A tab's metadata changed (title, conversation ID, etc.). Update tab bar label. */
    fun onTabUpdated(tab: TabState)
}