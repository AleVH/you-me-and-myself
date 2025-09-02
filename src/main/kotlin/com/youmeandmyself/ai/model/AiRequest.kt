package com.youmeandmyself.ai.model

/**
 * Represents a message sent to the AI backend.
 * You can expand this to include system prompts, model name, temperature, etc.
 */
data class AiRequest(
    val input: String
)
