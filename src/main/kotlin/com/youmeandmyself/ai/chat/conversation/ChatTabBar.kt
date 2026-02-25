// File: src/main/kotlin/com/youmeandmyself/ai/chat/conversation/ChatTabBar.kt
package com.youmeandmyself.ai.chat.conversation

import com.intellij.util.ui.JBUI
import com.youmeandmyself.dev.Dev
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*

/**
 * The tab bar UI component that sits above the JCEF chat browser.
 *
 * Renders conversation tabs as clickable pills with close buttons,
 * plus a [+] button for new tabs. Styled to match IntelliJ Darcula.
 *
 * ## Layout
 *
 * ```
 * ┌──────────────────────────────────────────────────────────────┐
 * │ [💬 Refactoring...  ×] [💬 API design  ×] [＋]              │
 * └──────────────────────────────────────────────────────────────┘
 * ```
 *
 * ## Interaction
 *
 * - Click tab → switch to that conversation
 * - Click × on tab → close that tab
 * - Click [+] → create new empty tab
 * - Middle-click tab → close that tab (IDE convention)
 *
 * This component does NOT manage state — it delegates all actions to
 * [ChatTabBarListener]. The ChatPanel (or ChatTabManager) handles the logic.
 */
class ChatTabBar : JPanel() {

    private val log = Dev.logger(ChatTabBar::class.java)
    private var listener: ChatTabBarListener? = null

    private val tabsPanel = JPanel(FlowLayout(FlowLayout.LEFT, 2, 0))
    private val newTabButton: JButton

    // Currently active tab ID for highlighting
    private var activeTabId: String? = null

    // ── Colors (Darcula-compatible) ─────────────────────────────────────

    private val bgDefault = Color(0x2B, 0x2B, 0x2B)
    private val bgTab = Color(0x3C, 0x3C, 0x3C)
    private val bgTabActive = Color(0x4E, 0x5A, 0x65)
    private val bgTabHover = Color(0x45, 0x45, 0x45)
    private val fgTab = Color(0x96, 0x96, 0x96)
    private val fgTabActive = Color(0xCC, 0xCC, 0xCC)
    private val fgClose = Color(0x6A, 0x6A, 0x6A)
    private val fgCloseHover = Color(0xCC, 0xCC, 0xCC)
    private val borderColor = Color(0x3C, 0x3C, 0x3C)

