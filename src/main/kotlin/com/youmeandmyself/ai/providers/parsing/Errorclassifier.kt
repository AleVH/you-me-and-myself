package com.youmeandmyself.ai.providers.parsing

import kotlinx.serialization.json.*

/**
 * Classifies errors using multiple signals for accurate user messaging.
 *
 * Error classification combines:
 * 1. HTTP status code (necessary but not sufficient)
 * 2. Error object fields (type, code, status, message)
 * 3. Message substring heuristics
 *
 * Why not status code alone?
 * - 429 can mean: quota exceeded, rate limited, or "too many tokens"
 * - 200 can contain embedded error objects
 * - 400 can mean: "model not found", "context length exceeded", "invalid request"
 */
object ErrorClassifier {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Classify an error response.
     *
     * @param rawJson The raw JSON response (may contain error structure)
     * @param httpStatus The HTTP status code (null for network errors)
     * @return The classified error type and extracted message
     */
    fun classify(rawJson: String?, httpStatus: Int?): ClassificationResult {
        // Network error (no response at all)
        if (rawJson == null && httpStatus == null) {
            return ClassificationResult(ErrorType.NETWORK_ERROR, null)
        }

        // Try to parse error structure from JSON
        val errorInfo = rawJson?.let { extractErrorInfo(it) }
        val errorMessage = errorInfo?.message
        val errorCode = errorInfo?.code
        val errorStatus = errorInfo?.status

        // Combine all signals for classification
        val errorType = classifyFromSignals(
            httpStatus = httpStatus,
            errorCode = errorCode,
            errorStatus = errorStatus,
            errorMessage = errorMessage
        )

        return ClassificationResult(errorType, errorMessage)
    }

    /**
     * Extract error information from JSON response.
     */
    private fun extractErrorInfo(rawJson: String): ErrorInfo? {
        return try {
            val obj = json.parseToJsonElement(rawJson) as? JsonObject ?: return null
            val error = obj["error"] ?: return null

            when (error) {
                is JsonObject -> {
                    ErrorInfo(
                        message = error["message"]?.jsonPrimitive?.contentOrNull,
                        code = error["code"]?.let { extractCode(it) },
                        status = error["status"]?.jsonPrimitive?.contentOrNull,
                        type = error["type"]?.jsonPrimitive?.contentOrNull
                    )
                }
                is JsonPrimitive -> {
                    ErrorInfo(message = error.contentOrNull)
                }
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Extract error code (can be int or string depending on provider).
     */
    private fun extractCode(element: JsonElement): String? {
        return when (element) {
            is JsonPrimitive -> {
                if (element.isString) element.content
                else element.intOrNull?.toString()
            }
            else -> null
        }
    }

    /**
     * Classify error from all available signals.
     */
    private fun classifyFromSignals(
        httpStatus: Int?,
        errorCode: String?,
        errorStatus: String?,
        errorMessage: String?
    ): ErrorType {
        val messageLower = errorMessage?.lowercase() ?: ""

        // Check message heuristics first (most specific)
        if (containsQuotaKeywords(messageLower)) {
            return ErrorType.QUOTA_EXCEEDED
        }

        if (containsRateLimitKeywords(messageLower)) {
            return ErrorType.RATE_LIMITED
        }

        if (containsInsufficientFundsKeywords(messageLower)) {
            return ErrorType.INSUFFICIENT_FUNDS
        }

        if (containsAuthKeywords(messageLower)) {
            return ErrorType.AUTH_FAILED
        }

        if (containsModelNotFoundKeywords(messageLower)) {
            return ErrorType.MODEL_NOT_FOUND
        }

        if (containsContextLengthKeywords(messageLower)) {
            return ErrorType.CONTEXT_TOO_LONG
        }

        // Check error status (Gemini uses this)
        when (errorStatus?.uppercase()) {
            "RESOURCE_EXHAUSTED" -> return ErrorType.QUOTA_EXCEEDED
            "PERMISSION_DENIED", "UNAUTHENTICATED" -> return ErrorType.AUTH_FAILED
            "NOT_FOUND" -> return ErrorType.MODEL_NOT_FOUND
            "INVALID_ARGUMENT" -> {
                // Could be context length or other issues
                if (containsContextLengthKeywords(messageLower)) {
                    return ErrorType.CONTEXT_TOO_LONG
                }
            }
        }

        // Fall back to HTTP status
        return when (httpStatus) {
            401, 403 -> ErrorType.AUTH_FAILED
            402 -> ErrorType.INSUFFICIENT_FUNDS
            404 -> ErrorType.MODEL_NOT_FOUND
            429 -> {
                // 429 is ambiguous - default to rate limited
                // (quota keywords would have been caught above)
                ErrorType.RATE_LIMITED
            }
            in 500..599 -> ErrorType.SERVER_ERROR
            else -> ErrorType.UNKNOWN
        }
    }

    // ==================== Keyword Detection ====================

    private fun containsQuotaKeywords(message: String): Boolean {
        return listOf(
            "quota exceeded",
            "exceeded your current quota",
            "insufficient_quota",
            "quota limit",
            "billing"
        ).any { it in message }
    }

    private fun containsRateLimitKeywords(message: String): Boolean {
        return listOf(
            "rate limit",
            "rate_limit",
            "too many requests",
            "request limit",
            "requests per"
        ).any { it in message }
    }

    private fun containsInsufficientFundsKeywords(message: String): Boolean {
        return listOf(
            "insufficient balance",
            "insufficient funds",
            "no credits",
            "payment required",
            "add credits"
        ).any { it in message }
    }

    private fun containsAuthKeywords(message: String): Boolean {
        return listOf(
            "invalid api key",
            "invalid_api_key",
            "unauthorized",
            "authentication failed",
            "invalid credentials",
            "api key not found",
            "incorrect api key"
        ).any { it in message }
    }

    private fun containsModelNotFoundKeywords(message: String): Boolean {
        return listOf(
            "model not found",
            "model does not exist",
            "unknown model",
            "invalid model",
            "no such model"
        ).any { it in message }
    }

    private fun containsContextLengthKeywords(message: String): Boolean {
        return listOf(
            "context length",
            "context_length",
            "maximum tokens",
            "max_tokens",
            "token limit",
            "too long",
            "exceeds the model",
            "maximum context"
        ).any { it in message }
    }
}

/**
 * Extracted error information from JSON.
 */
private data class ErrorInfo(
    val message: String? = null,
    val code: String? = null,
    val status: String? = null,
    val type: String? = null
)

/**
 * Result of error classification.
 */
data class ClassificationResult(
    val errorType: ErrorType,
    val errorMessage: String?
)

// Extensions
private val JsonPrimitive.contentOrNull: String?
    get() = if (isString) content else null

private val JsonPrimitive.intOrNull: Int?
    get() = try { int } catch (e: Exception) { null }