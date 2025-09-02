// File: src/main/kotlin/com/youmeandmyself/ai/chat/ChatPanel.kt
// path: src/main/kotlin/com/youmeandmyself/ai/chat/ChatPanel.kt — The chat UI panel
package com.youmeandmyself.ai.chat

import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.youmeandmyself.ai.providers.ProviderRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Font
import javax.swing.*
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants
import javax.swing.text.StyledDocument
import com.youmeandmyself.ai.settings.PluginSettingsState
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.vfs.VirtualFile
// Context Orchestrator imports
import com.youmeandmyself.context.orchestrator.ContextOrchestrator
import com.youmeandmyself.context.orchestrator.ContextRequest
import com.youmeandmyself.context.orchestrator.DetectorRegistry
import com.youmeandmyself.context.orchestrator.ContextBundle
import com.youmeandmyself.context.orchestrator.detectors.FrameworkDetector
import com.youmeandmyself.context.orchestrator.detectors.LanguageDetector
import com.youmeandmyself.context.orchestrator.detectors.ProjectStructureDetector
import com.youmeandmyself.context.orchestrator.detectors.RelevantFilesDetector
import com.intellij.openapi.diagnostic.Logger

/**
 * A minimal chat UI:
 * - Top transcript (StyledDocument) with monospaced code blocks.
 * - Bottom input (single-line for now) + Send button (Ctrl/Cmd+Enter later).
 * - On send: resolve active OpenAI provider and call chat(prompt).
 *   (Later: add provider drop-down to switch between OpenAI/Gemini, etc.)
 */
class ChatPanel(private val project: com.intellij.openapi.project.Project) {
    val component: JComponent get() = root
    private val root = JPanel(BorderLayout())

    private val transcript = JTextPane().apply {
        isEditable = false
        margin = JBUI.insets(8)
    }
    private val doc: StyledDocument = transcript.styledDocument

    private val input = JBTextField().apply {
        emptyText.text = "Type a prompt…"
    }

    private val send = JButton("Send")

    // Explicit provider selector: "mock", "openai", "gemini", "deepseek"
    private val providerSelector = JComboBox<String>()

    // Purpose: opt-in attach of the active editor file contents
    private val attachCurrentFile = JCheckBox("Attach current file")

    private val scope = CoroutineScope(Dispatchers.IO)

    init {
        val top = JBScrollPane(transcript)
        val bottom = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(8)

            // LEFT: provider dropdown
            val left = JPanel(BorderLayout()).apply {
                add(providerSelector, BorderLayout.CENTER)
            }
            add(left, BorderLayout.WEST)

            // CENTER: prompt input
            add(input, BorderLayout.CENTER)

            // RIGHT: send button
            val right = JPanel(BorderLayout()).apply {
                add(attachCurrentFile, BorderLayout.NORTH)
                add(send, BorderLayout.SOUTH)
            }
            add(right, BorderLayout.EAST)

        }

        root.add(top, BorderLayout.CENTER)
        root.add(bottom, BorderLayout.SOUTH)

