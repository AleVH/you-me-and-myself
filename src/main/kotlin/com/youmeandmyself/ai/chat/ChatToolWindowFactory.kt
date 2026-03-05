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
 * Two implementations exist during the React migration:
 * - **Legacy (default):** ChatPanel with vanilla HTML/JS in JCEF
 * - **React (opt-in):** ReactChatPanel with Vite-built React in JCEF
 *
 * Toggle via system property: `-Dymm.reactChat=true`
 * This is a dev flag — it will be removed once React reaches feature parity (R6).
 *
 * ## Library Tab
 *
 * Always uses LibraryPanel (not affected by the React migration).
 */
class ChatToolWindowFactory : ToolWindowFactory, DumbAware {
    private val log = Dev.logger(ChatToolWindowFactory::class.java)

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        Dev.info(log, "toolwindow.create", "start" to true)

        // ── Chat tab ─────────────────────────────────────────────────
        // Check the dev flag to decide which chat implementation to use.
        // Legacy path is the default until React reaches full parity.
        val useReact = System.getProperty("ymm.reactChat", "false").toBoolean()
        Dev.info(log, "toolwindow.chat_mode", "react" to useReact)

        if (useReact) {
            createReactChatTab(project, toolWindow)
        } else {
            createLegacyChatTab(project, toolWindow)
        }

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

    // ── React Chat (new) ─────────────────────────────────────────────

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
            // Fall back to an error message rather than crashing the tool window
            val errorPanel = JPanel(BorderLayout()).apply {
                add(JLabel("React chat failed to initialize: ${e.message}", SwingConstants.CENTER))
            }
            val chatContent = toolWindow.contentManager.factory.createContent(errorPanel, "Chat", false)
            chatContent.isCloseable = false
            toolWindow.contentManager.addContent(chatContent)
        }
    }

    // ── Legacy Chat (existing) ───────────────────────────────────────

    /**
     * Create the legacy vanilla HTML/JS chat tab.
     *
     * Uses SwingWorker for async initialization because ChatPanel's JCEF
     * browser setup can block. Shows a loading indicator until ready.
     * This is the original implementation — untouched by the React migration.
     */
    private fun createLegacyChatTab(project: Project, toolWindow: ToolWindow) {
        val loadingPanel = createLoadingPanel()
        val chatContent = toolWindow.contentManager.factory.createContent(loadingPanel, "Chat", false)
        chatContent.isCloseable = false
        toolWindow.contentManager.addContent(chatContent)

        val worker = object : SwingWorker<ChatPanel, Void>() {
            override fun doInBackground(): ChatPanel {
                Dev.info(log, "toolwindow.worker", "background_start" to true)
                return ChatPanel(project) { isReady ->
                    Dev.info(log, "toolwindow.callback", "isReady" to isReady, "thread" to Thread.currentThread().name)
                    SwingUtilities.invokeLater {
                        Dev.info(log, "toolwindow.swing", "swing_invoke" to true)
                        if (isReady) {
                            Dev.info(log, "toolwindow.ready", "swapping_panels" to true)
                            val chatPanel = get()
                            loadingPanel.removeAll()
                            loadingPanel.add(chatPanel.root, BorderLayout.CENTER)
                            loadingPanel.revalidate()
                            loadingPanel.repaint()
                            Dev.info(log, "toolwindow.ready", "panels_swapped" to true)
                        } else {
                            Dev.info(log, "toolwindow.failed", "fallback_mode" to true)
                            loadingPanel.removeAll()
                            loadingPanel.add(JLabel("Chat initialization failed - using basic mode", SwingConstants.CENTER))
                            loadingPanel.revalidate()
                            loadingPanel.repaint()
                        }
                    }
                }
            }

            override fun done() {
                Dev.info(log, "toolwindow.worker", "done" to true)
            }
        }
        worker.execute()
        Dev.info(log, "toolwindow.create", "worker_executed" to true)
    }

    private fun createLoadingPanel(): JPanel {
        return JPanel(BorderLayout()).apply {
            add(JLabel("Loading chat...", SwingConstants.CENTER))
        }
    }
}