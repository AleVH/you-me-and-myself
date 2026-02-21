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

class ChatToolWindowFactory : ToolWindowFactory, DumbAware {
    private val log = Dev.logger(ChatToolWindowFactory::class.java)

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        Dev.info(log, "toolwindow.create", "start" to true)

        val loadingPanel = createLoadingPanel()
        val chatContent = toolWindow.contentManager.factory.createContent(loadingPanel, "Chat", false)
        chatContent.isCloseable = false
        toolWindow.contentManager.addContent(chatContent)

        // Initialize chat panel asynchronously using SwingWorker
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

        // Library tab
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

    private fun createLoadingPanel(): JPanel {
        return JPanel(BorderLayout()).apply {
            add(JLabel("Loading chat...", SwingConstants.CENTER))
        }
    }
}