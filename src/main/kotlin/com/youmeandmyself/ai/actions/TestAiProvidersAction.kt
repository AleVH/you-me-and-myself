// ==========================
// File: src/main/kotlin/com/youmeandmyself/ai/actions/TestAiProvidersAction.kt
// ==========================
// path: src/main/kotlin/com/youmeandmyself/ai/actions/TestAiProvidersAction.kt — Tools menu action to test providers
package com.youmeandmyself.ai.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.youmeandmyself.ai.providers.ProviderRegistry
import kotlinx.coroutines.*
import com.intellij.openapi.application.ApplicationManager

/**
 * Tools → AI → Test AI Providers
 * Runs a quick ping against each active provider and shows a summary dialog.
 */
class TestAiProvidersAction : AnAction() {
    private val log = Logger.getInstance(TestAiProvidersAction::class.java)

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project
        if (project == null) {
            Messages.showErrorDialog("No open project.", "AI Providers Test")
            return
        }
        runTest(project)
    }

    private fun runTest(project: Project) {
        val providers = ProviderRegistry.allProviders(project)

        // Run work off the EDT to avoid blocking the UI thread.
        ApplicationManager.getApplication().executeOnPooledThread {
            val results = runBlocking {
                providers.map { p -> async(Dispatchers.IO) { p.displayName to pingSafe(p) } }.awaitAll()
            }

            val message = buildString {
                if (results.isEmpty()) {
                    append("No providers configured. Add keys in Settings → YouMeAndMyself Assistant.")
                } else {
                    results.forEach { (name, res) -> append("\n• ").append(name).append(": ").append(res) }
                }
            }

            // Back to EDT to show the dialog.
            ApplicationManager.getApplication().invokeLater {
                Messages.showInfoMessage(project, message.trim(), "AI Providers Test")
            }
        }
    }

    // Wraps each provider ping; returns "ok" or a detailed failure reason.
    private suspend fun pingSafe(p: com.youmeandmyself.ai.providers.AiProvider): String = try {
        p.ping() // expected to return "ok" or similar on success
    } catch (t: Throwable) {
        val msg = t.message ?: t::class.simpleName ?: "error"
        log.warn("Ping failed for ${p.id}", t)     // <-- fixed interpolation
        "FAILED ($msg)"                             // <-- fixed interpolation
    }
}
