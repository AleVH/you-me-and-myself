// File: src/main/kotlin/com/example/ai/AiToolWindowFactory.kt
package com.youmeandmyself.ai

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.BoxLayout

/**
 * Creates a docked "AI Chat" tool window with a basic UI.
 * Replace this stub with a proper chat component that supports streaming + markdown.
 */
class AiToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(JTextArea("👋 Hello! Ask me something or use the editor action."))
        }
        val content = ContentFactory.getInstance().createContent(panel, "Chat", false)
        toolWindow.contentManager.addContent(content)
    }
}