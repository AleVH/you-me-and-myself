package com.youmeandmyself.ai.chat.components

import com.intellij.openapi.project.Project
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefJSQuery
import com.intellij.ui.jcef.executeJavaScript
import com.youmeandmyself.ai.chat.service.ChatState
import com.youmeandmyself.ai.chat.service.UserMessage
import com.youmeandmyself.ai.chat.service.AssistantMessage
import com.youmeandmyself.ai.chat.service.SystemMessage
import com.youmeandmyself.dev.Dev
import javax.swing.JComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineScope
import kotlinx.datetime.toLocalDateTime
import kotlin.math.min

/**
 * Browser-based chat component using JCEF (Java Chromium Embedded Framework).
 *
 * Renders chat messages with markdown support, syntax highlighting, and
 * professional styling. Falls back to SwingChatService if JCEF fails.
 *
 * ## Architecture (Phase 4B)
 *
 * The HTML/CSS/JS for the chat interface lives in separate resource files:
 *
 *   src/main/resources/chat-window/
 *     ├── chat.html          — HTML skeleton with {{PLACEHOLDER}} tokens
 *     ├── chat.css            — all chat styles
 *     ├── chat.js             — all chat logic (init, addMessage, bookmarks, etc.)
 *     ├── highlight.min.js    — highlight.js library (browser build)
 *     ├── github-dark.css     — highlight.js theme
 *     └── markdown-it.min.js  — markdown-it library
 *
 * This class assembles them into a single HTML string at runtime by:
 *   1. Reading all resource files
 *   2. Injecting the JBCefJSQuery bridge into chat.js
 *   3. Replacing placeholders in chat.html with the inlined content
 *   4. Passing the assembled HTML to browser.loadHTML()
 *
 * JCEF's loadHTML() doesn't resolve relative paths, so everything must be
 * inlined. This is the same pattern already used for highlight.js and
 * markdown-it — we're just extending it to our own files.
 *
 * ## Rendering Strategy
 *
 * Uses incremental rendering to avoid re-rendering the entire chat history
 * on each new message. Tracks the count of rendered messages and only
 * appends new ones. This is critical for:
 * - Performance with long conversations
 * - Avoiding race conditions with async JS execution
 * - Maintaining correct message order
 *
 * ## Current Limitation: No Message Chunking
 *
 * Messages are rendered as a whole (no chunking) because splitting markdown
 * mid-content breaks rendering - especially code blocks. If a code fence
 * is split between chunks, markdown-it can't parse either chunk correctly.
 *
 * ## Future Enhancement: True Streaming Rendering
 *
 * To implement real-time streaming (showing text as it arrives from the API):
 *
 * 1. **Provider Changes**: GenericLlmProvider needs to support streaming responses
 *    using Server-Sent Events (SSE) or chunked transfer encoding. Most LLM APIs
 *    support this (OpenAI: stream=true, Gemini: streamGenerateContent).
 *
 * 2. **Accumulator Pattern**: Create a StreamingAssembler that:
 *    - Buffers incoming chunks
 *    - Detects safe render points (complete paragraphs, closed code blocks)
 *    - Only triggers render when content is "markdown-safe"
 *    - Tracks open fences (```) to avoid splitting code blocks
 *
 * 3. **Incremental DOM Updates**: Instead of replacing innerHTML, append to
 *    an existing message element. This requires:
 *    - Tracking the "current streaming message" element ID
 *    - A JS function like `appendToMessage(id, newContent)` or `updateMessage(id, fullContent)`
 *    - Re-running markdown render on the accumulated content (not just the delta)
 *
 * 4. **Cursor/Typing Indicator**: Show a blinking cursor at the end of the
 *    streaming message to indicate more content is coming.
 *
 * 5. **Error Recovery**: If streaming fails mid-message, gracefully finalize
 *    whatever content was received rather than showing nothing.
 *
 * For now, we show a "Thinking..." indicator while waiting for the complete response.
 */
