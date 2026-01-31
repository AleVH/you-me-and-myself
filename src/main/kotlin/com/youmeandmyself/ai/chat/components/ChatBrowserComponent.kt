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

                        // DOM is ready - set up message handling
                        setupIncrementalMessageHandling()

                        // Signal to ChatPanel that we're ready
                        onReadyCallback?.invoke(true)
                        null
                    }
                    message.startsWith("COPY:") -> {
                        // Fallback clipboard handler when JS clipboard API is blocked
                        // JCEF may restrict navigator.clipboard in some configurations
                        val code = message.removePrefix("COPY:")
                        copyToClipboard(code)
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

    /**
     * Builds the complete HTML for the chat interface.
     *
     * Structure:
     * 1. Base styles (CSS for chat bubbles, code blocks, etc.)
     * 2. highlight.js library + theme CSS
     * 3. markdown-it library
     * 4. Single unified initialization script that:
     *    - Initializes markdown-it with highlight.js integration
     *    - Installs custom fence renderer for language labels
     *    - Sets up chat message functions (addMessage, showThinking, etc.)
     *    - Signals readiness to Kotlin
     *
     * All initialization happens in one DOMContentLoaded handler to ensure
     * correct ordering of dependencies.
     */
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

            <script>
            /**
             * Unified initialization script.
             * 
             * All setup happens here in the correct order to ensure
             * dependencies are available when needed.
             */
            (function() {
                let isInitialized = false;
                
                // Create the bridge function for JS → Kotlin communication
                window.sendToKotlin = function(message) {
                    ${jsQuery.inject("message")}
                };
                
                document.addEventListener("DOMContentLoaded", function() {
                    console.log("[YMM] DOMContentLoaded fired");
                    
                    if (isInitialized) {
                        console.log("[YMM] Already initialized, skipping");
                        return;
                    }
                    isInitialized = true;
                    
                    // Step 1: Initialize markdown-it with highlight.js integration
                    if (typeof window.markdownit !== "undefined") {
                        window.md = window.markdownit({
                            html: false,
                            linkify: true,
                            breaks: true,
                            highlight: function(str, lang) {
                                // Use highlight.js if available and language is supported
                                if (lang && window.hljs && window.hljs.getLanguage(lang)) {
                                    try {
                                        return window.hljs.highlight(str, { language: lang }).value;
                                    } catch (e) {
                                        console.warn("[YMM] highlight failed for", lang, e);
                                    }
                                }
                                // Auto-detect language if no specific language given
                                if (window.hljs) {
                                    try {
                                        return window.hljs.highlightAuto(str).value;
                                    } catch (e) {
                                        console.warn("[YMM] highlightAuto failed", e);
                                    }
                                }
                                // Fallback: escape HTML
                                return str.replace(/&/g, "&amp;")
                                          .replace(/</g, "&lt;")
                                          .replace(/>/g, "&gt;");
                            }
                        });
                        console.log("[YMM] markdown-it initialized with highlight support");
                    } else {
                        console.error("[YMM] markdown-it library not loaded!");
                        window.md = { render: function(text) { return escapeHtml(text); } };
                    }
                    
                    // Step 2: Configure highlight.js
                    if (window.hljs) {
                        window.hljs.configure({ ignoreUnescapedHTML: true });
                        console.log("[YMM] highlight.js configured");
                    } else {
                        console.warn("[YMM] highlight.js not loaded - syntax highlighting disabled");
                    }
                    
                    // Step 3: Install custom fence renderer for language labels and copy button
                    if (window.md && window.md.renderer) {
                        // Track code blocks for copy functionality
                        // Each code block gets a unique ID so the copy button knows which code to copy
                        var codeBlockCounter = 0;
                        
                        window.md.renderer.rules.fence = function(tokens, idx, options, env, self) {
                            var token = tokens[idx];
                            var rawCode = token.content || "";
                            var rawLang = (token.info || "").trim().toLowerCase();
                            var langLabel = rawLang ? rawLang.toUpperCase() : "CODE";
                            var blockId = "code-block-" + (codeBlockCounter++);
                            
                            // Use markdown-it's highlight function (set up in Step 1)
                            var highlightedCode = "";
                            if (options.highlight) {
                                highlightedCode = options.highlight(rawCode, rawLang) || escapeHtml(rawCode);
                            } else {
                                highlightedCode = escapeHtml(rawCode);
                            }
                            
                            // Store raw code in a data attribute for copy functionality
                            // We base64 encode to avoid escaping issues with quotes and special chars
                            var encodedCode = btoa(unescape(encodeURIComponent(rawCode)));
                            
                            return '<div class="code-block" id="' + blockId + '" data-code="' + encodedCode + '">' +
                                   '<div class="code-block-header">' +
                                   '<span class="lang-label">' + langLabel + '</span>' +
                                   '<button class="copy-btn" onclick="copyCodeBlock(\'' + blockId + '\')" title="Copy code">' +
                                   '<span class="copy-icon">⧉</span><span class="copy-text">Copy</span>' +
                                   '</button>' +
                                   '</div>' +
                                   '<pre><code class="hljs ' + rawLang + '">' + highlightedCode + '</code></pre>' +
                                   '</div>';
                        };
                        console.log("[YMM] Custom fence renderer installed with copy button");
                    }
                    
                    /**
                     * Copy code from a code block to clipboard.
                     * 
                     * Called when user clicks the Copy button on a code block.
                     * Shows visual feedback (button text changes to "Copied!").
                     * 
                     * @param blockId - The ID of the code-block div containing the code
                     */
                    window.copyCodeBlock = function(blockId) {
                        var block = document.getElementById(blockId);
                        if (!block) {
                            console.error("[YMM] Code block not found:", blockId);
                            return;
                        }
                        
                        // Get raw code from data attribute (base64 encoded)
                        var encodedCode = block.getAttribute("data-code");
                        if (!encodedCode) {
                            console.error("[YMM] No code data found for block:", blockId);
                            return;
                        }
                        
                        // Decode the code
                        var rawCode;
                        try {
                            rawCode = decodeURIComponent(escape(atob(encodedCode)));
                        } catch (e) {
                            console.error("[YMM] Failed to decode code:", e);
                            return;
                        }
                        
                        // Copy to clipboard
                        navigator.clipboard.writeText(rawCode).then(function() {
                            // Show feedback
                            var btn = block.querySelector(".copy-btn");
                            if (btn) {
                                var originalText = btn.innerHTML;
                                btn.innerHTML = '<span class="copy-icon">✓</span><span class="copy-text">Copied!</span>';
                                btn.classList.add("copied");
                                
                                // Reset after 2 seconds
                                setTimeout(function() {
                                    btn.innerHTML = originalText;
                                    btn.classList.remove("copied");
                                }, 2000);
                            }
                            console.log("[YMM] Code copied to clipboard");
                        }).catch(function(err) {
                            console.error("[YMM] Clipboard write failed:", err);
                            // Fallback: try using Kotlin bridge if clipboard API fails
                            // (JCEF may restrict clipboard in some configurations)
                            window.sendToKotlin("COPY:" + rawCode);
                        });
                    };
                    
                    // Step 4: Set up chat functions
                    
                    /**
                     * Add a message to the chat container.
                     * 
                     * @param role - "user" or "assistant"
                     * @param text - Message content (markdown for assistant, plain for user)
                     * @param timestamp - Display timestamp (e.g., "14:30")
                     */
                    window.addMessage = function(role, text, timestamp) {
                        // Hide thinking indicator when assistant message arrives
                        if (role === "assistant") {
                            hideThinking();
                        }
                        
                        var chatContainer = document.getElementById("chatContainer");
                        if (!chatContainer) {
                            console.error("[YMM] chatContainer not found");
                            return;
                        }

                        var msg = document.createElement("div");
                        msg.classList.add("message");
                        msg.classList.add(role === "user" ? "user" : "assistant");

                        // Render markdown for assistant, escape HTML for user
                        var content;
                        if (role === "assistant" && window.md) {
                            content = window.md.render(text || "");
                        } else {
                            content = escapeHtml(text || "");
                        }
                        msg.innerHTML = content;

                        // Add timestamp
                        var tsDiv = document.createElement("div");
                        tsDiv.className = "timestamp";
                        tsDiv.textContent = timestamp || "";
                        msg.appendChild(tsDiv);

                        chatContainer.appendChild(msg);
                        chatContainer.scrollTop = chatContainer.scrollHeight;
                    };

                    /**
                     * Show "Thinking..." indicator while waiting for AI response.
                     * 
                     * Called by Kotlin when a request is sent to the provider.
                     * Automatically hidden when addMessage is called with role="assistant".
                     */
                    window.showThinking = function() {
                        var chatContainer = document.getElementById("chatContainer");
                        if (!chatContainer) return;
                        
                        // Don't add duplicate
                        if (document.getElementById("thinking")) return;
                        
                        var bubble = document.createElement("div");
                        bubble.id = "thinking";
                        bubble.className = "message assistant thinking";
                        bubble.innerHTML = '<span class="thinking-dots">Thinking<span>.</span><span>.</span><span>.</span></span>';
                        chatContainer.appendChild(bubble);
                        chatContainer.scrollTop = chatContainer.scrollHeight;
                    };

                    /**
                     * Hide the "Thinking..." indicator.
                     * 
                     * Called automatically when assistant message arrives,
                     * or explicitly if request fails/is cancelled.
                     */
                    window.hideThinking = function() {
                        var t = document.getElementById("thinking");
                        if (t) t.remove();
                    };
                    
                    /**
                     * Clear all messages from the chat container.
                     * Called when chat state is reset.
                     */
                    window.clearChat = function() {
                        var chatContainer = document.getElementById("chatContainer");
                        if (chatContainer) {
                            chatContainer.innerHTML = '';
                        }
                    };
                    
                    // Step 5: Signal readiness to Kotlin
                    window.YMM_READY = true;
                    window.sendToKotlin('READY');
                    console.log("[YMM] Initialization complete, YMM_READY=true");
                });
                
                /**
                 * Escape HTML special characters to prevent XSS.
                 */
                function escapeHtml(text) {
                    var div = document.createElement('div');
                    div.textContent = text || '';
                    return div.innerHTML;
                }
                
                // Make escapeHtml available globally for the fence renderer
                window.escapeHtml = escapeHtml;
            })();
            </script>
        </body>
        </html>
    """.trimIndent()
    }

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
                    Dev.info(log, "browser.render_incremental",
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

                    // Update the count after rendering
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
     */
    private fun renderMessage(content: String, role: String) {
        Dev.info(log, "browser.render_message",
            "role" to role,
            "content_length" to content.length
        )

        val escapedContent = escapeJS(content)
        val safeRole = if (role == "user") "user" else "assistant"
        val formattedTimestamp = kotlinx.datetime.Clock.System.now()
            .toLocalDateTime(kotlinx.datetime.TimeZone.currentSystemDefault())
            .let { "%02d:%02d".format(it.hour, it.minute) }

        val jsFunction = """
            try {
                addMessage('$safeRole', '$escapedContent', '$formattedTimestamp');
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

    // -------------------------------------------------------------------------
    // CSS Styles
    // -------------------------------------------------------------------------

    /**
     * Base CSS styles for the chat interface.
     *
     * Includes styling for:
     * - Chat container layout
     * - User and assistant message bubbles
     * - Code blocks with language labels
     * - Inline code
     * - Timestamps
     * - Thinking indicator animation
     */
    private fun buildBaseStyles(): String = """
        <style>
          /* Reset and base */
          html, body {
            height: 100%;
            margin: 0;
            overflow: hidden;
          }
          
          body {
              background-color: #1e1e1e;
              color: #ddd;
              font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
              margin: 0;
              padding: 0;
          }
          
          /* Chat container */
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
          
          /* Message bubbles */
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
          
          .message.assistant {
              background-color: #2a2a2a;
              color: #ddd;
              border-radius: 0.75rem;
              padding: 1rem 1.25rem;
              line-height: 1.5;
              margin-top: 0.5rem;
          }

          .message.assistant > *:first-child {
              margin-top: 0;
          }
          
          /* Thinking indicator */
          .message.thinking {
              color: #888;
              font-style: italic;
          }
          
          .thinking-dots span {
              animation: blink 1.4s infinite both;
          }
          
          .thinking-dots span:nth-child(2) {
              animation-delay: 0.2s;
          }
          
          .thinking-dots span:nth-child(3) {
              animation-delay: 0.4s;
          }
          
          @keyframes blink {
              0%, 80%, 100% { opacity: 0; }
              40% { opacity: 1; }
          }
          
          /* Fade in animation */
          @keyframes fadeIn {
              from { opacity: 0; transform: translateY(5px); }
              to { opacity: 1; transform: translateY(0); }
          }
          
          /* Timestamp */
          .timestamp {
              font-size: 0.7rem;
              color: #999;
              margin-top: 0.3rem;
              text-align: right;
          }
          
          /* Code styling */
          pre, code {
              font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
          }
          
          /* Inline code */
          code {
              background: #151515;
              padding: 0.15rem 0.3rem;
              border-radius: 0.25rem;
              font-size: 0.9em;
          }
          
          /* Code blocks */
          pre {
              background: #1b1b1b;
              border: 1px solid #333;
              border-radius: 0.5rem;
              padding: 0.75rem;
              overflow-x: auto;
              margin: 0.5rem 0;
          }
          
          /* Reset code inside pre (don't double-style) */
          pre code {
              background: transparent;
              padding: 0;
              border-radius: 0;
              font-size: inherit;
          }
          
          /* Code block container with language label */
          .code-block {
              position: relative;
              margin: 0.5rem 0;
          }
          
          /* Code block header (contains language label and copy button) */
          .code-block-header {
              display: flex;
              justify-content: space-between;
              align-items: center;
              background: #333;
              border: 1px solid #444;
              border-bottom: none;
              border-radius: 0.5rem 0.5rem 0 0;
              padding: 0.25rem 0.5rem;
          }
          
          /* Language label */
          .lang-label {
              color: #aaa;
              font-size: 0.7rem;
              font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
              text-transform: uppercase;
              letter-spacing: 0.05em;
          }
          
          /* Copy button */
          .copy-btn {
              display: flex;
              align-items: center;
              gap: 0.3rem;
              background-color: transparent;
              color: #888;
              border: 1px solid #555;
              border-radius: 4px;
              padding: 0.2rem 0.5rem;
              font-size: 0.7rem;
              font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
              cursor: pointer;
              transition: all 0.2s ease;
          }
          
          .copy-btn:hover {
              color: #fff;
              border-color: #777;
              background-color: #444;
          }
          
          .copy-btn.copied {
              color: #4ade80;
              border-color: #4ade80;
          }
          
          .copy-icon {
              font-size: 0.8rem;
          }
          
          /* Adjust pre when inside code-block (header present) */
          .code-block pre {
              margin: 0;
              border-radius: 0 0 0.5rem 0.5rem;
              border-top: none;
          }
        </style>
        """.trimIndent()

    // -------------------------------------------------------------------------
    // External Resources (highlight.js, markdown-it)
    // -------------------------------------------------------------------------

    /**
     * Loads highlight.js library and GitHub Dark theme CSS.
     *
     * IMPORTANT: Use the browser build of highlight.js, not the Node.js build.
     * The browser build should start with something like `var hljs=function()...`
     * NOT `var hljs=require("./core")...`
     *
     * Download from: https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.9.0/highlight.min.js
     *
     * Files must exist in src/main/resources/chat-window/
     * - highlight.min.js (browser build, ~70KB)
     * - github-dark.css
     */
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

            Dev.info(log, "browser.resources",
                "highlight_js_size" to highlightJs.length,
                "highlight_css_size" to highlightCss.length
            )

            """
            <!-- highlight.js theme -->
            <style>
            $highlightCss
            </style>
            <!-- highlight.js library -->
            <script>
            $highlightJs
            </script>
            """.trimIndent()
        } catch (e: Exception) {
            Dev.error(log, "browser.resources", e, "failed_to_load" to true)
            "<!-- Error loading highlight resources -->"
        }
    }

    /**
     * Loads markdown-it library.
     *
     * The library must exist at src/main/resources/chat-window/markdown-it.min.js
     *
     * Note: We only load the library here. Initialization happens in the
     * unified initialization script to ensure proper ordering with highlight.js.
     */
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

            Dev.info(log, "browser.resources",
                "markdown_it_size" to markdownIt.length
            )

            """
            <!-- markdown-it library -->
            <script>
            $markdownIt
            </script>
            """.trimIndent()
        } catch (e: Exception) {
            Dev.error(log, "browser.resources", e, "failed_to_load_markdown" to true)
            "<!-- Error loading markdown resources -->"
        }
    }

    // -------------------------------------------------------------------------
    // JavaScript Execution
    // -------------------------------------------------------------------------

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