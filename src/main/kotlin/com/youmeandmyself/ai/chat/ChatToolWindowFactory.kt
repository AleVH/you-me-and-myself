// File: src/main/kotlin/com/youmeandmyself/ai/chat/ChatToolWindowFactory.kt
// path: src/main/kotlin/com/youmeandmyself/ai/chat/ChatToolWindowFactory.kt — Registers the tool window
//package com.youmeandmyself.ai.chat
//
//import com.intellij.openapi.project.DumbAware
//import com.intellij.openapi.project.Project
//import com.intellij.openapi.wm.ToolWindow
//import com.intellij.openapi.wm.ToolWindowFactory
//
//class ChatToolWindowFactory : ToolWindowFactory, DumbAware {
//    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
//        val loadingPanel = createLoadingPanel()
//        val content = toolWindow.contentManager.factory.createContent(loadingPanel, "", false)
//        toolWindow.contentManager.addContent(content)
//
//        // Initialize chat panel asynchronously
//        CoroutineScope(Dispatchers.IO).launch {
//            val chatPanel = ChatPanel(project) { onReady ->
//                if (onReady) {
//                    // Swap to real chat UI when ready
//                    withContext(Dispatchers.Main) {
//                        content.component = chatPanel.component
//                        content.revalidate()
//                        content.repaint()
//                    }
//                }
//            }
//        }
//    }
//
//    private fun createLoadingPanel(): JComponent {
//        return JPanel(BorderLayout()).apply {
//            add(JLabel("Loading chat...", SwingConstants.CENTER))
//        }
//    }
//}
//
////class ChatToolWindowFactory : ToolWindowFactory, DumbAware {
////    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
////        val panel = ChatPanel(project)
////        val content = toolWindow.contentManager.factory.createContent(panel.component, "", false)
////        toolWindow.contentManager.addContent(content)
////    }
////}

// File: src/main/kotlin/com/youmeandmyself/ai/chat/ChatToolWindowFactory.kt
package com.youmeandmyself.ai.chat

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.youmeandmyself.dev.Dev
import java.awt.BorderLayout
import javax.swing.*

class ChatToolWindowFactory : ToolWindowFactory, DumbAware {
    private val log = Dev.logger(ChatToolWindowFactory::class.java)

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        Dev.info(log, "toolwindow.create", "start" to true)

        val loadingPanel = createLoadingPanel()
        val content = toolWindow.contentManager.factory.createContent(loadingPanel, "", false)
        toolWindow.contentManager.addContent(content)

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
                // This will trigger the callback above
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