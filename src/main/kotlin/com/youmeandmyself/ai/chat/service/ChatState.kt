// File: src/main/kotlin/com/youmeandmyself/ai/chat/service/ChatState.kt
package com.youmeandmyself.ai.chat.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.datetime.Clock

class ChatState {
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages

    private val _isTyping = MutableStateFlow(false)
    val isTyping: StateFlow<Boolean> = _isTyping

    fun addMessage(message: ChatMessage) {
        _messages.value += message
    }

    fun setTyping(typing: Boolean) {
        _isTyping.value = typing
    }

    fun clear() {
        _messages.value = emptyList()
        _isTyping.value = false
    }

    companion object {
        fun generateId(): String = "msg_${Clock.System.now().toEpochMilliseconds()}_${(0..9999).random()}"
    }
}