        send.addActionListener { doSend() }
        input.addActionListener { doSend() } // Enter to send
        initProviderSelector()

    }

    private fun doSend() {
        val prompt = input.text?.trim().orEmpty()
        if (prompt.isBlank()) return
        input.text = ""
        appendUser(prompt)
        // Build finalPrompt with optional attached file content
        var attachedNote: String? = null
        val finalPrompt = if (attachCurrentFile.isSelected) {
            val editor = FileEditorManager.getInstance(project).selectedTextEditor
            val vf: VirtualFile? = editor?.virtualFile
            val path = vf?.path
            val content = editor?.document?.text

            if (!path.isNullOrBlank() && !content.isNullOrBlank()) {
                attachedNote = "[Attached: $path (${content.length} chars)]"
                """
        You are given the current file from the project for context.
        FILE: $path
        CONTENT:
        ```
        $content
        ```

        User question:
        $prompt
        """.trimIndent()
            } else {
                prompt
            }
        } else {
            prompt
        }

        // If we attached something, show the one-line note in the transcript
        if (attachedNote != null) {
            appendBlock(attachedNote)
        }

        scope.launch {
            val provider = ProviderRegistry.selectedProvider(project)
            if (provider == null) {
                appendAssistant(
                    "[No provider selected/configured. Pick one in the dropdown or set keys in Settings.]",
                    isError = true
                )
                return@launch
            }

            try {
                // 1) Build the detector registry (baseline four)
                val registry = DetectorRegistry(
                    listOf(
                        LanguageDetector(),
                        FrameworkDetector(),
                        ProjectStructureDetector(),
                        RelevantFilesDetector()
                    )
                )

                // 2) Run the orchestrator with a tight budget (tune later)
                val orchestrator = ContextOrchestrator(registry, Logger.getInstance(ChatPanel::class.java))
                val request = ContextRequest(
                    project = project,
                    maxMillis = 1400L // keep the UI snappy; raise if needed
                )
                val (bundle, metrics) = orchestrator.gather(request, scope)

                // 3) (Optional) surface a one-liner in transcript for visibility
                appendBlock("[Context ready in ${metrics.totalMillis} ms]")

                // 4) Prepend a compact context note to your existing finalPrompt
                val contextNote = formatContextNote(bundle)
                val effectivePrompt = "$contextNote\n\n$finalPrompt"

                // 5) Ask the provider with the augmented prompt
                val reply = provider.chat(effectivePrompt)
                appendAssistant(reply)
            } catch (t: Throwable) {
                appendAssistant("Error: ${t.message}", isError = true)
            }

        }
    }

    private fun appendUser(text: String) {
        appendBlock("You:", bold = true)
        appendBlock(text)
        appendSpacer()
    }

    private fun appendAssistant(text: String, isError: Boolean = false) {
        appendBlock("Assistant:", bold = true)
        // naive code block handling: render ```...``` as monospaced block
        val parts = splitByTripleBackticks(text)
        parts.forEachIndexed { i, part ->
            if (i % 2 == 1) appendCodeBlock(part) else appendBlock(part, isError = isError)
        }
        appendSpacer()
    }

    private fun splitByTripleBackticks(s: String): List<String> {
        return s.split("```")
    }

    private fun appendBlock(text: String, bold: Boolean = false, isError: Boolean = false) {
        SwingUtilities.invokeLater {
            val attrs = SimpleAttributeSet()
            StyleConstants.setFontFamily(attrs, transcript.font.family)
            StyleConstants.setBold(attrs, bold)
            if (isError) StyleConstants.setForeground(attrs, Color(0xB00020))
            doc.insertString(doc.length, text + "\n", attrs)
        }
    }

    private fun appendCodeBlock(code: String) {
        SwingUtilities.invokeLater {
            val attrs = SimpleAttributeSet()
            StyleConstants.setFontFamily(attrs, Font.MONOSPACED)
            StyleConstants.setBackground(attrs, Color(0xF5F5F5))
            doc.insertString(doc.length, code.trim() + "\n", attrs)
        }
    }

    private fun appendSpacer() = appendBlock("")

    // Populate the dropdown and keep selection in settings
    private fun initProviderSelector() {
        val s = PluginSettingsState.getInstance(project)
        val options = mutableListOf<String>()

        // Always include "mock"
        options += "mock"

        // Add only providers that have keys configured
        if (!s.openAiApiKey.isNullOrBlank()) options += "openai"
        if (!s.geminiApiKey.isNullOrBlank()) options += "gemini"
        if (!s.deepSeekApiKey.isNullOrBlank()) options += "deepseek"

        providerSelector.model = DefaultComboBoxModel(options.toTypedArray())
        providerSelector.selectedItem =
            (s.selectedProvider?.lowercase()).takeIf { it in options } ?: options.first()

        providerSelector.addActionListener {
            s.selectedProvider = providerSelector.selectedItem as String
        }
    }

    /**
     * Formats a compact context note from a ContextBundle for the model.
     * Keep it short to avoid prompt bloat; we only surface the most useful bits.
     */
    private fun formatContextNote(bundle: ContextBundle): String {
        val lang = bundle.language?.languageId ?: "unknown"
        val frameworks = bundle.frameworks.joinToString(", ") { f ->
            if (f.version.isNullOrBlank()) f.name else "${f.name} ${f.version}"
        }.ifBlank { "none" }
        val build = bundle.projectStructure?.buildSystem ?: "unknown"
        val modules = bundle.projectStructure?.modules?.joinToString(", ")?.ifBlank { null }
        val files = bundle.relevantFiles?.let { it.filePaths.take(5) }?.joinToString("\n- ")?.let {
            if (it.isNotBlank()) "\nTop files:\n- $it" else ""
        } ?: ""

        val modulesLine = modules?.let { "\nModules: $it" } ?: ""
        return """
        [Context]
        Language: $lang
        Frameworks: $frameworks
        Build: $build$modulesLine$files
        """.trim()
    }

}
