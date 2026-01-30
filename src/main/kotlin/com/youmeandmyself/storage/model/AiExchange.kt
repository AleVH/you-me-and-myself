package com.youmeandmyself.storage.model

import java.time.Instant

/**
 * Complete record of a single AI interaction (request + response).
 * This is the "raw data" stored in the project directory as JSONL.
 *
 * Immutable once created — represents a historical fact.
 *
 * @property id Unique identifier (UUID) for this exchange
 * @property timestamp When the exchange occurred
 * @property providerId Which AI provider handled this (e.g., "openai", "deepseek")
 * @property modelId Specific model used (e.g., "gpt-4-turbo", "deepseek-coder")
 * @property purpose Why this exchange happened (chat, summary, etc.)
 * @property request The user's input and parameters
 * @property rawResponse The complete, unmodified response from the provider
 */
data class AiExchange(
    val id: String,
    val timestamp: Instant,
    val providerId: String,
    val modelId: String,
    val purpose: ExchangePurpose,
    val request: ExchangeRequest,
    val rawResponse: ExchangeRawResponse
)

/**
 * The request portion of an exchange — what was sent to the AI.
 *
 * Contains both the user's input and all parameters that control the AI's behavior.
 * Most parameters are optional and default to null, meaning "use provider default".
 * We store them even when not explicitly set, for completeness and future analysis.
 *
 * @property input The user's message or prompt text
 * @property systemPrompt Instructions that set the AI's behavior, persona, or context.
 *                        Sent as a "system" role message in OpenAI-compatible APIs.
 *                        Null means no system prompt was provided.
 * @property contextFiles File paths that were included as context (null if none)
 * @property temperature Controls response randomness: 0.0 = deterministic, 1.0 = creative.
 *                        Typical range 0.0-2.0 depending on provider. Null = provider default.
 * @property maxTokens Maximum number of tokens in the response. Null = provider default.
 * @property topP Nucleus sampling: only consider tokens with cumulative probability >= topP.
 *                Alternative to temperature. Range 0.0-1.0. Null = provider default.
 *                Note: Usually you set temperature OR topP, not both.
 * @property stopSequences List of strings that signal the AI to stop generating.
 *                         When the model outputs any of these, generation stops.
 *                         Null means no stop sequences.
 */
data class ExchangeRequest(
    val input: String,
    val systemPrompt: String? = null,
    val contextFiles: List<String>? = null,
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    val topP: Double? = null,
    val stopSequences: List<String>? = null
)