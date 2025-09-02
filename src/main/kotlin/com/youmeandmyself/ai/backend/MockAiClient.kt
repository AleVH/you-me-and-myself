package com.youmeandmyself.ai.backend

import com.youmeandmyself.ai.model.AiRequest
import com.youmeandmyself.ai.model.AiResponse

/**
 * A mock backend that just echoes the input.
 * Useful for testing the plugin UI without calling a real AI.
 */
class MockAiClient : AiBackend {
    override fun send(request: AiRequest): AiResponse {
        val echo = "🧪 [Mock AI] You said: \"${request.input}\""
        return AiResponse(content = echo, tokensUsed = request.input.length / 4)
    }
}
