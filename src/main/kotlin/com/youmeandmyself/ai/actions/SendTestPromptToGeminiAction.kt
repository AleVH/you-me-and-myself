// File: src/main/kotlin/com/youmeandmyself/ai/actions/SendTestPromptToGeminiAction.kt
// Purpose: Tools → AI → "Send Test Prompt to Gemini" action.
// Behavior: Prompts user for a short message, calls Gemini v1beta generateContent,
//           and shows the first candidate's text. Surfaces clear HTTP/API error messages.
//
// Design notes:
//  - Uses only simple, concrete @Serializable DTOs (no polymorphism), so no custom SerializersModule is needed.
//  - Single Json instance reused to avoid "redundant creation" warnings.
//  - Numeric 2xx status check (no isSuccess() extension), so no extra Ktor imports are required.
//  - Default model = "gemini-1.5-flash". Base URL can be overridden in settings if you add it there.

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
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import io.ktor.client.call.body
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class SendTestPromptToGeminiAction : AnAction("Send Test Prompt to Gemini") {
    private val log = Logger.getInstance(SendTestPromptToGeminiAction::class.java)

    // Reused JSON instance for (de)serialization to avoid redundant creation warnings
    private val json = Json { ignoreUnknownKeys = true }

    // Lightweight Ktor client with kotlinx.serialization JSON installed
    private val client by lazy {
        HttpClient(CIO) {
            install(ContentNegotiation) { json(json) }
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project: Project = e.project ?: return
        val s = PluginSettingsState.getInstance(project)

        // 1) Resolve API key + base URL (with safe defaults)
        val key = s.geminiApiKey?.trim().orEmpty()
        val base = (s.geminiBaseUrl?.trim()?.ifBlank { null } ?: "https://generativelanguage.googleapis.com").trimEnd('/')
        if (key.isEmpty()) {
            Messages.showErrorDialog(project, "Gemini API key is missing. Add it in Settings → YouMeAndMyself Assistant.", "Gemini Test Prompt")
            return
        }

        // 2) Prompt user for a quick message
        val prompt = Messages.showInputDialog(project, "Enter a quick prompt:", "Gemini Test Prompt", null)
            ?.trim().orEmpty()
        if (prompt.isEmpty()) return

        // 3) Call the API (blocking for simplicity)
        val reply = try {
            runBlocking {
                val model = "gemini-1.5-flash" // cheap/fast default; read from settings later if you add it
                sendGeminiPrompt(base, key, model, prompt)
            }
        } catch (t: Throwable) {
            val msg = t.message ?: t::class.simpleName ?: "error"
            log.warn("Gemini call failed: $msg", t)
            "FAILED ($msg)"
        }

        // 4) Show result
        Messages.showInfoMessage(project, reply, "Gemini Response")
    }

    /**
     * Sends a text-only request to Gemini v1beta generateContent.
     * Endpoint: POST {base}/v1beta/models/{model}:generateContent?key=API_KEY
     * Returns: first candidate's text, or a clear FAILED(...) message on errors.
     */
    private suspend fun sendGeminiPrompt(base: String, apiKey: String, model: String, prompt: String): String {
        val url = "$base/v1beta/models/$model:generateContent?key=${apiKey.trim()}"

        // --- Request payload (text-only) ---
        val req = GenerateContentRequest(
            contents = listOf(
                Content(
                    parts = listOf(TextPart(text = prompt))
                )
            )
        )

        // Execute request
        val httpResp: HttpResponse = client.post(url) {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            header(HttpHeaders.Accept, ContentType.Application.Json.toString())
            setBody(req)
        }

        // Non-2xx → try to parse Gemini error envelope; otherwise return raw body
        if (httpResp.status.value !in 200..299) {
            val raw = httpResp.bodyAsText()
            return try {
                val err = json.decodeFromString(ErrorEnvelope.serializer(), raw)
                val status = err.error?.status ?: "error"
                val message = err.error?.message ?: raw
                "FAILED (HTTP ${httpResp.status.value} — $status: $message)"
            } catch (_: Throwable) {
                "FAILED (HTTP ${httpResp.status.value} — $raw)"
            }
        }

        // 2xx → parse normal response and extract first candidate text
        val resp: GenerateContentResponse = httpResp.body()
        val text = resp.candidates.firstOrNull()
            ?.content?.parts?.firstOrNull()
            ?.text
            ?.trim()
            .orEmpty()

        if (text.isEmpty()) error("Empty response from model")
        return text
    }

    // ------------------------- DTOs -------------------------

    // Request DTOs (simple, concrete, no polymorphism)
    @Serializable
    private data class GenerateContentRequest(
        val contents: List<Content>
    )

    @Serializable
    private data class Content(
        val parts: List<TextPart>
    )

    @Serializable
    private data class TextPart(
        val text: String
    )

    // Response DTOs (subset sufficient to read the first text part)
    @Serializable
    private data class GenerateContentResponse(
        val candidates: List<Candidate> = emptyList()
    )

    @Serializable
    private data class Candidate(
        val content: ContentResponse? = null
    )

    @Serializable
    private data class ContentResponse(
        val parts: List<TextPartResponse> = emptyList()
    )

    @Serializable
    private data class TextPartResponse(
        val text: String? = null
    )

    // Error envelope (so failures are clear and actionable)
    @Serializable
    private data class ErrorEnvelope(
        val error: Err? = null
    )

    @Serializable
    private data class Err(
        val code: Int? = null,
        val message: String? = null,
        val status: String? = null
    )
}
