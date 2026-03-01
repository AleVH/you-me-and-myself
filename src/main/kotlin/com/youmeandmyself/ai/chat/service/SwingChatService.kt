package com.youmeandmyself.ai.chat.service

import com.intellij.openapi.project.Project
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.flow.StateFlow
import java.awt.Color
import java.awt.Font
import javax.swing.*
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants
import javax.swing.text.StyledDocument

/**
 * Plain Swing fallback implementation of [ChatUIService].
 *
 * ## When Is This Used?
 *
 * This service activates when JCEF (Java Chromium Embedded Framework) fails
 * to initialize — which can happen on older JDK versions, headless environments,
 * or when the JCEF runtime is missing. ChatPanel tries BrowserChatService first;
 * if it throws, it falls back to this.
 *
 * ## Capabilities vs BrowserChatService
 *
 * | Feature                  | Browser | Swing (this) |
 * |--------------------------|---------|--------------|
 * | Markdown rendering       | ✅      | ❌ (plain text) |
 * | Syntax highlighting      | ✅      | ❌ (monospace only) |
 * | Thinking indicator       | ✅      | ❌ (no-op) |
 * | Metrics display          | ✅      | ❌ (no-op) |
 * | Code block detection     | ✅      | ✅ (basic triple-backtick splitting) |
 *
 * ## Lifecycle
 *
 * This entire class will be deleted when React replaces the frontend.
 * It exists only to ensure the plugin remains functional on platforms
 * where JCEF isn't available.
 *
 * @param project The IntelliJ project context (unused currently, kept for interface consistency)
 */
class SwingChatService(private val project: Project) : ChatUIService {

    /** Shared chat state — holds the message list and typing indicator as observable flows. */
    private val chatState = ChatState()

    /**
     * The text pane that renders all chat messages.
     *
     * Uses a [StyledDocument] for basic formatting (bold for role labels,
     * monospace for code blocks, red for errors). Not editable — this is
     * a display-only transcript.
     */
    private val transcript = JTextPane().apply {
        isEditable = false
        margin = JBUI.insets(8)
    }

    /** Styled document handle — used by all append* methods to insert formatted text. */
    private val doc: StyledDocument = transcript.styledDocument

    // ── State Access (from ChatUIService interface) ──────────────────────

    /** Observable message list. Collectors see new messages as they're added. */
    override val messages: StateFlow<List<ChatMessage>> = chatState.messages

    /** Observable typing state. True while waiting for an AI response. */
    override val isTyping: StateFlow<Boolean> = chatState.isTyping

    // ── UI Component ─────────────────────────────────────────────────────

    /**
     * Returns the transcript pane for embedding in the tool window layout.
     *
     * Note: Unlike BrowserChatService which returns a full JCEF panel,
     * this returns a bare JTextPane. The caller (ChatPanel) should wrap
     * it in a JScrollPane if needed.
     */
    override fun getComponent(): JComponent = transcript

    // ── Message Operations ───────────────────────────────────────────────

    /**
     * Display a user message in the chat transcript.
     *
     * Adds the message to the shared state (for any observers) AND
     * renders it in the Swing text pane with "You:" label in bold.
     */
    override fun sendUserMessage(content: String, contextAttached: Boolean) {
        val message = UserMessage(
            id = ChatState.generateId(),
            timestamp = kotlinx.datetime.Clock.System.now(),
            content = content,
            contextAttached = contextAttached
        )
        chatState.addMessage(message)
        appendUser(content)
    }

    /**
     * Display an AI assistant response in the chat transcript.
     *
     * Adds the message to the shared state AND renders it with basic
     * formatting: "Assistant:" label in bold, code blocks in monospace,
     * errors in red.
     */
    override fun addAssistantMessage(content: String, providerId: String?, isError: Boolean) {
        val message = AssistantMessage(
            id = ChatState.generateId(),
            timestamp = kotlinx.datetime.Clock.System.now(),
            content = content,
            providerId = providerId,
            isError = isError
        )
        chatState.addMessage(message)
        appendAssistant(content, isError)
    }

    /**
     * Display a system notification in the chat transcript.
     *
     * Rendered with a "[System]" prefix to distinguish from AI responses.
     * Used for context status, correction hints, plugin-level errors, etc.
     */
    override fun addSystemMessage(content: String, type: SystemMessageType) {
        val message = SystemMessage(
            id = ChatState.generateId(),
            timestamp = kotlinx.datetime.Clock.System.now(),
            content = content,
            type = type
        )
        chatState.addMessage(message)
        appendBlock("[System] $content")
    }

