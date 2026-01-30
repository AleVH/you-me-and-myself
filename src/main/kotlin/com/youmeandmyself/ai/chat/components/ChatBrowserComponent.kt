package com.youmeandmyself.ai.chat.components

import com.intellij.openapi.project.Project
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefJSQuery
import com.intellij.ui.jcef.executeJavaScript
import com.youmeandmyself.ai.chat.service.ChatMessage
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
 * ## Rendering Strategy
 *
 * Uses incremental rendering to avoid re-rendering the entire chat history
 * on each new message. Tracks the count of rendered messages and only
 * appends new ones. This is critical for:
 * - Performance with long conversations
 * - Avoiding race conditions with async JS execution
 * - Maintaining correct message order
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
                when (message) {
                    "READY" -> {
                        Dev.info(log, "browser.ready", "dom_ready" to true)

                        // DOM is ready - set up message handling
                        setupIncrementalMessageHandling()

                        // Signal to ChatPanel that we're ready
                        onReadyCallback?.invoke(true)
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
            loadEnhancedHtml()

            Dev.info(log, "browser.setup", "complete" to true)
        } catch (e: Throwable) {
            Dev.error(log, "browser.init", e, "failed" to true)
            throw e
        }
    }

    private fun loadEnhancedHtml() {
        val htmlContent = buildEnhancedChatHTML()

        Dev.info(
            log,
            "browser.load_enhanced",
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

    private fun buildEnhancedChatHTML(): String {
        return """
        <!DOCTYPE html>
        <html lang="en">
        <head>
            <meta charset="UTF-8" />
            ${buildBaseStyles()}
            ${buildHighlightResources()}
            ${buildMarkdownResources()}
        </head>
        <body>
            <div id="chatContainer" class="chat-container"></div>

            ${buildFenceRendererScript()}

            <script>
            let isInitialized = false;
            
            // Create the bridge function for JS → Kotlin communication
            window.sendToKotlin = function(message) {
                ${jsQuery.inject("message")}
            };
            
            document.addEventListener("DOMContentLoaded", () => {
                console.log("[YMM] DOMContentLoaded fired");
                window.sendToKotlin('READY');
                window.YMM_READY = true;
                console.log("[YMM] YMM_READY=true");
                initializeChat();
            });

            function initializeChat() {
                if (isInitialized) return;
                isInitialized = true;
                
                // Ensure markdown-it instance exists
                if (!window.md) {
                    console.error("[YMM] markdown-it not initialized");
                    window.md = { render: (text) => text };
                }

                // Initialize highlight.js
                if (window.hljs) {
                    hljs.configure({ ignoreUnescapedHTML: true });
                }

                // Expose addMessage globally so Kotlin can call it
                window.addMessage = function(role, text, timestamp, isComplete) {
                    const chatContainer = document.getElementById("chatContainer");
                    if (!chatContainer) {
                        console.error("[YMM] chatContainer not found");
                        return;
                    }

                    const msg = document.createElement("div");
                    msg.classList.add("message");
                    if (role === "user") {
                        msg.classList.add("user");
                    } else {
                        msg.classList.add("assistant");
                    }

                    // Use markdown rendering for assistant messages
                    const content = role === "assistant" ? renderMarkdown(text) : escapeHtml(text);
                    msg.innerHTML = content;

                    // Apply syntax highlighting
                    if (window.hljs) {
                        setTimeout(() => {
                            msg.querySelectorAll('pre code').forEach((block) => {
                                try {
                                    window.hljs.highlightElement(block);
                                } catch (e) {
                                    console.warn("[YMM] highlight failed", e);
                                }
                            });
                        }, 0);
                    }

                    const tsDiv = document.createElement("div");
                    tsDiv.className = "timestamp";
                    tsDiv.textContent = timestamp || "";
                    msg.appendChild(tsDiv);

                    chatContainer.appendChild(msg);
                    chatContainer.scrollTop = chatContainer.scrollHeight;
                };

                window.showThinking = function() {
                    const chatContainer = document.getElementById("chatContainer");
                    if (!document.getElementById("thinking")) {
                        const bubble = document.createElement("div");
                        bubble.id = "thinking";
                        bubble.textContent = "Thinking…";
                        bubble.className = "message assistant";
                        chatContainer.appendChild(bubble);
                        chatContainer.scrollTop = chatContainer.scrollHeight;
                    }
                };

                window.hideThinking = function() {
                    const t = document.getElementById("thinking");
                    if (t) t.remove();
                };
                
                // Clear chat container (used when chat is reset)
                window.clearChat = function() {
                    const chatContainer = document.getElementById("chatContainer");
                    if (chatContainer) {
                        chatContainer.innerHTML = '';
                    }
                };
            }

            function renderMarkdown(text) {
                try {
                    return window.md ? window.md.render(text || "") : escapeHtml(text);
                } catch (e) {
                    console.error("Markdown render error", e);
                    return escapeHtml(text);
                }
            }

            function escapeHtml(text) {
                const div = document.createElement('div');
                div.textContent = text;
                return div.innerHTML;
            }
            </script>
        </body>
        </html>
    """.trimIndent()
    }

    private var messageHandlingSetup = false

    /**
     * Sets up incremental message rendering.
     *
     * Instead of clearing and re-rendering all messages on every change,
     * this tracks how many messages have been rendered and only appends
     * new ones. This solves:
     * - Performance issues with long chats
     * - Race conditions causing message order scrambling
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
                    Dev.info(log, "browser.render_incremental",
                        "already_rendered" to renderedMessageCount,
                        "new_messages" to newMessages.size,
                        "total" to totalMessages
                    )

                    newMessages.forEachIndexed { index, message ->
                        val globalIndex = renderedMessageCount + index
                        when (message) {
                            is UserMessage -> renderMessageChunked(message.content, role = "user", index = globalIndex)
                            is AssistantMessage -> renderMessageChunked(message.content, role = "assistant", index = globalIndex)
                            is SystemMessage -> renderMessageChunked(message.content, role = "assistant", index = globalIndex)
                        }
                    }

                    // Update the count after rendering
                    renderedMessageCount = totalMessages
                }
            }
        }
    }

    /**
     * Renders a single message, chunking large content to avoid JS issues.
     */
    private fun renderMessageChunked(content: String, role: String, index: Int) {
        val maxChunkSize = 2000
        val chunks = if (content.length > maxChunkSize) content.chunked(maxChunkSize) else listOf(content)

        Dev.info(
            log, "browser.chunking",
            "message_index" to index,
            "total_chunks" to chunks.size,
            "original_length" to content.length
        )

        chunks.forEachIndexed { chunkIndex, chunk ->
            val isFinalChunk = chunkIndex == chunks.size - 1
            val jsFunction = buildSafeJSFunction(chunk, role, isFinalChunk)
            executeWhenReady(jsFunction)
        }
    }

    private fun buildSafeJSFunction(
        content: String,
        role: String,
        isFinalChunk: Boolean = true
    ): String {
        val escapedContent = escapeJS(content)
        val safeRole = if (role == "user") "user" else "assistant"

        val formattedTimestamp = kotlinx.datetime.Clock.System.now()
            .toLocalDateTime(kotlinx.datetime.TimeZone.currentSystemDefault())
            .let { "%02d:%02d".format(it.hour, it.minute) }

        return """
        try {
            addMessage('$safeRole', '$escapedContent', '$formattedTimestamp', $isFinalChunk);
        } catch (error) {
            console.error('[YMM] Chunk failed:', error);
            const chatContainer = document.getElementById('chatContainer');
            if (chatContainer) {
                const fallbackDiv = document.createElement('div');
                fallbackDiv.className = 'message ' + ('$safeRole');
                fallbackDiv.textContent = '${escapeJS(content.take(500))}' + (${content.length} > 500 ? '...' : '');
                chatContainer.appendChild(fallbackDiv);
            }
        }
    """.trimIndent()
    }

    private fun escapeJS(text: String): String {
        return text.replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
    }

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

    // -------------------------------------------------------------------------
    // Styles and resources
    // -------------------------------------------------------------------------

    private fun buildBaseStyles(): String = """
        <style>
          body {
              background-color: #1e1e1e;
              color: #ddd;
              font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
              margin: 0;
              padding: 0;
          }
          html, body {
            height: 100%;
            margin: 0;
            overflow: hidden;
          }
          .chat-container {
            display: flex;
            flex-direction: column;
            height: 100vh;
            overflow-y: auto;
            padding: 1rem;
            flex: 1;
            scrollbar-width: thin;
            scrollbar-color: #555 #2a2a2a;
          }
          .message {
              max-width: 85%;
              margin: 0.4rem 0;
              padding: 0.8rem 1rem;
              border-radius: 1rem;
              word-break: break-word;
              line-height: 1.5;
              background-color: #2b2b2b;
              color: #f2f2f2;
              border: 1px solid #3a3a3a;
              align-self: flex-start;
              animation: fadeIn 0.2s ease-in;
          }
          .message.user {
              background-color: #0a84ff;
              color: #fff;
              border: 1px solid #0a84ff;
              align-self: flex-end;
          }
          @keyframes fadeIn {
              from { opacity: 0; transform: translateY(5px); }
              to { opacity: 1; transform: translateY(0); }
          }
          .timestamp {
              font-size: 0.7rem;
              color: #999;
              margin-top: 0.3rem;
              text-align: right;
          }
          pre, code {
              font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
              border-radius: 0.5rem;
          }
          pre {
              background: #1b1b1b;
              border: 1px solid #333;
              padding: 0.75rem;
              overflow-x: auto;
              position: relative;
          }
          code {
              background: #151515;
              padding: 0.15rem 0.3rem;
          }
          .copy-btn {
              position: absolute;
              top: 6px;
              right: 6px;
              background-color: #333;
              color: #ccc;
              border: none;
              border-radius: 4px;
              padding: 3px 6px;
              font-size: 0.75rem;
              cursor: pointer;
              opacity: 0.4;
              transition: opacity 0.2s, background-color 0.2s;
          }
          .copy-btn:hover {
              opacity: 1;
              background-color: #444;
          }
          .code-block { position: relative; }
          .lang-label {
              position: absolute;
              top: 6px; right: 40px;
              background: #444;
              color: #ccc;
              font-size: 0.65rem;
              border-radius: 4px;
              padding: 2px 6px;
              text-transform: uppercase;
          }
          .message.assistant {
              background-color: #2a2a2a;
              color: #ddd;
              border-radius: 0.75rem;
              padding: 1rem 1.25rem;
              line-height: 1.5;
          }

          .message.assistant > *:first-child {
              margin-top: 0;
          }

          .message.assistant {
              margin-top: 0.5rem;
          }
        </style>
        """.trimIndent()

    private fun buildHighlightResources(): String {
        return try {
            val highlightJs = javaClass.getResource("/chat-window/highlight.min.js")?.readText()
            val highlightCss = javaClass.getResource("/chat-window/github-dark.css")?.readText()

            if (highlightJs == null || highlightCss == null) {
                Dev.error(log, "browser.resources",
                    Exception("Highlight.js resources missing: js=${highlightJs == null}, css=${highlightCss == null}"),
                    "failed_to_load" to true
                )
                return "<!-- Highlight.js resources missing -->"
            }

            """
        <!-- highlight.js -->
        <style>
        $highlightCss
        </style>
        <script>
        $highlightJs
        </script>
        """.trimIndent()
        } catch (e: Exception) {
            Dev.error(log, "browser.resources", e, "failed_to_load" to true)
            "<!-- Error loading highlight resources -->"
        }
    }

    private fun buildMarkdownResources(): String {
        return try {
            val markdownIt = javaClass.getResource("/chat-window/markdown-it.min.js")?.readText()

            if (markdownIt == null) {
                Dev.error(log, "browser.resources",
                    Exception("markdown-it.min.js missing from resources"),
                    "resource_path" to "/chat-window/markdown-it.min.js"
                )
                return "<!-- markdown-it resource missing -->"
            }

            """
            <!-- markdown-it -->
            <script>
            $markdownIt
            </script>
            <script>
            document.addEventListener("DOMContentLoaded", () => {
                if (typeof window.markdownit !== "undefined") {
                    window.md = window.markdownit({
                        html: false,
                        linkify: true,
                        breaks: true
                    });
                    console.log("[YMM] markdown-it ready");
                } else {
                    console.error("[YMM] markdown-it missing");
                }
            });
            </script>
        """.trimIndent()
        } catch (e: Exception) {
            Dev.error(log, "browser.resources", e, "failed_to_load_markdown" to true)
            "<!-- Error loading markdown resources -->"
        }
    }

    private fun buildFenceRendererScript(): String = """
        <script>
        document.addEventListener("DOMContentLoaded", () => {
          if (!window.md) {
            console.error("[YMM] markdown-it instance not ready for fence override");
            return;
          }
        
          console.log("[YMM] Installing markdown-it fence renderer");
        
          window.md.renderer.rules.fence = function (tokens, idx) {
            const token = tokens[idx];
            const rawCode = token.content || "";
            const rawLang = (token.info || "").trim().toLowerCase();
            const langLabel = rawLang ? rawLang.toUpperCase() : "CODE";
        
            let highlightedHtml;
            try {
              if (window.hljs && rawLang && hljs.getLanguage(rawLang)) {
                highlightedHtml = hljs.highlight(rawCode, { language: rawLang }).value;
              } else if (window.hljs) {
                highlightedHtml = hljs.highlightAuto(rawCode).value;
              } else {
                highlightedHtml = rawCode
                  .replace(/&/g, "&amp;")
                  .replace(/</g, "&lt;")
                  .replace(/>/g, "&gt;");
              }
            } catch (err) {
              console.warn("[YMM] highlight.js failed:", err);
              highlightedHtml = rawCode;
            }
        
            return `
              <div class="code-block">
                <span class="lang-label">${'$'}{langLabel}</span>
                <pre><code class="hljs ${'$'}{rawLang}">${'$'}{highlightedHtml}</code></pre>
              </div>
            `;
          };
        
          console.log("[YMM] Fence renderer installed successfully");
        });
        </script>
        """.trimIndent()

    /**
     * Executes JavaScript with exponential backoff retry logic
     * to handle JCEF initialization timing issues.
     */
    private fun executeWhenReady(script: String) {
        val wrapped = """
        (function waitReady() {
            console.log('[YMM] Checking readiness:', {
                YMM_READY: window.YMM_READY,
                addMessage: typeof addMessage,
                hljs: !!window.hljs,
                md: !!window.md
            });
            
            if (window.YMM_READY === true && typeof addMessage === 'function') {
                try {
                    $script
                } catch (err) {
                    console.error('[YMM] executeWhenReady script error:', err);
                }
            } else {
                setTimeout(waitReady, 100);
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
                        Dev.info(log, "executeWhenReady.success",
                            "attempts" to attempts,
                            "total_delay_ms" to (50L * attempts)
                        )
                    }
                    break

                } catch (e: IllegalStateException) {
                    if (e.message?.contains("JCEF browser") == true ||
                        e.message?.contains("not initialized") == true) {

                        attempts++

                        if (attempts >= maxAttempts) {
                            Dev.error(log, "executeWhenReady.timeout", e,
                                "gave_up_after_attempts" to attempts,
                                "script_preview" to Dev.preview(script, 100)
                            )
                            break
                        }

                        if (attempts <= 3) {
                            Dev.info(log, "executeWhenReady.retry",
                                "attempt" to attempts,
                                "delay_ms" to delayMs,
                                "reason" to "jcef_not_ready"
                            )
                        }

                        delay(delayMs)
                        delayMs = min(delayMs * 2, 1000L)

                    } else {
                        Dev.warn(log, "executeWhenReady.failed", e,
                            "script_length" to script.length,
                            "reason" to "unexpected_illegal_state"
                        )
                        break
                    }

                } catch (t: Throwable) {
                    Dev.warn(log, "executeWhenReady.failed", t,
                        "script_length" to script.length,
                        "attempts" to attempts
                    )
                    break
                }
            }
        }
    }
}