class ChatBrowserComponent(project: Project, private val chatState: ChatState) {
    private val log = Dev.logger(ChatBrowserComponent::class.java)
    private var onReadyCallback: ((Boolean) -> Unit)? = null

    fun setOnReady(callback: (Boolean) -> Unit) {
        onReadyCallback = callback
    }

    private val browser: JBCefBrowser = JBCefBrowser()
    private val scope = CoroutineScope(Dispatchers.IO)

    val component: JComponent get() = browser.component

    @Suppress("DEPRECATION") // still the correct public factory in 243
    private val jsQuery = JBCefJSQuery.create(browser)

    /**
     * Tracks how many messages have been rendered to the DOM.
     * Used for incremental rendering - only new messages are appended.
     */
    private var renderedMessageCount = 0

    init {
        System.setProperty("JBCefClient.Properties.JS_QUERY_POOL_SIZE", "10")
        Dev.info(log, "browser.init", "start" to true)
        try {
            // Handle messages coming from JS
            jsQuery.addHandler { message ->
                Dev.info(log, "browser.handler_called", "message" to message)
                when {
                    message == "READY" -> {
                        Dev.info(log, "browser.ready", "dom_ready" to true)
                        setupIncrementalMessageHandling()
                        onReadyCallback?.invoke(true)
                        null
                    }
                    message.startsWith("COPY:") -> {
                        // Fallback clipboard handler when JS clipboard API is blocked
                        val code = message.removePrefix("COPY:")
                        copyToClipboard(code)
                        null
                    }
                    message.startsWith("BOOKMARK:") -> {
                        val exchangeId = message.removePrefix("BOOKMARK:")
                        Dev.info(log, "browser.bookmark", "exchange_id" to exchangeId, "action" to "add")
                        // TODO: Wire to BookmarkService.addBookmark() with collection picker
                        null
                    }
                    message.startsWith("UNBOOKMARK:") -> {
                        val exchangeId = message.removePrefix("UNBOOKMARK:")
                        Dev.info(log, "browser.bookmark", "exchange_id" to exchangeId, "action" to "remove")
                        // TODO: Wire to BookmarkService.removeBookmark()
                        null
                    }
                    else -> {
                        logFromJs("from_js", message)
                        null
                    }
                }
            }

            // Fallback timeout in case READY message doesn't arrive
            scope.launch {
                delay(2000)
                Dev.info(log, "browser.manual_test", "triggering_ready" to true)
                onReadyCallback?.invoke(true)
            }

            // Load the chat HTML
            loadChatHtml()

            Dev.info(log, "browser.setup", "complete" to true)
        } catch (e: Throwable) {
            Dev.error(log, "browser.init", e, "failed" to true)
            throw e
        }
    }

    // -------------------------------------------------------------------------
    // HTML Assembly
    // -------------------------------------------------------------------------

    /**
     * Loads all resource files, assembles the final HTML, and loads it into JCEF.
     *
     * Assembly steps:
     * 1. Read all resource files from /chat-window/
     * 2. Inject the JBCefJSQuery bridge into chat.js (replace placeholder)
     * 3. Replace all {{PLACEHOLDER}} tokens in chat.html with inlined content
     * 4. Load the assembled HTML via browser.loadHTML()
     *
     * If any resource fails to load, a fallback HTML with an error message is used.
     * The pattern matches the existing buildHighlightResources() approach.
     */
    private fun loadChatHtml() {
        val htmlContent = assembleChatHtml()

        Dev.info(
            log,
            "browser.load_assembled",
            "html_length" to htmlContent.length,
            "html_preview" to Dev.preview(htmlContent, 500)
        )

        val testInject = jsQuery.inject("'TEST'")
        Dev.info(
            log,
            "browser.jsquery_test",
            "inject_result_length" to testInject.length,
            "inject_preview" to Dev.preview(testInject, 200)
        )

        browser.loadHTML(htmlContent)
    }