    /**
     * Update the typing indicator state.
     *
     * In the Swing fallback, this appends a text line ("Assistant is typing...")
     * rather than showing an animated indicator. Not ideal, but functional.
     */
    override fun setTyping(typing: Boolean) {
        chatState.setTyping(typing)
        if (typing) {
            appendBlock("Assistant is typing...", italic = true)
        }
    }

    /**
     * Clear all messages from the transcript.
     *
     * Resets both the shared state (for observers) and the Swing document
     * (for visual display). The document clear runs on EDT to avoid
     * Swing threading violations.
     */
    override fun clearChat() {
        chatState.clear()
        SwingUtilities.invokeLater {
            doc.remove(0, doc.length)
        }
    }

    // ── No-ops: Features not supported in Swing fallback ────────────────
    //
    // These satisfy the ChatUIService interface but do nothing.
    // The Swing fallback doesn't have an animated thinking indicator
    // or a metrics widget. This entire class gets deleted when React
    // replaces the frontend.

    /** No animated thinking indicator in Swing fallback. */
    override fun showThinking() {}

    /** No animated thinking indicator in Swing fallback. */
    override fun hideThinking() {}

    /** No metrics widget in Swing fallback. */
    override fun updateMetrics(
        model: String?,
        promptTokens: Int?,
        completionTokens: Int?,
        totalTokens: Int?,
        estimatedCost: String?
    ) {}

    // ── Lifecycle ────────────────────────────────────────────────────────

    /**
     * Release resources. No special cleanup needed for Swing components —
     * they're garbage collected with the panel.
     */
    override fun dispose() {}

    // ── Swing Rendering Helpers ──────────────────────────────────────────
    //
    // These methods insert formatted text into the StyledDocument.
    // All run on EDT via SwingUtilities.invokeLater to avoid threading issues.

    /**
     * Render a user message: bold "You:" label followed by the message text.
     */
    private fun appendUser(text: String) {
        appendBlock("You:", bold = true)
        appendBlock(text)
        appendSpacer()
    }

    /**
     * Render an assistant message with basic code block detection.
     *
     * Splits the response on triple backticks. Odd-indexed parts
     * are treated as code blocks (monospace font, gray background).
     * Even-indexed parts are regular text.
     *
     * This is a rough approximation of markdown code fences — it doesn't
     * handle language hints, nested fences, or other markdown features.
     *
     * @param text The assistant's response text
     * @param isError If true, render in red (error color)
     */
    private fun appendAssistant(text: String, isError: Boolean = false) {
        appendBlock("Assistant:", bold = true)
        val parts = text.split("```")
        parts.forEachIndexed { i, part ->
            if (i % 2 == 1) appendCodeBlock(part) else appendBlock(part, isError = isError)
        }
        appendSpacer()
    }

    /**
     * Insert a styled text block into the document.
     *
     * All text insertion goes through this method to ensure consistent
     * EDT dispatching and style application.
     *
     * @param text The text to insert
     * @param bold If true, render in bold (used for role labels)
     * @param isError If true, render in red
     * @param italic If true, render in italic (used for typing indicator)
     */
    private fun appendBlock(
        text: String,
        bold: Boolean = false,
        isError: Boolean = false,
        italic: Boolean = false
    ) {
        SwingUtilities.invokeLater {
            val attrs = SimpleAttributeSet()
            StyleConstants.setFontFamily(attrs, transcript.font.family)
            StyleConstants.setBold(attrs, bold)
            StyleConstants.setItalic(attrs, italic)
            if (isError) StyleConstants.setForeground(attrs, Color(0xB00020))
            doc.insertString(doc.length, text + "\n", attrs)
        }
    }

    /**
     * Insert a code block with monospace font and light gray background.
     *
     * Used for text between triple-backtick fences in assistant responses.
     * Trims leading/trailing whitespace from the code content.
     */
    private fun appendCodeBlock(code: String) {
        SwingUtilities.invokeLater {
            val attrs = SimpleAttributeSet()
            StyleConstants.setFontFamily(attrs, Font.MONOSPACED)
            StyleConstants.setBackground(attrs, Color(0xF5F5F5))
            doc.insertString(doc.length, code.trim() + "\n", attrs)
        }
    }

    /** Insert a blank line as visual spacing between messages. */
    private fun appendSpacer() = appendBlock("")
}