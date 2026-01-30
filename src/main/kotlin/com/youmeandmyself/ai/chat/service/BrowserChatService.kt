package com.youmeandmyself.ai.chat.service

import com.intellij.openapi.project.Project
import com.youmeandmyself.ai.chat.components.ChatBrowserComponent
import com.youmeandmyself.dev.Dev
import kotlinx.coroutines.flow.StateFlow
import javax.swing.JComponent

/**
 * Browser-based implementation of ChatUIService.
 *
 * Uses JCEF (Java Chromium Embedded Framework) to render chat messages
 * with full markdown support, syntax highlighting, and modern styling.
 *
 * Falls back gracefully if browser initialization fails - the caller
 * (ChatPanel) handles switching to SwingChatService.
 */
class BrowserChatService(private val project: Project) : ChatUIService {
    private val chatState = ChatState()
    private val browserComponent = ChatBrowserComponent(project, chatState)
    private val log = Dev.logger(BrowserChatService::class.java)

    private var onReadyCallback: ((Boolean) -> Unit)? = null

    init {
        Dev.info(log, "browserservice.init", "start" to true)
        // Wire the readiness callback to the browser component
        browserComponent.setOnReady { isReady ->
            Dev.info(log, "browserservice.ready_callback", "received_from_browser" to true, "isReady" to isReady)
            onReadyCallback?.invoke(isReady)
        }
        Dev.info(log, "browserservice.init", "callback_wired" to true)
    }

    fun setOnReady(callback: (Boolean) -> Unit) {
        Dev.info(log, "browserservice.set_ready", "callback_set" to true)
        onReadyCallback = callback
    }

    override val messages: StateFlow<List<ChatMessage>> = chatState.messages
    override val isTyping: StateFlow<Boolean> = chatState.isTyping

    override fun getComponent(): JComponent = browserComponent.component

    override fun sendUserMessage(content: String, contextAttached: Boolean) {
        val message = UserMessage(
            id = ChatState.generateId(),
            timestamp = kotlinx.datetime.Clock.System.now(),
            content = content,
            contextAttached = contextAttached
        )
        chatState.addMessage(message)
        browserComponent.scrollToBottom()
    }

    override fun addAssistantMessage(content: String, providerId: String?, isError: Boolean) {
        // When the model returns nothing but it's NOT marked as an explicit error:
        // Replace with a friendly message instead of showing an empty bubble.
        val safeContent = if (content.isBlank() && !isError) {
            "⚠️ No response received from provider. The service may be temporarily unavailable."
        } else {
            content
        }

        val message = AssistantMessage(
            id = ChatState.generateId(),
            timestamp = kotlinx.datetime.Clock.System.now(),
            content = safeContent,
            providerId = providerId,
            isError = isError || content.isBlank()
        )

        chatState.addMessage(message)
        browserComponent.scrollToBottom()
    }

    override fun addSystemMessage(content: String, type: SystemMessageType) {
        val message = SystemMessage(
            id = ChatState.generateId(),
            timestamp = kotlinx.datetime.Clock.System.now(),
            content = content,
            type = type
        )
        chatState.addMessage(message)
        browserComponent.scrollToBottom()
    }

    override fun setTyping(typing: Boolean) {
        chatState.setTyping(typing)
    }

    override fun clearChat() {
        chatState.clear()
        // Reset the browser component's render state so it knows to start fresh
        browserComponent.resetRenderState()
    }

    override fun dispose() {
        browserComponent.dispose()
    }
}