    /**
     * Reads all chat resource files and assembles them into a single HTML string.
     *
     * Resource files (required):
     * - chat.html          — HTML template with {{PLACEHOLDER}} tokens
     * - chat.css            — chat styles
     * - chat.js             — chat logic (contains /* __KOTLIN_BRIDGE__ */ placeholder)
     * - highlight.min.js    — syntax highlighting library
     * - github-dark.css     — syntax highlighting theme
     * - markdown-it.min.js  — markdown rendering library
     *
     * Resource files (optional — graceful degradation if missing):
     * - katex.min.js        — LaTeX math rendering
     * - katex-bundled.css   — KaTeX styles with base64-encoded fonts
     * - mermaid.min.js      — Diagram rendering
     *
     * The JBCefJSQuery bridge is injected by replacing the /* __KOTLIN_BRIDGE__ */
     * placeholder in chat.js with the actual jsQuery.inject() output.
     *
     * @return Complete HTML string ready for browser.loadHTML()
     */
    private fun assembleChatHtml(): String {
        return try {
            // Read our resource files (required)
            val chatHtml = loadResource("/chat-window/chat.html", "chat.html")
            val chatCss = loadResource("/chat-window/chat.css", "chat.css")
            val chatJs = loadResource("/chat-window/chat.js", "chat.js")

            // Read third-party libraries (required)
            val highlightJs = loadResource("/chat-window/highlight.min.js", "highlight.min.js")
            val highlightCss = loadResource("/chat-window/github-dark.css", "github-dark.css")
            val markdownJs = loadResource("/chat-window/markdown-it.min.js", "markdown-it.min.js")

            // Read optional rendering libraries (graceful degradation if missing)
            val katexJs = loadOptionalResource("/chat-window/katex.min.js", "katex.min.js")
            val katexCss = loadOptionalResource("/chat-window/katex-bundled.css", "katex-bundled.css")
                ?: loadOptionalResource("/chat-window/katex.min.css", "katex.min.css")
            val mermaidJs = loadOptionalResource("/chat-window/mermaid.min.js", "mermaid.min.js")

            if (chatHtml == null || chatCss == null || chatJs == null) {
                Dev.error(
                    log, "browser.assembly",
                    Exception("Core chat resources missing: html=${chatHtml == null}, css=${chatCss == null}, js=${chatJs == null}"),
                    "failed" to true
                )
                return buildFallbackHtml("Chat resources missing. Check that chat.html, chat.css, and chat.js exist in src/main/resources/chat-window/")
            }

            // Inject the JCEF bridge into chat.js
            // The bridge allows JS to send messages to Kotlin (e.g., READY, BOOKMARK, COPY)
            val bridgeCode = jsQuery.inject("message")
            val chatJsWithBridge = chatJs.replace("/* __KOTLIN_BRIDGE__ */", bridgeCode)

            Dev.info(
                log, "browser.assembly",
                "chat_css_size" to chatCss.length,
                "chat_js_size" to chatJsWithBridge.length,
                "highlight_js_loaded" to (highlightJs != null),
                "markdown_js_loaded" to (markdownJs != null),
                "katex_loaded" to (katexJs != null),
                "mermaid_loaded" to (mermaidJs != null),
                "bridge_injected" to chatJsWithBridge.contains("cefQuery") // sanity check
            )

            // Assemble: replace all placeholders in the HTML template
            chatHtml
                .replace("{{CHAT_CSS}}", chatCss)
                .replace("{{HIGHLIGHT_CSS}}", highlightCss ?: "/* highlight.js CSS missing */")
                .replace("{{HIGHLIGHT_JS}}", highlightJs ?: "/* highlight.js missing */")
                .replace("{{MARKDOWN_JS}}", markdownJs ?: "/* markdown-it missing */")
                .replace("{{KATEX_CSS}}", katexCss ?: "/* KaTeX CSS not available */")
                .replace("{{KATEX_JS}}", katexJs ?: "/* KaTeX not available */")
                .replace("{{MERMAID_JS}}", mermaidJs ?: "/* Mermaid not available */")
                .replace("{{CHAT_JS}}", chatJsWithBridge)

        } catch (e: Exception) {
            Dev.error(log, "browser.assembly", e, "failed" to true)
            buildFallbackHtml("Error assembling chat interface: ${e.message}")
        }
    }

    /**
     * Loads a text resource file from the classpath.
     *
     * @param path Resource path (e.g., "/chat-window/chat.css")
     * @param name Human-readable name for logging
     * @return File contents as a string, or null if not found
     */
    private fun loadResource(path: String, name: String): String? {
        return try {
            val content = javaClass.getResource(path)?.readText()
            if (content == null) {
                Dev.error(
                    log, "browser.resource_missing",
                    Exception("$name not found at $path"),
                    "resource_path" to path
                )
            } else {
                Dev.info(log, "browser.resource_loaded", "name" to name, "size" to content.length)
            }
            content
        } catch (e: Exception) {
            Dev.error(log, "browser.resource_error", e, "name" to name, "path" to path)
            null
        }
    }

    /**
     * Loads an optional resource file from the classpath.
     *
     * Unlike loadResource(), this logs at INFO level when the file is missing
     * (not an error — the feature is simply unavailable). This supports the
     * pluggable rendering architecture: drop a file in to enable, delete to disable.
     *
     * @param path Resource path (e.g., "/chat-window/katex.min.js")
     * @param name Human-readable name for logging
     * @return File contents as a string, or null if not found
     */
    private fun loadOptionalResource(path: String, name: String): String? {
        return try {
            val content = javaClass.getResource(path)?.readText()
            if (content == null) {
                Dev.info(log, "browser.optional_resource_skipped", "name" to name, "path" to path)
            } else {
                Dev.info(log, "browser.resource_loaded", "name" to name, "size" to content.length)
            }
            content
        } catch (e: Exception) {
            Dev.info(log, "browser.optional_resource_skipped", "name" to name, "reason" to (e.message ?: "unknown"))
            null
        }
    }

    /**
     * Builds a minimal fallback HTML when resource loading fails.
     * Shows an error message with instructions for the developer.
     */
    private fun buildFallbackHtml(errorMessage: String): String {
        val bridgeCode = jsQuery.inject("message")
        return """
            <!DOCTYPE html>
            <html lang="en">
            <head><meta charset="UTF-8" /></head>
            <body style="background:#1e1e1e;color:#e87979;font-family:sans-serif;padding:20px;">
                <h3>⚠️ Chat Interface Error</h3>
                <p>$errorMessage</p>
                <p style="color:#888;font-size:12px;">The chat interface resources could not be loaded.
                Ensure all files exist in <code>src/main/resources/chat-window/</code>.</p>
                <script>
                    window.sendToKotlin = function(message) { $bridgeCode };
                    window.YMM_READY = true;
                    window.sendToKotlin('READY');
                    window.addMessage = function() {};
                    window.showThinking = function() {};
                    window.hideThinking = function() {};
                    window.clearChat = function() {};
                </script>
            </body>
            </html>
        """.trimIndent()
    }

    // -------------------------------------------------------------------------
    // Message Rendering (Kotlin → JS)
    // -------------------------------------------------------------------------

    private var messageHandlingSetup = false

    /**
     * Sets up incremental message rendering.
     *
     * Listens to ChatState.messages flow and renders only new messages.
     * Messages are rendered whole (not chunked) to ensure markdown
     * integrity - especially for code blocks with fences.
     */
    private fun setupIncrementalMessageHandling() {
        if (messageHandlingSetup) {
            Dev.info(log, "browser.setup_messages", "already_setup" to true)
            return
        }
        messageHandlingSetup = true

        Dev.info(log, "browser.setup_messages", "start" to true, "initial_count" to chatState.messages.value.size)

        scope.launch {
            chatState.messages.collect { messages ->
                val totalMessages = messages.size

                // Check if chat was cleared (new count less than what we've rendered)
                if (totalMessages < renderedMessageCount) {
                    Dev.info(log, "browser.render", "chat_cleared" to true, "resetting_count" to true)
                    executeWhenReady("window.clearChat();")
                    renderedMessageCount = 0
                }

                // Only render messages we haven't rendered yet
                val newMessages = messages.drop(renderedMessageCount)

                if (newMessages.isNotEmpty()) {
                    Dev.info(
                        log, "browser.render_incremental",
                        "already_rendered" to renderedMessageCount,
                        "new_messages" to newMessages.size,
                        "total" to totalMessages
                    )

                    newMessages.forEach { message ->
                        when (message) {
                            is UserMessage -> renderMessage(message.content, role = "user")
                            is AssistantMessage -> renderMessage(message.content, role = "assistant")
                            is SystemMessage -> renderMessage(message.content, role = "assistant")
                        }
                    }

                    renderedMessageCount = totalMessages
                }
            }
        }
    }

    /**
     * Renders a complete message to the chat.
     *
     * No chunking - the entire message is sent at once to ensure
     * markdown parsing works correctly (especially for code blocks).
     *
     * @param content The message content (markdown for assistant messages)
     * @param role Either "user" or "assistant"
     * @param exchangeId Optional exchange ID for bookmarking (passed to JS addMessage)
     */
    private fun renderMessage(content: String, role: String, exchangeId: String? = null) {
        Dev.info(
            log, "browser.render_message",
            "role" to role,
            "content_length" to content.length
        )

        val escapedContent = escapeJS(content)
        val safeRole = if (role == "user") "user" else "assistant"
        val formattedTimestamp = kotlinx.datetime.Clock.System.now()
            .toLocalDateTime(kotlinx.datetime.TimeZone.currentSystemDefault())
            .let { "%02d:%02d".format(it.hour, it.minute) }
        val safeExchangeId = exchangeId?.let { escapeJS(it) } ?: ""

        val jsFunction = """
            try {
                addMessage('$safeRole', '$escapedContent', '$formattedTimestamp', '$safeExchangeId');
            } catch (error) {
                console.error('[YMM] renderMessage failed:', error);
            }
        """.trimIndent()

        executeWhenReady(jsFunction)
    }

    /**
     * Shows the "Thinking..." indicator.
     *
     * Call this when sending a request to the AI provider.
     * The indicator is automatically hidden when the assistant
     * message arrives (handled in JS addMessage function).
     */
    fun showThinking() {
        Dev.info(log, "browser.show_thinking", "triggered" to true)
        executeWhenReady("window.showThinking();")
    }

    /**
     * Hides the "Thinking..." indicator.
     *
     * Normally called automatically when assistant message arrives.
     * Call this explicitly if the request fails or is cancelled.
     */
    fun hideThinking() {
        Dev.info(log, "browser.hide_thinking", "triggered" to true)
        executeWhenReady("window.hideThinking();")
    }

    /**
     * Updates the metrics widget with current model stats.
     *
     * @param model Model name (e.g., "GPT-4o")
     * @param promptTokens Input tokens for the latest exchange
     * @param completionTokens Output tokens for the latest exchange
     * @param totalTokens Total tokens
     * @param estimatedCost Cost estimate as a string (e.g., "0.03")
     */
    fun updateMetrics(
        model: String?,
        promptTokens: Int?,
        completionTokens: Int?,
        totalTokens: Int?,
        estimatedCost: String?
    ) {
        val parts = mutableListOf<String>()
        model?.let { parts.add("model:'${escapeJS(it)}'") }
        promptTokens?.let { parts.add("promptTokens:$it") }
        completionTokens?.let { parts.add("completionTokens:$it") }
        totalTokens?.let { parts.add("totalTokens:$it") }
        estimatedCost?.let { parts.add("estimatedCost:'${escapeJS(it)}'") }

        executeWhenReady("window.updateMetrics({${parts.joinToString(",")}});")
    }

    /**
     * Sets the bookmark state for an exchange in the UI.
     * Call this when loading conversation history to restore bookmark stars.
     */
    fun setBookmarkState(exchangeId: String, bookmarked: Boolean) {
        executeWhenReady("window.setBookmarkState('${escapeJS(exchangeId)}', $bookmarked);")
    }

    /**
     * Show or hide turn counters.
     */
    fun setTurnCounterVisible(visible: Boolean) {
        executeWhenReady("window.setTurnCounterVisible($visible);")
    }

    // -------------------------------------------------------------------------
    // JavaScript Execution
    // -------------------------------------------------------------------------

    /**
     * Escapes a string for safe embedding in JavaScript single-quoted strings.
     */
    private fun escapeJS(text: String): String {
        return text.replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
    }

    /**
     * Scrolls the chat container to show the latest message.
     */
    fun scrollToBottom() {
        executeWhenReady("window.scrollTo(0, document.body.scrollHeight);")
    }

    /**
     * Resets the rendered message count when chat is cleared externally.
     * Call this when ChatState.clear() is called.
     */
    fun resetRenderState() {
        renderedMessageCount = 0
    }

    fun dispose() {
        browser.dispose()
    }

    private fun logFromJs(event: String, details: String) {
        Dev.info(log, "chat.js", "event" to event, "details" to details)
    }

    /**
     * Copy text to system clipboard.
     *
     * Used as a fallback when the JS clipboard API is blocked in JCEF.
     * The JS code sends a "COPY:..." message which is handled here.
     */
    private fun copyToClipboard(text: String) {
        try {
            val clipboard = java.awt.Toolkit.getDefaultToolkit().systemClipboard
            val selection = java.awt.datatransfer.StringSelection(text)
            clipboard.setContents(selection, selection)
            Dev.info(log, "browser.clipboard", "copied_chars" to text.length)
        } catch (e: Exception) {
            Dev.error(log, "browser.clipboard", e, "failed" to true)
        }
    }

    /**
     * Executes JavaScript with exponential backoff retry logic
     * to handle JCEF initialization timing issues.
     *
     * JCEF browser initialization can take 300-500ms after loadHTML() is called.
     * This method wraps the script in a readiness check and retries with
     * exponential backoff if JCEF isn't ready yet.
     *
     * @param script The JavaScript code to execute
     */
    private fun executeWhenReady(script: String) {
        val wrapped = """
        (function waitReady() {
            if (window.YMM_READY === true && typeof window.addMessage === 'function') {
                try {
                    $script
                } catch (err) {
                    console.error('[YMM] executeWhenReady script error:', err);
                }
            } else {
                setTimeout(waitReady, 50);
            }
        })();
    """.trimIndent()

        scope.launch {
            var attempts = 0
            var delayMs = 50L
            val maxAttempts = 50

            while (attempts < maxAttempts) {
                try {
                    browser.executeJavaScript(wrapped)

                    if (attempts > 0) {
                        Dev.info(
                            log, "executeWhenReady.success",
                            "attempts" to attempts,
                            "total_delay_ms" to (50L * attempts)
                        )
                    }
                    break

                } catch (e: IllegalStateException) {
                    if (e.message?.contains("JCEF browser") == true ||
                        e.message?.contains("not initialized") == true
                    ) {

                        attempts++

                        if (attempts >= maxAttempts) {
                            Dev.error(
                                log, "executeWhenReady.timeout", e,
                                "gave_up_after_attempts" to attempts,
                                "script_preview" to Dev.preview(script, 100)
                            )
                            break
                        }

                        if (attempts <= 3) {
                            Dev.info(
                                log, "executeWhenReady.retry",
                                "attempt" to attempts,
                                "delay_ms" to delayMs,
                                "reason" to "jcef_not_ready"
                            )
                        }

                        delay(delayMs)
                        delayMs = min(delayMs * 2, 1000L)

                    } else {
                        Dev.warn(
                            log, "executeWhenReady.failed", e,
                            "script_length" to script.length,
                            "reason" to "unexpected_illegal_state"
                        )
                        break
                    }

                } catch (t: Throwable) {
                    Dev.warn(
                        log, "executeWhenReady.failed", t,
                        "script_length" to script.length,
                        "attempts" to attempts
                    )
                    break
                }
            }
        }
    }
}