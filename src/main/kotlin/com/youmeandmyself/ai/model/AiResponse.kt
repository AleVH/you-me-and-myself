package com.youmeandmyself.ai.model

/**
 * Represents the AI's response.
 * `content` is the raw text, and `tokensUsed` is optional metadata.
 */
data class AiResponse(
    val content: String,
    val tokensUsed: Int? = null
)
