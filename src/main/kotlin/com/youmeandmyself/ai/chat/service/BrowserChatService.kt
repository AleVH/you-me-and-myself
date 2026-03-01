package com.youmeandmyself.ai.chat.service

import com.intellij.openapi.project.Project
import com.youmeandmyself.ai.chat.components.ChatBrowserComponent
import com.youmeandmyself.dev.Dev
import kotlinx.coroutines.flow.StateFlow
import javax.swing.JComponent

/**
 * Browser-based implementation of [ChatUIService].
 *
 * Uses JCEF (Java Chromium Embedded Framework) to render chat messages
 * with full markdown support, syntax highlighting, and modern styling.
 *
 * Falls back gracefully if browser initialization fails — the caller
 * (ChatPanel) handles switching to SwingChatService.
 *
 * ## Thinking Indicator
 *
 * Call [showThinking] when sending a request to the AI provider.
 * The indicator is automatically hidden when [addAssistantMessage] is called,
 * but you should call [hideThinking] explicitly if the request fails or is cancelled.
 *
 * ## Why Not Just Use setTyping()?
 *
 * [setTyping] updates the ChatState observable (for Swing/data binding).
 * [showThinking] / [hideThinking] drive the browser's animated indicator
 * (a pulsing dot or spinner in the chat window). Both exist because:
 * - The browser indicator is richer (animated, positioned in the message flow)
 * - The typing state is observable (for other UI elements like status bars)
 * - They can be used independently or together
 *
 * ## Thread Safety
 *
 * All methods are safe to call from any thread. The browser component handles
 * its own threading for JavaScript execution. Message state updates go through
 * ChatState which is thread-safe.
 */
class BrowserChatService(private val project: Project) : ChatUIService {
    private val chatState = ChatState()
    private val browserComponent = ChatBrowserComponent(project, chatState)
    private val log = Dev.logger(BrowserChatService::class.java)

    /**
     * Callback invoked when the browser component finishes initializing.
     *
     * JCEF initialization is async — the browser needs to load HTML,
     * initialize JavaScript, and report ready. This callback tells the
     * caller (ChatPanel) when it's safe to start rendering messages.
     *
     * The callback receives `true` if initialization succeeded, `false` if it failed.
     */
    private var onReadyCallback: ((Boolean) -> Unit)? = null

    init {
        Dev.info(log, "browserservice.init", "start" to true)
        // Wire the readiness callback to the browser component.
        // When the browser's JavaScript reports ready, this propagates to our caller.
        browserComponent.setOnReady { isReady ->
            Dev.info(log, "browserservice.ready_callback",
                "received_from_browser" to true,
                "isReady" to isReady
            )
            onReadyCallback?.invoke(isReady)
        }
        Dev.info(log, "browserservice.init", "callback_wired" to true)
    }

    /**
     * Register a callback for browser readiness notification.
     *
     * Must be called before the browser starts loading (typically in ChatPanel's init).
     * The callback fires once when the browser either succeeds or fails to initialize.
     */
    fun setOnReady(callback: (Boolean) -> Unit) {
        Dev.info(log, "browserservice.set_ready", "callback_set" to true)
        onReadyCallback = callback
    }

    // ── State Access (from ChatUIService interface) ──────────────────────

    /** Observable message list. Collectors see new messages as they're added. */
    override val messages: StateFlow<List<ChatMessage>> = chatState.messages

    /** Observable typing state. True while waiting for an AI response. */
    override val isTyping: StateFlow<Boolean> = chatState.isTyping

    // ── UI Component ─────────────────────────────────────────────────────

    /**
     * Returns the JCEF browser panel for embedding in the tool window.
     *
     * This is the root component that renders the entire chat interface:
     * messages, thinking indicator, metrics bar, scroll position, etc.
     */
    override fun getComponent(): JComponent = browserComponent.component

    // ── Message Operations ───────────────────────────────────────────────

    /**
     * Display a user message in the chat.
     *
     * Creates a [UserMessage] with a unique ID and timestamp, adds it to
     * the state (triggering observers), and scrolls the browser to the bottom
     * so the new message is visible.
     */
    override fun sendUserMessage(content: String, contextAttached: Boolean) {
        val message = UserMessage(
            id = ChatState.generateId(),
            timestamp = kotlinx.datetime.Clock.System.now(),
            content = content,
            contextAttached = contextAttached
        )
        chatState.addMessage(message)
        browserComponent.scrollToBottom()
    }

