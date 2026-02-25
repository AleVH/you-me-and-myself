// File: src/main/kotlin/com/youmeandmyself/storage/model/Conversation.kt
package com.youmeandmyself.storage.model

import java.time.Instant

/**
 * A named, persistent conversation — a sequence of exchanges with an AI provider.
 *
 * Conversations are the grouping unit that connects individual exchanges into
 * a coherent multi-turn dialogue. They enable:
 * - Chat tabs: each tab corresponds to one conversation
 * - Library browsing: conversations appear as expandable groups
 * - History replay: reopening a conversation restores AI context
 *
 * ## Lifecycle
 *
 * 1. Created when the user sends the first message in a new chat tab
 * 2. Title auto-generated from the first prompt (editable later)
 * 3. Updated on every new exchange (timestamp, turn count, provider info)
 * 4. Soft-closed via [isActive] = false (archiving, never deleted)
 *
 * ## History Token Cap
 *
 * Each conversation can override the global max history tokens setting.
 * This allows a user to have a global cap of 30k tokens but let one specific
 * deep architecture discussion run unlimited — without changing settings for all chats.
 *
 * @property id Unique identifier (UUID)
 * @property projectId Links to the project this conversation belongs to
 * @property title Display title. Auto-generated from first prompt, user-editable.
 * @property createdAt When the conversation started
 * @property updatedAt When the last exchange was added (used for sorting)
 * @property providerId Last provider used in this conversation
 * @property modelId Last model used in this conversation
 * @property turnCount Number of exchanges (user→assistant pairs)
 * @property isActive True = active conversation, false = archived
 * @property maxHistoryTokensOverride Per-conversation override for history token cap.
 *           null = inherit global setting,
 *           -1 = explicitly unlimited (no cap),
 *           >0 = specific cap for this conversation only
 */
data class Conversation(
    val id: String,
    val projectId: String,
    val title: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val providerId: String? = null,
    val modelId: String? = null,
    val turnCount: Int = 0,
    val isActive: Boolean = true,
    val maxHistoryTokensOverride: Int? = null
) {
    /**
     * Display-safe title: returns the title or a fallback if null/blank.
     */
    val displayTitle: String
        get() = title?.takeIf { it.isNotBlank() } ?: "New Chat"

    /**
     * Short title for tab display (truncated to maxLength).
     */
    fun tabTitle(maxLength: Int = 25): String {
        val t = displayTitle
        return if (t.length <= maxLength) t else t.take(maxLength - 1) + "…"
    }

    companion object {
        /**
         * Generate a title from the user's first prompt.
         *
         * Strips context blocks, code fences, and system annotations,
         * then takes the first 80 characters trimmed to the last complete word.
         */
        fun titleFromPrompt(prompt: String): String {
            // Strip context blocks that the plugin prepends
            val cleaned = prompt
                .replace(Regex("""\[Context[^\]]*]"""), "")
                .replace(Regex("""### Files.*$""", RegexOption.DOT_MATCHES_ALL), "")
                .replace(Regex("""```[\s\S]*?```"""), "")
                .replace(Regex("""\[File:[^\]]*]"""), "")
                .lines()
                .filter { line ->
                    !line.trimStart().startsWith("//") &&
                            !line.trimStart().startsWith("Language:") &&
                            !line.trimStart().startsWith("Frameworks:") &&
                            !line.trimStart().startsWith("Build:") &&
                            !line.trimStart().startsWith("Modules:") &&
                            !line.trimStart().startsWith("Files:")
                }
                .joinToString(" ")
                .replace(Regex("\\s+"), " ")
                .trim()

            if (cleaned.isBlank()) return "New Chat"

            return if (cleaned.length <= 80) {
                cleaned
            } else {
                val truncated = cleaned.take(80)
                val lastSpace = truncated.lastIndexOf(' ')
                if (lastSpace > 40) truncated.take(lastSpace) else truncated
            }
        }
    }
}