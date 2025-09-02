package com.youmeandmyself.ai.backend

import com.youmeandmyself.ai.model.AiRequest
import com.youmeandmyself.ai.model.AiResponse

/**
 * Interface for connecting to any AI backend.
 * You can implement this for OpenAI, Claude, local LLMs, etc.
 */
interface AiBackend {
    fun send(request: AiRequest): AiResponse
}
