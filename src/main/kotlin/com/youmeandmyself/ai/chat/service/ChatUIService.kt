// File: src/main/kotlin/com/youmeandmyself/ai/chat/service/ChatUIService.kt
package com.youmeandmyself.ai.chat.service

import com.intellij.openapi.project.Project
import kotlinx.coroutines.flow.StateFlow
import javax.swing.JComponent

interface ChatUIService {
    // State access
    val messages: StateFlow<List<ChatMessage>>
    val isTyping: StateFlow<Boolean>

    // UI component
    fun getComponent(): JComponent

    // Message operations
    fun sendUserMessage(content: String, contextAttached: Boolean = false)
    fun addAssistantMessage(content: String, providerId: String? = null, isError: Boolean = false)
    fun addSystemMessage(content: String, type: SystemMessageType = SystemMessageType.INFO)
    fun setTyping(typing: Boolean)
    fun clearChat()

    // Lifecycle
    fun dispose()
}

// Factory for creating the service
interface ChatUIServiceFactory {
    fun create(project: Project): ChatUIService
}