    init {
        layout = BorderLayout()
        background = bgDefault
        border = BorderFactory.createMatteBorder(0, 0, 1, 0, borderColor)

        tabsPanel.background = bgDefault
        tabsPanel.border = JBUI.Borders.empty(4, 6, 4, 0)

        newTabButton = JButton("+").apply {
            font = Font("SansSerif", Font.PLAIN, 14)
            foreground = fgTab
            background = bgDefault
            isBorderPainted = false
            isFocusPainted = false
            isContentAreaFilled = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            preferredSize = Dimension(28, 24)
            toolTipText = "New conversation"

            addActionListener {
                listener?.onNewTabRequested()
            }

            addMouseListener(object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent) { foreground = fgTabActive }
                override fun mouseExited(e: MouseEvent) { foreground = fgTab }
            })
        }

        val rightPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            background = bgDefault
            border = JBUI.Borders.empty(4, 0, 4, 6)
            add(newTabButton)
        }

        add(tabsPanel, BorderLayout.CENTER)
        add(rightPanel, BorderLayout.EAST)
    }

    // ── Public API ──────────────────────────────────────────────────────

    fun setTabBarListener(listener: ChatTabBarListener) {
        this.listener = listener
    }

    /**
     * Rebuild the entire tab bar from the given tab states.
     * Called on init and whenever tabs change.
     */
    fun updateTabs(tabs: List<TabState>, activeId: String?) {
        activeTabId = activeId
        tabsPanel.removeAll()

        for (tab in tabs) {
            tabsPanel.add(createTabComponent(tab))
        }

        tabsPanel.revalidate()
        tabsPanel.repaint()
    }

    /**
     * Update just the title of a single tab (avoid full rebuild).
     */
    fun updateTabTitle(tabId: String, title: String) {
        for (comp in tabsPanel.components) {
            if (comp is JPanel && comp.getClientProperty("tabId") == tabId) {
                val label = findLabelIn(comp)
                if (label != null) {
                    label.text = truncateTitle(title)
                    label.toolTipText = title
                }
                break
            }
        }
    }

    /**
     * Update which tab is visually active.
     */
    fun setActiveTab(tabId: String) {
        activeTabId = tabId

        for (comp in tabsPanel.components) {
            if (comp is JPanel) {
                val isActive = comp.getClientProperty("tabId") == tabId
                comp.background = if (isActive) bgTabActive else bgTab
                val label = findLabelIn(comp)
                label?.foreground = if (isActive) fgTabActive else fgTab
            }
        }

        tabsPanel.repaint()
    }

    // ── Tab component factory ───────────────────────────────────────────

    private fun createTabComponent(tab: TabState): JPanel {
        val isActive = tab.id == activeTabId

        val panel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
            putClientProperty("tabId", tab.id)
            background = if (isActive) bgTabActive else bgTab
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(borderColor, 1),
                JBUI.Borders.empty(1, 6, 1, 2)
            )
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

            // Round corners via custom painting would be nice but
            // FlowLayout panels don't clip — keep it simple with square borders
        }

        // Chat icon
        val icon = JLabel("💬").apply {
            font = Font("SansSerif", Font.PLAIN, 11)
        }

        // Title
        val titleLabel = JLabel(truncateTitle(tab.title)).apply {
            font = Font("SansSerif", Font.PLAIN, 12)
            foreground = if (isActive) fgTabActive else fgTab
            toolTipText = tab.title
        }

        // Close button
        val closeButton = JLabel("×").apply {
            font = Font("SansSerif", Font.PLAIN, 14)
            foreground = fgClose
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            border = JBUI.Borders.empty(0, 4, 0, 2)

            addMouseListener(object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent) { foreground = fgCloseHover }
                override fun mouseExited(e: MouseEvent) { foreground = fgClose }
                override fun mouseClicked(e: MouseEvent) {
                    e.consume()
                    listener?.onTabCloseRequested(tab.id)
                }
            })
        }

        panel.add(icon)
        panel.add(titleLabel)
        panel.add(closeButton)

        // Click to switch
        panel.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                // Ignore if clicking the close button
                if (e.component != panel) return
                // Middle-click = close (IDE convention)
                if (SwingUtilities.isMiddleMouseButton(e)) {
                    listener?.onTabCloseRequested(tab.id)
                } else {
                    listener?.onTabSwitchRequested(tab.id)
                }
            }

            override fun mouseEntered(e: MouseEvent) {
                if (tab.id != activeTabId) {
                    panel.background = bgTabHover
                }
            }

            override fun mouseExited(e: MouseEvent) {
                panel.background = if (tab.id == activeTabId) bgTabActive else bgTab
            }
        })

        return panel
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private fun truncateTitle(title: String, maxLength: Int = 25): String {
        return if (title.length <= maxLength) title else title.take(maxLength - 1) + "…"
    }

    private fun findLabelIn(panel: JPanel): JLabel? {
        return panel.components
            .filterIsInstance<JLabel>()
            .firstOrNull { it.text != "💬" && it.text != "×" }
    }
}

/**
 * Callback interface for tab bar interactions.
 *
 * ChatPanel implements this to handle tab actions and delegate to ChatTabManager.
 */
interface ChatTabBarListener {
    /** User clicked [+] — create a new empty tab */
    fun onNewTabRequested()

    /** User clicked a tab — switch to it */
    fun onTabSwitchRequested(tabId: String)

    /** User clicked × or middle-clicked a tab — close it */
    fun onTabCloseRequested(tabId: String)
}