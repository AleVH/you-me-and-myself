// File: src/main/kotlin/com/youmeandmyself/ai/actions/OpenChatToolWindowAction.kt
// Purpose: Opens (and focuses) the "YMM Chat" tool window without needing <property> in plugin.xml.
package com.youmeandmyself.ai.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.wm.ToolWindowManager

class OpenChatToolWindowAction : AnAction("Open YMM Chat") {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val tw = ToolWindowManager.getInstance(project).getToolWindow("YMM Chat") ?: return
        if (!tw.isAvailable) tw.isAvailable = true
        tw.activate(null, true, true)
    }
}