    /**
     * Display an AI assistant response in the chat.
     *
     * Handles the edge case of blank responses: if the provider returned
     * nothing but it's not marked as an explicit error, shows a friendly
     * fallback message instead of an empty bubble.
     *
     * The thinking indicator is automatically hidden by the browser's
     * JavaScript when an assistant message arrives (the addMessage JS
     * function handles this).
     */
    override fun addAssistantMessage(content: String, providerId: String?, isError: Boolean) {
        // Replace blank non-error responses with a friendly message.
        // This handles edge cases where the provider returns empty content
        // without signaling an error (some providers do this on content filters).
        val safeContent = if (content.isBlank() && !isError) {
            "⚠️ No response received from provider. The service may be temporarily unavailable."
        } else {
            content
        }

        val message = AssistantMessage(
            id = ChatState.generateId(),
            timestamp = kotlinx.datetime.Clock.System.now(),
            content = safeContent,
            providerId = providerId,
            isError = isError || content.isBlank()
        )

        chatState.addMessage(message)
        browserComponent.scrollToBottom()

        // Note: hideThinking() is called automatically by the JS addMessage function
        // when role="assistant", so we don't need to call it here explicitly.
    }

    /**
     * Display a system notification to the user (not an AI response).
     *
     * Called by the orchestrator/UI for:
     * - Context gathering status ("Context ready in 450ms")
     * - Correction flow hints ("Type /correct to fix")
     * - Error states that aren't from the AI provider
     *
     * Rendered with system styling (smaller, different color than assistant messages).
     */
    override fun addSystemMessage(content: String, type: SystemMessageType) {
        val message = SystemMessage(
            id = ChatState.generateId(),
            timestamp = kotlinx.datetime.Clock.System.now(),
            content = content,
            type = type
        )
        chatState.addMessage(message)
        browserComponent.scrollToBottom()
    }

    /**
     * Update the observable typing state.
     *
     * This drives data-bound UI elements (e.g., status bar indicators).
     * For the animated browser indicator, use [showThinking] / [hideThinking].
     */
    override fun setTyping(typing: Boolean) {
        chatState.setTyping(typing)
    }

    /**
     * Clear all messages and reset the browser rendering state.
     *
     * Called when starting a new conversation or switching to a different tab.
     * Resets the incremental render counter so the browser knows to start fresh.
     */
    override fun clearChat() {
        chatState.clear()
        // Reset the browser component's render state so it knows to start fresh
        // (the incremental renderer tracks how many messages have been rendered)
        browserComponent.resetRenderState()
    }

    // ── Thinking Indicator (now on ChatUIService interface) ──────────────

    /**
     * Shows a "Thinking..." indicator in the chat browser.
     *
     * Triggers a JavaScript call that renders an animated indicator
     * in the message flow. The indicator sits at the bottom of the chat,
     * giving the user visual feedback that their request is being processed.
     *
     * The indicator is automatically hidden when [addAssistantMessage] is called
     * (handled in the JavaScript side — the addMessage function removes it).
     * If the request fails or is cancelled, call [hideThinking] explicitly.
     */
    override fun showThinking() {
        browserComponent.showThinking()
    }

    /**
     * Hides the "Thinking..." indicator in the chat browser.
     *
     * Normally called automatically when the assistant message arrives
     * (the JS side handles this). Call explicitly if:
     * - The request fails/throws an exception
     * - The user cancels the request
     * - Any other case where no assistant message will be added
     */
    override fun hideThinking() {
        browserComponent.hideThinking()
    }

    // ── Metrics (now on ChatUIService interface) ─────────────────────────

    /**
     * Updates the metrics widget in the browser with token usage data.
     *
     * Called after receiving a response to show the user what this exchange cost.
     * The browser renders this as a top bar or overlay with model name and token counts.
     *
     * @param model The model name displayed to the user
     * @param promptTokens Input token count
     * @param completionTokens Output token count
     * @param totalTokens Total token count (prompt + completion)
     * @param estimatedCost Human-readable cost string (e.g., "$0.003"). Currently unused.
     */
    override fun updateMetrics(
        model: String?,
        promptTokens: Int?,
        completionTokens: Int?,
        totalTokens: Int?,
        estimatedCost: String?
    ) {
        browserComponent.updateMetrics(model, promptTokens, completionTokens, totalTokens, estimatedCost)
    }

    // ── Lifecycle ────────────────────────────────────────────────────────

    /**
     * Release the JCEF browser and all associated resources.
     *
     * Called when the chat panel is disposed. After this, the browser
     * component cannot be used again.
     */
    override fun dispose() {
        browserComponent.dispose()
    }
}