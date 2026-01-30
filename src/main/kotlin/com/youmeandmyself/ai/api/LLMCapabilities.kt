package com.youmeandmyself.ai.api

import com.intellij.openapi.project.Project

interface ChatClient {
    suspend fun chat(project: Project, prompt: String, system: String? = null): String
}

interface SynopsisClient {
    suspend fun summarize(
        project: Project,
        path: String,
        languageId: String?,
        sourceText: String,
        maxTokens: Int
    ): String
}

/** A provider may implement one or both capabilities. */
interface ProviderHandle {
    val id: String            // e.g., "openai", "deepseek", "gemini"
    val displayName: String
    val chat: ChatClient?     // null if not supported
    val synopsis: SynopsisClient? // null if not supported
}
