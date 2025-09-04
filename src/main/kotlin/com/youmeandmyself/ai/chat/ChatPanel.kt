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

    // Purpose: opt-in attach context
    private val attachContext = JCheckBox("Attach context").apply {
        // Swing/IntelliJ tooltips use this setter; reliable across IDE versions
        setToolTipText("Include IDE-derived context (language, frameworks, project structure, related files)")
    }

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
                add(attachContext, BorderLayout.NORTH)
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

    // File: src/main/kotlin/com/youmeandmyself/ai/chat/ChatPanel.kt — full replacement of doSend()

    // File: src/main/kotlin/com/youmeandmyself/ai/chat/ChatPanel.kt — full replacement of doSend()

    private fun doSend() {
        // 0) Read user input and render it on the transcript
        val userInput = input.text?.trim().orEmpty()
        if (userInput.isBlank()) return
        input.text = ""
        appendUser(userInput)

        // 1) Decide if we should run the orchestrator (smart + explicit toggle)
        val needContext = attachContext.isSelected || isContextLikelyUseful(userInput)

        // 2) Call provider
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
                val effectivePrompt: String

                if (needContext) {
                    // Run detectors only when beneficial or explicitly requested
                    val registry = DetectorRegistry(
                        listOf(
                            LanguageDetector(),
                            FrameworkDetector(),
                            ProjectStructureDetector(),
                            RelevantFilesDetector()
                        )
                    )
                    val orchestrator = ContextOrchestrator(registry, Logger.getInstance(ChatPanel::class.java))
                    val request = ContextRequest(project = project, maxMillis = 1400L)
                    val (bundle, metrics) = orchestrator.gather(request, scope)

                    appendBlock("[Context ready in ${metrics.totalMillis} ms]")

                    val contextNote = formatContextNote(bundle)
                    effectivePrompt = """
                    $contextNote

                    $userInput
                """.trimIndent()
                } else {
                    // No context → send plain
                    effectivePrompt = userInput
                }

                val reply = provider.chat(effectivePrompt)
                appendAssistant(reply)

            } catch (t: Throwable) {
                appendAssistant("Error: ${t.message}", isError = true)
            }
        }
    }

    // Heuristic: return true when the prompt likely benefits from project context.
    private fun isContextLikelyUseful(text: String): Boolean {
        val t = text.lowercase()

        // Obvious code markers or fenced blocks
        if ("```" in t) return true

        // Error/exception keywords
        val errorHints = listOf(
            "error", "exception", "traceback", "stack trace", "unresolved reference",
            "undefined", "cannot find symbol", "no such method", "classnotfound"
        )
        if (errorHints.any { it in t }) return true

        // File path / extension hints
        val extHints = listOf(
            ".kt", ".kts", ".java", ".js", ".ts", ".tsx", ".py", ".php", ".rb",
            ".go", ".rs", ".cpp", ".c", ".h", ".cs", ".xml", ".json", ".gradle",
            ".yml", ".yaml"
        )
        if (extHints.any { it in t }) return true
        if (Regex("""[\\/].+\.(\w{1,6})""").containsMatchIn(t)) return true

        // Code-y keywords
        val codeHints = listOf(
            "import ", "package ", "class ", "interface ", "fun ", "def ",
            "require(", "include(", "from ", "new ", "extends ", "implements "
        )
        if (codeHints.any { it in t }) return true

        // Length heuristic: short greetings rarely need context
        val words = t.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.size <= 3 && setOf("hi", "hello", "hey", "yo").any { it == t || t.startsWith(it) }) return false

        // Default: no context
        return false
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

        val filesLine = if (bundle.files.isNotEmpty()) {
            val preview = bundle.files.take(5).joinToString("\n- ") { it.path }
            val truncNote = if (bundle.truncatedCount > 0) " (truncated: ${bundle.truncatedCount})" else ""
            "\nFiles: ${bundle.files.size}$truncNote (~${bundle.totalChars} chars)\n- $preview"
        } else ""

        val modulesLine = modules?.let { "\nModules: $it" } ?: ""
        return """
        [Context]
        Language: $lang
        Frameworks: $frameworks
        Build: $build$modulesLine$filesLine
    """.trimIndent()
    }

}
