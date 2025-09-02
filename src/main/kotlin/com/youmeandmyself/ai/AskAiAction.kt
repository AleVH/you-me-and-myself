// File: src/main/kotlin/com/example/ai/AskAiAction.kt
package com.youmeandmyself.ai

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.youmeandmyself.ai.backend.AiBackendFactory
import com.youmeandmyself.ai.model.AiRequest
import javax.swing.SwingUtilities
import com.youmeandmyself.ai.settings.AiSettings

/**
 * Editor action that sends the current selection (or whole file) to the AI backend.
 * The backend is selected dynamically from plugin settings (e.g., Mock, OpenAI).
 */
class AskAiAction : AnAction("Ask AI") {

    override fun actionPerformed(e: AnActionEvent) {
        val project: Project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return

        val selectionModel = editor.selectionModel
        val text = if (selectionModel.hasSelection()) {
            selectionModel.selectedText ?: ""
        } else {
            editor.document.text
        }

        // Launch the AI call in a background thread to avoid freezing the UI
        Thread {
            val backend = AiBackendFactory.create() // Use selected backend from settings
            val response = backend.send(AiRequest(text))

            // Show the response in a popup on the UI thread
            SwingUtilities.invokeLater {
                val backendName = AiSettings.getInstance().state.backend.replaceFirstChar { it.uppercase() }
                Messages.showInfoMessage(project, response.content, "$backendName says:")
            }
        }.start()
    }
}
