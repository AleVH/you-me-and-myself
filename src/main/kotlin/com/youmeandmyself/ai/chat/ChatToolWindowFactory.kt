// File: src/main/kotlin/com/youmeandmyself/ai/chat/ChatToolWindowFactory.kt
// path: src/main/kotlin/com/youmeandmyself/ai/chat/ChatToolWindowFactory.kt — Registers the tool window
package com.youmeandmyself.ai.chat

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory

class ChatToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = ChatPanel(project)
        val content = toolWindow.contentManager.factory.createContent(panel.component, "", false)
        toolWindow.contentManager.addContent(content)
    }
}
