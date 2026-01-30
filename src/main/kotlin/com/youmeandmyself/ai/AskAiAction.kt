// File: src/main/kotlin/com/example/ai/AskAiAction.kt
package com.youmeandmyself.ai

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.ui.Messages
import com.youmeandmyself.ai.providers.ProviderRegistry
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import javax.swing.SwingUtilities

/**
 * Editor action that sends the current selection (or whole file) to the AI backend.
 * The backend is selected dynamically from plugin settings (e.g., Mock, OpenAI).
 */
class AskAiAction : AnAction("Ask AI") {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return

        val selection = editor.selectionModel
        val prompt = if (selection.hasSelection()) {
            selection.selectedText ?: ""
        } else {
            editor.document.text
        }.trim()

        // Resolve the active CHAT provider using your existing registry
        val provider = ProviderRegistry.selectedChatProvider(project)
        if (provider == null) {
            SwingUtilities.invokeLater {
                Messages.showWarningDialog(
                    project,
                    "No chat provider is selected/configured.",
                    "YMM Assistant"
                )
            }
            return
        }

        // Call provider.chat (suspend) on a background coroutine
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val result = withTimeout(30_000) {
                    provider.chat(prompt)
                }
                withContext(Dispatchers.Main) {
                    val title = provider.displayName
                    // Use displayText from ParsedResponse (handles both success and error)
                    // Show as error dialog if isError, otherwise info dialog
                    if (result.isError) {
                        Messages.showErrorDialog(project, result.displayText, "$title - Error")
                    } else {
                        Messages.showInfoMessage(project, result.displayText, "$title says:")
                    }
                }
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) {
                    Messages.showErrorDialog(
                        project,
                        if (t is TimeoutCancellationException) "Request timed out" else t.message ?: "Unexpected error",
                        "YMM Assistant"
                    )
                }
            }
        }
    }
}