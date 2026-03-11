package com.youmeandmyself.ai.metrics

/**
 * Default context window sizes for known AI models.
 *
 * ## Purpose
 *
 * Provides a best-effort context window size when the user hasn't
 * configured one on their AiProfile. This powers the "context fill bar"
 * in MetricsBar — showing how much of the model's context window was
 * used by the last exchange.
 *
 * ## How It's Used
 *
 * Resolution order (first non-null wins):
 * 1. AiProfile.contextWindowSize — user's explicit override for this profile
 * 2. DefaultContextWindows.lookup(profile.model) — built-in default
 * 3. null — fill bar is hidden (graceful degradation)
 *
 * ## Maintenance
 *
 * Adding a new model = adding one line to the map below.
 * No cascading changes, no schema changes, no migration.
 *
 * Model names are matched case-insensitively and with prefix matching
 * so "gpt-4o-2024-05-13" matches the "gpt-4o" entry. The longest
 * matching prefix wins (so "gpt-4o-mini" matches before "gpt-4o").
 *
 * ## Important
 *
 * These are best-effort defaults. Providers can deploy the same model
 * name with different context limits (e.g., fine-tuned deployments,
 * enterprise vs. public API). The user can always override per-profile.
 *
 * Values are in tokens (not characters, not bytes).
 * Last updated: March 2026.
 */
object DefaultContextWindows {

    /**
     * Known model context window sizes.
     *
     * Sorted by provider group for readability. Each entry maps a model
     * name prefix to its maximum context window in tokens.
     *
     * When adding new entries:
     * - Use the model name as it appears in API responses
     * - More specific names should come before general ones
     *   (e.g., "gpt-4o-mini" before "gpt-4o") — the lookup uses
     *   longest-prefix matching
     * - Values should reflect the standard public API limits
     */
    private val KNOWN_SIZES: Map<String, Int> = linkedMapOf(

        // ── OpenAI ────────────────────────────────────────────────────
        "o3-mini"               to 200_000,
        "o3"                    to 200_000,
        "o1-mini"               to 128_000,
        "o1"                    to 200_000,
        "gpt-4.1-mini"          to 1_047_576,
        "gpt-4.1-nano"          to 1_047_576,
        "gpt-4.1"               to 1_047_576,
        "gpt-4o-mini"           to 128_000,
        "gpt-4o"                to 128_000,
        "gpt-4-turbo"           to 128_000,
        "gpt-4"                 to 8_192,
        "gpt-3.5-turbo"         to 16_385,

        // ── Anthropic ─────────────────────────────────────────────────
        "claude-opus-4"         to 200_000,
        "claude-sonnet-4"       to 200_000,
        "claude-3.5-sonnet"     to 200_000,
        "claude-3.5-haiku"      to 200_000,
        "claude-3-opus"         to 200_000,
        "claude-3-sonnet"       to 200_000,
        "claude-3-haiku"        to 200_000,

        // ── Google Gemini ─────────────────────────────────────────────
        "gemini-2.5-pro"        to 1_048_576,
        "gemini-2.5-flash"      to 1_048_576,
        "gemini-2.0-flash"      to 1_048_576,
        "gemini-1.5-pro"        to 2_097_152,
        "gemini-1.5-flash"      to 1_048_576,

        // ── DeepSeek ──────────────────────────────────────────────────
        "deepseek-chat"         to 64_000,
        "deepseek-reasoner"     to 64_000,
        "deepseek-coder"        to 128_000,

        // ── Meta Llama (common Ollama models) ─────────────────────────
        "llama-3.3"             to 128_000,
        "llama-3.2"             to 128_000,
        "llama-3.1"             to 128_000,
        "llama-3"               to 8_192,
        "llama3"                to 8_192,

        // ── Mistral ───────────────────────────────────────────────────
        "mistral-large"         to 128_000,
        "mistral-medium"        to 32_000,
        "mistral-small"         to 32_000,
        "mixtral"               to 32_000,
        "mistral"               to 32_000,

        // ── Qwen ──────────────────────────────────────────────────────
        "qwen-2.5"              to 128_000,
        "qwen2.5"               to 128_000,
        "qwen-2"                to 32_000,
        "qwen2"                 to 32_000,

        // ── CodeLlama (Ollama) ────────────────────────────────────────
        "codellama"             to 16_384,

        // ── Phi ───────────────────────────────────────────────────────
        "phi-4"                 to 16_384,
        "phi-3"                 to 128_000,
    )

    /**
     * Look up the default context window size for a model.
     *
     * Uses case-insensitive longest-prefix matching:
     * - "gpt-4o-2024-05-13" → matches "gpt-4o" → 128,000
     * - "gemini-2.5-flash-latest" → matches "gemini-2.5-flash" → 1,048,576
     * - "my-custom-model" → no match → null
     *
     * @param modelName The model identifier as configured in the profile
     *   or returned by the provider
     * @return Context window size in tokens, or null if the model is unknown
     */
    fun lookup(modelName: String?): Int? {
        if (modelName.isNullOrBlank()) return null

        val normalized = modelName.lowercase().trim()

        // Longest-prefix match: try the most specific match first.
        // Since KNOWN_SIZES is a LinkedHashMap with more-specific entries
        // listed before less-specific ones within each provider group,
        // we sort candidates by key length descending and take the first match.
        return KNOWN_SIZES.entries
            .filter { (prefix, _) -> normalized.startsWith(prefix.lowercase()) }
            .maxByOrNull { (prefix, _) -> prefix.length }
            ?.value
    }

    /**
     * Resolve the effective context window size for a profile.
     *
     * Priority:
     * 1. User's explicit override (profileContextWindowSize)
     * 2. Built-in default for the model name
     * 3. null (fill bar hidden — graceful degradation)
     *
     * @param profileContextWindowSize The user's override from AiProfile.contextWindowSize
     * @param modelName The model identifier for built-in lookup
     * @return Resolved context window size in tokens, or null
     */
    fun resolve(profileContextWindowSize: Int?, modelName: String?): Int? {
        return profileContextWindowSize ?: lookup(modelName)
    }
}