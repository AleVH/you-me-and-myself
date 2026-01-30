package com.youmeandmyself.ai.providers.parsing

/**
 * Main orchestrator for parsing AI provider responses.
 *
 * This class coordinates:
 * 1. Error detection (is this an error response?)
 * 2. Format detection (what schema is this?)
 * 3. Content extraction (get the actual text)
 * 4. Fallback chain (heuristics if known formats don't match)
 * 5. User correction support (provide candidates when uncertain)
 *
 * ## Three Scenarios
 *
 * 1. **Known format**: Schema matches OpenAI/Gemini/Anthropic exactly
 *    → Extract content, high confidence, no correction needed
 *
 * 2. **Heuristic match**: JSON walk found plausible content
 *    → Show content but include candidates for user correction
 *    → If confidence is MEDIUM+, show with "Not right?" button
 *    → If confidence is LOW/NONE, may want to ask immediately
 *
 * 3. **Parse failure**: No content found
 *    → Return error, include candidates if any found
 *
 * ## Important: Storage happens BEFORE this is called
 *
 * The raw JSON should already be persisted by GenericLlmProvider before
 * calling this parser. The exchangeId parameter is a reference to that
 * stored data, used for "View raw" functionality.
 */
object ResponseParser {

    /**
     * Parse a raw API response.
     *
     * @param rawJson The raw JSON response from the provider
     * @param httpStatus The HTTP status code (null for network errors)
     * @param exchangeId Reference to the stored raw data
     * @return Parsed response with extracted content or error information
     */
    fun parse(
        rawJson: String?,
        httpStatus: Int?,
        exchangeId: String
    ): ParsedResponse {

        // Handle null/empty response
        if (rawJson.isNullOrBlank()) {
            return handleEmptyResponse(httpStatus, exchangeId)
        }

        // Check if HTTP status indicates error
        val isHttpError = httpStatus != null && (httpStatus < 200 || httpStatus >= 300)

        // Detect format
        val detection = FormatDetector.detect(rawJson)

        // If detected as error, classify and return error response
        if (detection.schema == DetectionSchema.ERROR) {
            return handleErrorResponse(rawJson, httpStatus, exchangeId, detection)
        }

        // If HTTP error but no error structure detected, still treat as error
        if (isHttpError) {
            return handleHttpError(rawJson, httpStatus, exchangeId)
        }

        // Try to extract content using detected schema (Scenario 1: Known format)
        if (detection.schema != DetectionSchema.UNKNOWN && detection.confidence != Confidence.LOW) {
            val content = ContentExtractor.extract(rawJson, detection.schema)

            if (!content.isNullOrBlank()) {
                return ParsedResponse.success(
                    rawText = content,
                    exchangeId = exchangeId,
                    metadata = ParseMetadata(
                        parseStrategy = detection.schema.toParseStrategy(),
                        confidence = detection.confidence,
                        detectedSchema = detection.schema.toMetadataSchema(),
                        contentPath = detection.contentPath,
                        tokenUsage = detection.tokenUsage?.toMetadataTokenUsage(),
                        requestId = detection.requestId,
                        finishReason = detection.finishReason
                    )
                )
            }
        }

        // Fallback A: Try JSON walk heuristic (Scenario 2: Heuristic match)
        val heuristicResult = JsonWalkHeuristic.findCandidates(rawJson)
        if (heuristicResult != null && heuristicResult.content.isNotBlank()) {
            return ParsedResponse.success(
                rawText = heuristicResult.content,
                exchangeId = exchangeId,
                metadata = ParseMetadata(
                    parseStrategy = ParseStrategy.HEURISTIC_JSON_WALK,
                    confidence = heuristicResult.confidence,
                    contentPath = heuristicResult.path,
                    candidates = heuristicResult.allCandidates // Include ALL candidates for correction
                ),
                displayText = heuristicResult.content
            )
        }

        // Fallback B: Check if it's plain text (not JSON)
        if (!looksLikeJson(rawJson)) {
            return handlePlainTextResponse(rawJson, httpStatus, exchangeId)
        }

        // Scenario 3: Parse failure - include whatever candidates we found
        val failedCandidates = heuristicResult?.allCandidates ?: emptyList()

        return ParsedResponse.error(
            errorMessage = "Could not extract content from response",
            errorType = ErrorType.PARSE_ERROR,
            exchangeId = exchangeId,
            metadata = ParseMetadata(
                parseStrategy = ParseStrategy.FAILED,
                confidence = Confidence.NONE,
                candidates = failedCandidates
            )
        )
    }

