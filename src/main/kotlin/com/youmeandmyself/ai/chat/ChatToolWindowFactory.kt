package com.youmeandmyself.ai.chat

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.youmeandmyself.ai.library.LibraryPanel
import com.youmeandmyself.ai.library.LibraryPanelHolder
import com.youmeandmyself.dev.Dev
import java.awt.BorderLayout
import javax.swing.*

/**
 * Creates the tool window tabs for the YMM plugin.
 *
 * ## Chat Tab
 *
 * Uses ReactChatPanel — a Vite-built React app rendered in JCEF.
 * The legacy vanilla HTML/JS chat was removed in R6.
 *
 * ## Library Tab
 *
 * Uses LibraryPanel (legacy HTML/JS, will migrate to React in R5).
 */
class ChatToolWindowFactory : ToolWindowFactory, DumbAware {
    private val log = Dev.logger(ChatToolWindowFactory::class.java)

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        Dev.info(log, "toolwindow.create", "start" to true)

        // ── Chat tab ─────────────────────────────────────────────────
        createReactChatTab(project, toolWindow)

        // ── Library tab ──────────────────────────────────────────────
        try {
            val libraryPanel = LibraryPanel(project, toolWindow.disposable)
            val libraryContent = toolWindow.contentManager.factory.createContent(libraryPanel, "Library", false)
            libraryContent.isCloseable = false
            toolWindow.contentManager.addContent(libraryContent)
            LibraryPanelHolder.set(project, libraryPanel)
            Dev.info(log, "toolwindow.library", "added" to true)
        } catch (e: Throwable) {
            Dev.error(log, "toolwindow.library_failed", e)
        }
    }

    /**
     * Create the React-based chat tab.
     *
     * ReactChatPanel handles its own JCEF lifecycle, bridge wiring, and
     * HTML loading. No SwingWorker needed — React manages async internally.
     * The panel's root JPanel is added directly to the tool window.
     */
    private fun createReactChatTab(project: Project, toolWindow: ToolWindow) {
        try {
            val reactPanel = ReactChatPanel(project)
            val chatContent = toolWindow.contentManager.factory.createContent(reactPanel.root, "Chat", false)
            chatContent.isCloseable = false
            chatContent.setDisposer(reactPanel)
            toolWindow.contentManager.addContent(chatContent)
            Dev.info(log, "toolwindow.react_chat", "added" to true)
        } catch (e: Throwable) {
            Dev.error(log, "toolwindow.react_chat_failed", e)
            val errorPanel = JPanel(BorderLayout()).apply {
                add(JLabel("React chat failed to initialize: ${e.message}", SwingConstants.CENTER))
            }
            val chatContent = toolWindow.contentManager.factory.createContent(errorPanel, "Chat", false)
            chatContent.isCloseable = false
            toolWindow.contentManager.addContent(chatContent)
        }
    }
}