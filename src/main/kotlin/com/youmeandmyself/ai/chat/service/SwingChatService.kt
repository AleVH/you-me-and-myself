package com.youmeandmyself.ai.chat.service

import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Font
import javax.swing.*
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants
import javax.swing.text.StyledDocument

class SwingChatService(private val project: Project) : ChatUIService {
    private val chatState = ChatState()
    private val root = JPanel(BorderLayout())
    private val transcript = JTextPane().apply {
        isEditable = false
        margin = JBUI.insets(8)
    }
    private val doc: StyledDocument = transcript.styledDocument

    override val messages: StateFlow<List<ChatMessage>> = chatState.messages
    override val isTyping: StateFlow<Boolean> = chatState.isTyping

//    init {
//        val scrollPane = JBScrollPane(transcript)
//        root.add(scrollPane, BorderLayout.CENTER)
//    }

    override fun getComponent(): JComponent = transcript

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

    override fun setTyping(typing: Boolean) {
        chatState.setTyping(typing)
        if (typing) {
            appendBlock("Assistant is typing...", italic = true)
        }
    }

    override fun clearChat() {
        chatState.clear()
        SwingUtilities.invokeLater {
            doc.remove(0, doc.length)
        }
    }

    override fun dispose() {
        // No special disposal needed for Swing components
    }

    // Your existing rendering methods
    private fun appendUser(text: String) {
        appendBlock("You:", bold = true)
        appendBlock(text)
        appendSpacer()
    }

    private fun appendAssistant(text: String, isError: Boolean = false) {
        appendBlock("Assistant:", bold = true)
        val parts = splitByTripleBackticks(text)
        parts.forEachIndexed { i, part ->
            if (i % 2 == 1) appendCodeBlock(part) else appendBlock(part, isError = isError)
        }
        appendSpacer()
    }

    private fun splitByTripleBackticks(s: String): List<String> = s.split("```")

    private fun appendBlock(text: String, bold: Boolean = false, isError: Boolean = false, italic: Boolean = false) {
        SwingUtilities.invokeLater {
            val attrs = SimpleAttributeSet()
            StyleConstants.setFontFamily(attrs, transcript.font.family)
            StyleConstants.setBold(attrs, bold)
            StyleConstants.setItalic(attrs, italic)
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
}