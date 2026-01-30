// File: src/main/kotlin/com/youmeandmyself/ai/chat/service/ChatMessage.kt
package com.youmeandmyself.ai.chat.service

import kotlinx.datetime.Instant

sealed class ChatMessage {
    abstract val id: String
    abstract val timestamp: Instant
    abstract val content: String
}

data class UserMessage(
    override val id: String,
    override val timestamp: Instant,
    override val content: String,
    val contextAttached: Boolean = false
) : ChatMessage()

data class AssistantMessage(
    override val id: String,
    override val timestamp: Instant,
    override val content: String,
    val isError: Boolean = false,
    val providerId: String? = null
) : ChatMessage()

data class SystemMessage(
    override val id: String,
    override val timestamp: Instant,
    override val content: String,
    val type: SystemMessageType
) : ChatMessage()

enum class SystemMessageType {
    CONTEXT_READY, TYPING_INDICATOR, ERROR, INFO
}