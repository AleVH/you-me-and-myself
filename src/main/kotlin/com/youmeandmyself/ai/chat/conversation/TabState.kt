// File: src/main/kotlin/com/youmeandmyself/ai/chat/conversation/TabState.kt
package com.youmeandmyself.ai.chat.conversation

import com.youmeandmyself.ai.chat.service.ChatState

/**
 * State for a single conversation tab in the Chat panel.
 *
 * Each open tab tracks its own conversation, messages, scroll position,
 * and loading state. The JCEF browser is shared — when switching tabs,
 * the browser content is cleared and re-rendered from the active tab's state.
 *
 * ## Lifecycle
 *
 * 1. Created when user clicks [+] or reopens a conversation from Library
 * 2. [conversationId] is null until the first message is sent (then a Conversation is created)
 * 3. On tab switch: scroll position saved, content cleared, new tab's content rendered
 * 4. On tab close: state discarded (conversation persists in DB, reopenable from Library)
 *
 * @property id Unique tab identifier (not the same as conversationId — tabs are ephemeral, conversations are persistent)
 * @property conversationId The persistent conversation this tab represents. Null for a fresh "New Chat" tab.
 * @property title Display title for the tab. "New Chat" initially, then auto-generated from first prompt.
 * @property chatState The message state for this tab (tracks messages and typing indicator)
 * @property scrollExchangeId Which exchange was visible when the user switched away. Null = bottom.
 * @property scrollPixelOffset Pixel offset within the visible exchange for precise scroll restoration.
 * @property loadedExchangeCount How many exchanges are currently rendered in the browser for this tab.
 * @property isModified True if the tab has unsent input text (for close confirmation).
 */
data class TabState(
    val id: String,
    val conversationId: String? = null,
    val title: String = "New Chat",
    val chatState: ChatState = ChatState(),
    val scrollExchangeId: String? = null,
    val scrollPixelOffset: Int? = null,
    val loadedExchangeCount: Int = 0,
    val isModified: Boolean = false
) {
    /**
     * Short title for tab display, truncated with ellipsis.
     */
    fun tabDisplayTitle(maxLength: Int = 25): String {
        return if (title.length <= maxLength) title else title.take(maxLength - 1) + "…"
    }

    /**
     * Whether this tab represents a persisted conversation (has been saved to DB).
     */
    val isPersisted: Boolean get() = conversationId != null

    /**
     * Whether this is a fresh empty tab (no conversation, no messages).
     */
    val isEmpty: Boolean get() = conversationId == null && chatState.messages.value.isEmpty()
}