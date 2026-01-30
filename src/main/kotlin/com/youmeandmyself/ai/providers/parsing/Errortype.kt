package com.youmeandmyself.ai.providers.parsing

/**
 * Classification of error types for user-friendly messaging.
 *
 * Error classification combines multiple signals:
 * - HTTP status code
 * - Error object fields (type, code, status, message)
 * - Message substring heuristics
 *
 * Note: UNKNOWN is a valid outcome even when isError=true.
 * We don't force classification when signals are ambiguous.
 */
enum class ErrorType(val userMessage: String) {
    /** API quota exceeded - user needs to wait or upgrade plan */
    QUOTA_EXCEEDED("API quota exceeded. Please check your plan or wait."),

    /** Too many requests in short time - temporary, retry later */
    RATE_LIMITED("Rate limited. Please wait a moment and try again."),

    /** Account has no credits/balance */
    INSUFFICIENT_FUNDS("Insufficient balance. Please add credits to your account."),

    /** Invalid or expired API key */
    AUTH_FAILED("Authentication failed. Please check your API key in Settings."),

    /** Model ID doesn't exist or isn't available */
    MODEL_NOT_FOUND("Model not found. Please check your model selection in Settings."),

    /** Input prompt too long for model's context window */
    CONTEXT_TOO_LONG("Input too long for this model. Please shorten your message."),

    /** Provider-side error (5xx) */
    SERVER_ERROR("Provider error. Please try again later."),

    /** Got a 200 but couldn't parse the response format */
    PARSE_ERROR("Couldn't parse the response. Click 'View raw' to see details."),

    /** Connection failed, timeout, DNS error, etc. */
    NETWORK_ERROR("Network error. Please check your connection."),

    /** Unclassified error - show raw message */
    UNKNOWN("An unexpected error occurred. Click 'View raw' for details.")
}