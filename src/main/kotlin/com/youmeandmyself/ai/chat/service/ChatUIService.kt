// File: src/main/kotlin/com/youmeandmyself/ai/chat/service/ChatUIService.kt
package com.youmeandmyself.ai.chat.service

import com.intellij.openapi.project.Project
import kotlinx.coroutines.flow.StateFlow
import javax.swing.JComponent

/**
 * Interface for the chat UI rendering layer.
 *
 * ## Why This Interface Exists
 *
 * This is the **contract** between the orchestration layer (ChatOrchestrator) and
 * whatever technology renders the chat. Currently we have two implementations:
 *
 * - [BrowserChatService]: JCEF-based rich rendering (markdown, syntax highlighting)
 * - [SwingChatService]: Plain Swing fallback (if JCEF isn't available)
 *
 * A future React implementation will also implement this interface (or a bridge
 * adapter that translates to React component calls).
 *
 * ## The Casting Problem This Solves
 *
 * Before this update, `showThinking()`, `hideThinking()`, and `updateMetrics()` only
 * existed on [BrowserChatService]. ChatPanel had to cast:
 *
 * ```kotlin
 * (chatService as? BrowserChatService)?.showThinking()  // 💀 Breaks if service type changes
 * ```
 *
 * Now these are on the interface. Any implementation that doesn't support them
 * (like SwingChatService) can provide no-op implementations. The orchestrator and
 * UI layer never need to know the concrete type.
 *
 * ## UI Swappability
 *
 * This interface uses [JComponent] for `getComponent()` because we're currently
 * in a Swing/JCEF world. When React replaces the UI:
 *
 * 1. React won't use `getComponent()` — it renders in its own JCEF panel
 * 2. React will call the orchestrator directly through a bridge
 * 3. The bridge translates ChatResult → React component state
 * 4. This interface may evolve into a platform-agnostic version without JComponent
 *
 * For now, JComponent is the right abstraction — it works for both Swing and JCEF.
 *
 * ## Thread Safety
 *
 * All methods should be safe to call from any thread. Implementations handle
 * their own EDT dispatching (e.g., `SwingUtilities.invokeLater`).
 */
interface ChatUIService {

    // ── State Access ─────────────────────────────────────────────────────

    /** Observable list of all chat messages. UI layers can collect this flow. */
    val messages: StateFlow<List<ChatMessage>>

    /** Whether the AI is currently "typing" (waiting for a response). */
    val isTyping: StateFlow<Boolean>

    // ── UI Component ─────────────────────────────────────────────────────

    /**
     * Get the root Swing component for embedding in a tool window.
     *
     * Returns the component that should be added to the IntelliJ panel layout.
     * For BrowserChatService, this is the JCEF browser panel.
     * For SwingChatService, this is a JScrollPane with the text area.
     */
    fun getComponent(): JComponent

    // ── Message Operations ───────────────────────────────────────────────

    /**
     * Display a user message in the chat.
     *
     * Called by the UI layer after the user submits their input.
     * The message appears immediately — it doesn't wait for the AI response.
     *
     * @param content The user's message text
     * @param contextAttached True if IDE context was attached to this message
     */
    fun sendUserMessage(content: String, contextAttached: Boolean = false)

    /**
     * Display an AI assistant response in the chat.
     *
     * Called after the orchestrator returns a ChatResult. For browser-based
     * implementations, this renders markdown, syntax highlighting, etc.
     *
     * Automatically hides the thinking indicator (if shown).
     *
     * @param content The AI response text (may contain markdown)
     * @param providerId Which provider generated this (for display badges)
     * @param isError True if this is an error message (renders with error styling)
     */
    fun addAssistantMessage(content: String, providerId: String? = null, isError: Boolean = false)

    /**
     * Display a system notification (not an AI response).
     *
     * Used for:
     * - Context gathering status ("Context ready in 450ms")
     * - Correction flow hints ("Type /correct to fix")
     * - Error states from the plugin (not from the AI provider)
     *
     * Rendered distinctly from assistant messages (typically smaller, different color).
     *
     * @param content The notification text
     * @param type The notification category (INFO, WARNING, ERROR)
     */
    fun addSystemMessage(content: String, type: SystemMessageType = SystemMessageType.INFO)

    /**
     * Set the typing indicator state.
     *
     * @param typing True to show typing indicator, false to hide
     */
    fun setTyping(typing: Boolean)

    /**
     * Clear all messages from the chat display.
     *
     * Called when starting a new conversation or switching tabs.
     * Resets both the message list and any rendering state.
     */
    fun clearChat()

    // ── Thinking Indicator ───────────────────────────────────────────────

    /**
     * Show a "Thinking..." indicator in the chat.
     *
     * Call this immediately before sending a request to the AI provider.
     * Gives the user visual feedback that their request is being processed.
     *
     * The indicator is automatically hidden when [addAssistantMessage] is called.
     * If the request fails or is cancelled, call [hideThinking] explicitly.
     *
     * Implementations that don't support animated indicators can use [setTyping]
     * as a simpler alternative.
     */
    fun showThinking()

    /**
     * Hide the "Thinking..." indicator.
     *
     * Normally called automatically when the assistant message arrives.
     * Call explicitly if:
     * - The request fails or throws an exception
     * - The user cancels the request
     * - Any other case where no assistant message will follow
     */
    fun hideThinking()

    // ── Metrics ──────────────────────────────────────────────────────────

    /**
     * Update the token usage metrics display.
     *
     * Called after receiving a response to show the user what this exchange cost.
     * Implementations can show this in a top bar, footer, or overlay — the
     * interface doesn't prescribe the visual treatment.
     *
     * @param model The model name (e.g., "gpt-4o", "claude-3.5-sonnet")
     * @param promptTokens Tokens used for the prompt (input)
     * @param completionTokens Tokens used for the response (output)
     * @param totalTokens Total tokens (prompt + completion)
     * @param estimatedCost Human-readable cost estimate (e.g., "$0.003"). Null if unavailable.
     */
    fun updateMetrics(
        model: String?,
        promptTokens: Int?,
        completionTokens: Int?,
        totalTokens: Int?,
        estimatedCost: String?
    )

    // ── Lifecycle ────────────────────────────────────────────────────────

    /**
     * Release all resources held by this service.
     *
     * Called when the chat panel is disposed (tab closed, project closed).
     * Implementations should clean up browser instances, listeners, etc.
     */
    fun dispose()
}

/**
 * Factory for creating ChatUIService instances.
 *
 * Used by the plugin framework to create service instances per-project.
 * The factory allows switching implementations without changing callers.
 */
interface ChatUIServiceFactory {
    /**
     * Create a new ChatUIService for the given project.
     *
     * @param project The IntelliJ project context
     * @return A new ChatUIService instance
     */
    fun create(project: Project): ChatUIService
}