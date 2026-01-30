package com.youmeandmyself.storage.model

/**
 * Raw wire data from an AI provider response.
 *
 * This captures exactly what came back from the HTTP call, before any
 * parsing or extraction. Critical for:
 * - Debugging provider issues
 * - Future re-extraction with improved algorithms
 * - Analyzing response structures for unknown providers
 *
 * @property json The complete, unmodified JSON response body
 * @property httpStatus The HTTP status code (e.g., 200, 429, 500)
 */
data class ExchangeRawResponse(
    val json: String,
    val httpStatus: Int? = null
)