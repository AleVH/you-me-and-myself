package com.youmeandmyself.ai.providers.parsing

/**
 * The result of parsing an AI provider response.
 *
 * This is a lightweight object that does NOT contain the raw JSON - by this point,
 * the raw response has already been persisted to storage. Instead, it contains:
 * - Extracted content (rawText)
 * - User-friendly display text (displayText)
 * - Error information if applicable
 * - Reference to stored raw data (exchangeId)
 * - Metadata about how we parsed it
 *
 * ## Key Design Decisions
 *
 * 1. **rawText vs displayText**: We keep these separate because storage/search needs
 *    the pure extracted content, while the UI might need a friendlier version.
 *
 * 2. **No rawJson**: The raw response is persisted immediately in the provider,
 *    before parsing. Use exchangeId to retrieve it if needed.
 *
 * 3. **Streaming fields**: Even though streaming isn't implemented yet, we include
 *    the fields now to avoid a painful redesign later.
 *
 * @property rawText The actual extracted model text (when success) - pure, unmodified
 * @property displayText What to show in chat bubble (may include friendly wrapper)
 * @property errorMessage Extracted provider error message (when error) - pure, unmodified
 * @property isError True if this response represents an error
 * @property errorType Classification of the error (if isError=true)
 * @property exchangeId Reference to stored raw data for "View raw" feature
 * @property metadata Additional parsing metadata
 * @property isPartial True if this is a partial/streaming response
 * @property deltaText Incremental content for streaming (this chunk only)
 * @property streamEvent Type of streaming event
 */
data class ParsedResponse(
    // Content
    val rawText: String?,
    val displayText: String,
    val errorMessage: String? = null,

    // Error state
    val isError: Boolean,
    val errorType: ErrorType? = null,

    // Reference to storage
    val exchangeId: String,

    // Metadata
    val metadata: ParseMetadata,

    // Streaming support (future)
    val isPartial: Boolean = false,
    val deltaText: String? = null,
    val streamEvent: StreamEvent = StreamEvent.COMPLETE
) {
    companion object {
        /**
         * Create a successful response.
         */
        fun success(
            rawText: String,
            exchangeId: String,
            metadata: ParseMetadata,
            displayText: String? = null
        ): ParsedResponse = ParsedResponse(
            rawText = rawText,
            displayText = displayText ?: rawText,
            errorMessage = null,
            isError = false,
            errorType = null,
            exchangeId = exchangeId,
            metadata = metadata
        )

        /**
         * Create an error response.
         */
        fun error(
            errorMessage: String?,
            errorType: ErrorType,
            exchangeId: String,
            metadata: ParseMetadata,
            displayText: String? = null
        ): ParsedResponse = ParsedResponse(
            rawText = null,
            displayText = displayText ?: errorType.userMessage,
            errorMessage = errorMessage,
            isError = true,
            errorType = errorType,
            exchangeId = exchangeId,
            metadata = metadata
        )
    }
}

/**
 * Streaming event types for future implementation.
 */
enum class StreamEvent {
    /** Partial content, more coming */
    DELTA,

    /** Stream complete */
    COMPLETE,

    /** Stream interrupted by error */
    ERROR
}