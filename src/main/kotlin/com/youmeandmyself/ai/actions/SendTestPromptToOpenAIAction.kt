// file: src/main/kotlin/com/youmeandmyself/ai/actions/SendTestPromptToOpenAIAction.kt
// Purpose: Tools → AI → Send Test Prompt to OpenAI. Prompts the user, calls OpenAI /v1/chat/completions, shows reply.
// Notes:
//  - Uses a default model "gpt-4o-mini" (change here if you add a model field to settings later).
//  - Parses the HTTP response into ChatResponse via .body<ChatResponse>() to avoid type mismatch.
//  - Minimal, blocking runBlocking for simplicity; we can coroutine-ify later if you prefer.

package com.youmeandmyself.ai.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.youmeandmyself.ai.settings.PluginSettingsState
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import io.ktor.client.call.body
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode


class SendTestPromptToOpenAIAction : AnAction("Send Test Prompt to OpenAI") {
    private val log = Logger.getInstance(SendTestPromptToOpenAIAction::class.java)

    // Lightweight Ktor client for this action (JSON enabled)
    private val client by lazy {
        HttpClient(CIO) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project: Project = e.project ?: return
        val s = PluginSettingsState.getInstance(project)

        // 1) Basic config checks (trim + default base URL)
        val key = s.openAiApiKey?.trim().orEmpty()
        val base = (s.openAiBaseUrl?.trim()?.ifBlank { null } ?: "https://api.openai.com").trimEnd('/')
        if (key.isEmpty()) {
            Messages.showErrorDialog(project, "OpenAI API key is missing. Add it in Settings → YouMeAndMyself Assistant.", "OpenAI Test Prompt")
            return
        }

        // 2) Ask user for a simple prompt
        val prompt = Messages.showInputDialog(project, "Enter a quick prompt:", "OpenAI Test Prompt", null)
            ?.trim().orEmpty()
        if (prompt.isEmpty()) return

        // 3) Call chat completions (minimal, blocking for simplicity)
        val reply = try {
            runBlocking {
                // CHANGED: default model here to avoid unresolved reference to settings.openAiModel
                val model = "gpt-4o-mini"
                sendChatPrompt(base, key, model, prompt)
            }
        } catch (t: Throwable) {
            val msg = t.message ?: t::class.simpleName ?: "error"
            log.warn("OpenAI call failed: $msg", t)
            "FAILED ($msg)"
        }

        // 4) Show result to the user
        Messages.showInfoMessage(project, reply, "OpenAI Response")
    }

    // Performs POST /v1/chat/completions with a single user message and returns the first reply’s content.
    private suspend fun sendChatPrompt(base: String, key: String, model: String, prompt: String): String {
        val url = "$base/v1/chat/completions" // OpenAI chat completions endpoint

        val req = ChatRequest(
            model = model,
            messages = listOf(ChatMessage(role = "user", content = prompt)),
            temperature = 0.2
        )

        // Send request and capture raw response
        val httpResp: HttpResponse = client.post(url) {
            header(HttpHeaders.Authorization, "Bearer $key")
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            header(HttpHeaders.Accept, ContentType.Application.Json.toString())
            setBody(req)
        }

        // Treat any non-2xx as error
        if (httpResp.status.value !in 200..299) {
            val raw = httpResp.bodyAsText()
            return try {
                val err = Json { ignoreUnknownKeys = true }.decodeFromString(ErrorEnvelope.serializer(), raw)
                "FAILED (HTTP ${httpResp.status.value} — ${err.error.code ?: err.error.type}: ${err.error.message})"
            } catch (_: Throwable) {
                "FAILED (HTTP ${httpResp.status.value} — $raw)"
            }
        }

        // If successful, parse ChatResponse normally
        val resp: ChatResponse = httpResp.body()
        val content = resp.choices.firstOrNull()?.message?.content?.trim().orEmpty()
        if (content.isEmpty()) error("Empty response from model")
        return content
    }

    // --- Request/response payloads (minimal) ---
    @Serializable
    private data class ChatRequest(
        val model: String,
        val messages: List<ChatMessage>,
        val temperature: Double? = null
    )

    @Serializable
    private data class ChatMessage(
        val role: String,
        val content: String
    )

    @Serializable
    private data class ChatResponse(
        val choices: List<Choice> = emptyList()
    ) {
        @Serializable
        data class Choice(
            val index: Int = 0,
            val message: ChatMessage = ChatMessage("", "")
        )
    }

    @Serializable
    private data class ErrorEnvelope(
        val error: ErrorDetail
    ) {
        @Serializable
        data class ErrorDetail(
            val message: String,
            val type: String? = null,
            val code: String? = null,
            val param: String? = null
        )
    }

}
