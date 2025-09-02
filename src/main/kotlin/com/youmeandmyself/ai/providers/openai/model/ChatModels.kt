// (project-root)/src/main/kotlin/com/youmeandmyself/ai/providers/openai/model/ChatModels.kt
// Data models for OpenAI chat API requests/responses using kotlinx.serialization.

package com.youmeandmyself.ai.providers.openai.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    val temperature: Double? = null,
    // Add any other parameters you use; unknown ones will be ignored by the server.
)

@Serializable
data class ChatMessage(
    val role: String,   // "user" | "assistant" | "system"
    val content: String
)

@Serializable
data class ChatResponse(
    val id: String? = null,
    val choices: List<ChatChoice> = emptyList()
)

@Serializable
data class ChatChoice(
    val index: Int,
    val message: ChatMessage
)