    /**
     * Handle empty or null response.
     */
    private fun handleEmptyResponse(httpStatus: Int?, exchangeId: String): ParsedResponse {
        val errorType = when {
            httpStatus == null -> ErrorType.NETWORK_ERROR
            httpStatus >= 500 -> ErrorType.SERVER_ERROR
            httpStatus == 401 || httpStatus == 403 -> ErrorType.AUTH_FAILED
            else -> ErrorType.UNKNOWN
        }

        return ParsedResponse.error(
            errorMessage = "Empty response from provider",
            errorType = errorType,
            exchangeId = exchangeId,
            metadata = ParseMetadata(
                parseStrategy = ParseStrategy.FAILED,
                confidence = Confidence.HIGH
            )
        )
    }

    /**
     * Handle response with error structure detected.
     */
    private fun handleErrorResponse(
        rawJson: String,
        httpStatus: Int?,
        exchangeId: String,
        detection: DetectionResult
    ): ParsedResponse {
        // Classify the error using multiple signals
        val classification = ErrorClassifier.classify(rawJson, httpStatus)

        // Extract the actual error message
        val errorMessage = ContentExtractor.extractErrorMessage(rawJson)
            ?: classification.errorMessage
            ?: "Unknown error"

        return ParsedResponse.error(
            errorMessage = errorMessage,
            errorType = classification.errorType,
            exchangeId = exchangeId,
            metadata = ParseMetadata(
                parseStrategy = ParseStrategy.ERROR_DETECTED,
                confidence = detection.confidence,
                detectedSchema = DetectedSchema.UNKNOWN
            )
        )
    }

    /**
     * Handle HTTP error without detected error structure.
     */
    private fun handleHttpError(
        rawJson: String,
        httpStatus: Int?,
        exchangeId: String
    ): ParsedResponse {
        val classification = ErrorClassifier.classify(rawJson, httpStatus)

        return ParsedResponse.error(
            errorMessage = classification.errorMessage ?: "HTTP error: $httpStatus",
            errorType = classification.errorType,
            exchangeId = exchangeId,
            metadata = ParseMetadata(
                parseStrategy = ParseStrategy.ERROR_DETECTED,
                confidence = Confidence.MEDIUM
            )
        )
    }

    /**
     * Handle response that doesn't look like JSON.
     */
    private fun handlePlainTextResponse(
        rawText: String,
        httpStatus: Int?,
        exchangeId: String
    ): ParsedResponse {
        // If HTTP status is success, treat the text as content
        if (httpStatus == null || (httpStatus in 200..299)) {
            return ParsedResponse.success(
                rawText = rawText,
                exchangeId = exchangeId,
                metadata = ParseMetadata(
                    parseStrategy = ParseStrategy.NON_JSON_TEXT,
                    confidence = Confidence.MEDIUM
                )
            )
        }

        // Otherwise, it's an error (probably HTML error page)
        return ParsedResponse.error(
            errorMessage = rawText.take(500), // Truncate long error pages
            errorType = ErrorClassifier.classify(null, httpStatus).errorType,
            exchangeId = exchangeId,
            metadata = ParseMetadata(
                parseStrategy = ParseStrategy.NON_JSON_TEXT,
                confidence = Confidence.MEDIUM
            )
        )
    }

    /**
     * Quick check if string looks like JSON.
     */
    private fun looksLikeJson(text: String): Boolean {
        val trimmed = text.trim()
        return (trimmed.startsWith("{") && trimmed.endsWith("}")) ||
                (trimmed.startsWith("[") && trimmed.endsWith("]"))
    }

    // ==================== Conversion helpers ====================
    // These bridge between DetectionResult types and ParseMetadata types

    /**
     * Convert detection schema to parse strategy.
     */
    private fun DetectionSchema.toParseStrategy(): ParseStrategy = when (this) {
        DetectionSchema.OPENAI -> ParseStrategy.KNOWN_SCHEMA_OPENAI
        DetectionSchema.GEMINI -> ParseStrategy.KNOWN_SCHEMA_GEMINI
        DetectionSchema.ANTHROPIC -> ParseStrategy.KNOWN_SCHEMA_ANTHROPIC
        DetectionSchema.ERROR -> ParseStrategy.ERROR_DETECTED
        DetectionSchema.UNKNOWN -> ParseStrategy.FAILED
    }

    /**
     * Convert detection schema to metadata schema enum.
     */
    private fun DetectionSchema.toMetadataSchema(): DetectedSchema = when (this) {
        DetectionSchema.OPENAI -> DetectedSchema.OPENAI_CHAT
        DetectionSchema.GEMINI -> DetectedSchema.GEMINI
        DetectionSchema.ANTHROPIC -> DetectedSchema.ANTHROPIC
        DetectionSchema.ERROR, DetectionSchema.UNKNOWN -> DetectedSchema.UNKNOWN
    }

    /**
     * Convert DetectionResult token usage to ParseMetadata token usage.
     */
    private fun DetectionTokenUsage.toMetadataTokenUsage(): TokenUsage = TokenUsage(
        promptTokens = this.promptTokens,
        completionTokens = this.completionTokens,
        totalTokens = this.totalTokens
    )
}