package com.youmeandmyself.storage.model

/**
 * Defines the intent behind an AI exchange.
 * Used to categorize and filter stored exchanges.
 *
 * CHAT - Direct conversation between user and AI in the chat window
 * FILE_SUMMARY - AI-generated summary of a single file's content
 * MODULE_SUMMARY - AI-generated summary aggregating multiple file summaries
 */
enum class ExchangePurpose {
    CHAT,
    FILE_SUMMARY,
    MODULE_SUMMARY;

    /** Whether this exchange is a summary type (routes to summaries/ folder). */
    val isSummaryType: Boolean get() = this != CHAT
}