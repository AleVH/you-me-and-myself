package com.youmeandmyself.storage.search

/**
 * Defines where to search within an exchange's content.
 *
 * REQUEST_ONLY - Search only in the user's input/prompt
 * RESPONSE_ONLY - Search only in the AI's response
 * BOTH - Search in both request and response
 */
enum class SearchScope {
    REQUEST_ONLY,
    RESPONSE_ONLY,
    BOTH
}