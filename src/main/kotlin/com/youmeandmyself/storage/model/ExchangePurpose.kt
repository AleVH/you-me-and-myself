package com.youmeandmyself.storage.model

/**
 * Defines the intent behind an AI exchange.
 * Used to categorize and filter stored exchanges, and to route
 * exchanges to the correct storage folder (chat/ vs summaries/).
 *
 * ## Code Hierarchy (bottom-up generation chain)
 *
 * METHOD_SUMMARY → CLASS_SUMMARY → FILE_SUMMARY → MODULE_SUMMARY → PROJECT_SUMMARY
 *
 * Each level summarizes its internals and exposes a **contract** upward:
 * - Method: internal logic summary + signature/one-line purpose as contract
 * - Class: how method contracts coordinate + class-level contract
 * - File: how class contracts compose + file-level contract
 * - Module: how file contracts compose + module-level contract
 * - Project: system architecture from module contracts
 *
 * ## Future Consumers
 *
 * The summary engine is general-purpose. Non-code consumers:
 * - PROFILE_SUMMARY: summarization of AI profile YAML (Block 4)
 * - CHAT_SUMMARY: compression of older conversation turns (Phase B)
 *
 * ## Storage Routing
 *
 * All non-CHAT purposes route to summaries/ folder via [isSummaryType].
 * CHAT_SUMMARY routes to summaries/ (not chat/) because it IS a summary —
 * the storage location reflects what the exchange IS, not what its input was.
 * The original chat exchanges remain in chat/ JSONL (source of truth, never lost).
 */
enum class ExchangePurpose {

    /** Direct conversation between user and AI in the chat window. */
    CHAT,

    // ── Code Hierarchy (bottom-up) ──────────────────────────────────

    /** AI-generated summary of a single method's logic + contract.
     *  Leaf node in the hierarchy — generated directly from source code. */
    METHOD_SUMMARY,

    /** AI-generated summary of a class, built from method contracts.
     *  Describes how methods coordinate, shared state, dependencies. */
    CLASS_SUMMARY,

    /** AI-generated summary of a single file's content, built from class contracts.
     *  Describes how classes compose, file purpose. */
    FILE_SUMMARY,

    /** AI-generated summary aggregating multiple file summaries into module-level understanding.
     *  Describes module purpose, boundaries, external interface.
     *  Gated behind user confirmation (can cascade into many sub-summaries). */
    MODULE_SUMMARY,

    /** AI-generated summary of the entire project, built from module contracts.
     *  Describes system architecture, entry points, data flows.
     *  Gated behind user confirmation (can cascade into many sub-summaries). */
    PROJECT_SUMMARY,

    // ── Future Consumers (placeholder — not fully wired yet) ────────

    /** Summarization of user's AI profile YAML.
     *  Triggered on profile edit. Uses profile-specific prompt template.
     *  Not implemented yet — see Block 4 (Profile system).
     *  @see com.youmeandmyself.summary.consumers.ProfileSummarizer */
    PROFILE_SUMMARY,

    /** Compression of older conversation turns to save context window tokens.
     *  The original exchanges persist in JSONL (source of truth — never lost).
     *  The summary is a context window optimization overlay: the AI sees the
     *  summary instead of raw turns, but the user can still browse all turns.
     *  Not implemented yet — see Phase B (Conversation Architecture).
     *  @see com.youmeandmyself.summary.consumers.ChatHistorySummarizer */
    CHAT_SUMMARY;

    /**
     * Whether this exchange is a summary type (routes to summaries/ folder).
     *
     * Every non-CHAT purpose is a summary type. This includes CHAT_SUMMARY,
     * which routes to summaries/ because it IS a summary (the original chat
     * exchanges remain in chat/ JSONL).
     */
    val isSummaryType: Boolean get() = this != CHAT

    /**
     * Whether this is a code hierarchy summary (part of the bottom-up chain).
     * Useful for filtering code summaries from other summary types.
     */
    val isCodeSummary: Boolean get() = this in CODE_SUMMARY_PURPOSES

    /**
     * Whether this purpose requires user confirmation before generation.
     * Module and project summaries can cascade into many sub-summaries.
     */
    val requiresConfirmation: Boolean get() = this == MODULE_SUMMARY || this == PROJECT_SUMMARY

    companion object {
        /** All code hierarchy purposes, ordered bottom-up. */
        val CODE_SUMMARY_PURPOSES = listOf(
            METHOD_SUMMARY, CLASS_SUMMARY, FILE_SUMMARY, MODULE_SUMMARY, PROJECT_SUMMARY
        )

        /** The parent purpose in the hierarchy, or null if top-level. */
        fun parentOf(purpose: ExchangePurpose): ExchangePurpose? = when (purpose) {
            METHOD_SUMMARY -> CLASS_SUMMARY
            CLASS_SUMMARY -> FILE_SUMMARY
            FILE_SUMMARY -> MODULE_SUMMARY
            MODULE_SUMMARY -> PROJECT_SUMMARY
            else -> null
        }

        /** The child purpose in the hierarchy, or null if leaf-level. */
        fun childOf(purpose: ExchangePurpose): ExchangePurpose? = when (purpose) {
            PROJECT_SUMMARY -> MODULE_SUMMARY
            MODULE_SUMMARY -> FILE_SUMMARY
            FILE_SUMMARY -> CLASS_SUMMARY
            CLASS_SUMMARY -> METHOD_SUMMARY
            else -> null
        }
    }
}