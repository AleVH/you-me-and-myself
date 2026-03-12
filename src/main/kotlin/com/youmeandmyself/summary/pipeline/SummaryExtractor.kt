package com.youmeandmyself.summary.pipeline

import com.intellij.openapi.diagnostic.Logger
import com.youmeandmyself.ai.providers.parsing.ParsedResponse
import com.youmeandmyself.ai.settings.RequestSettings
import com.youmeandmyself.dev.Dev
import com.youmeandmyself.storage.model.ExchangePurpose
import kotlinx.serialization.json.*

/**
 * Handles summary prompt construction and response extraction.
 *
 * ## Purpose
 *
 * This class encapsulates all the logic specific to code summarization:
 * - Building prompts that instruct the LLM to produce concise, plain-text summaries
 * - Level-aware prompt templates for the hierarchical generation chain
 * - Extracting summary text from LLM responses (handling various formats)
 * - Providing fallback extraction when standard parsing fails
 *
 * ## Contract-Based Output Format
 *
 * Each code hierarchy level produces TWO things:
 * 1. **Internal summary**: what this element does (for human consumption / parent context)
 * 2. **Contract**: a concise upward-facing description (consumed by the parent level's prompt)
 *
 * The LLM is instructed to output both sections with clear markers so we can
 * extract the contract portion for parent-level consumption.
 *
 * ## Template Selection
 *
 * Templates are selected by [ExchangePurpose] via [getTemplate]. Each code hierarchy
 * level has its own template. Non-code summary types (PROFILE_SUMMARY, CHAT_SUMMARY)
 * have placeholder templates with TODOs.
 *
 * ## The "Summary:" Marker Strategy
 *
 * LLM responses can come in unpredictable formats, especially from unknown providers.
 * Our prompt instructs the model to start its response with "Summary: ". This gives us
 * a reliable extraction fallback:
 *
 * 1. First, try standard response parsing (handles OpenAI, Gemini, etc.)
 * 2. If that fails, scan the raw JSON for any string starting with "Summary: "
 * 3. If found, extract everything after the marker
 * 4. Only ask the user as an absolute last resort (should rarely happen)
 *
 * This is less intrusive than the chat correction flow because:
 * - Summaries happen in the background, not blocking the user
 * - We have a self-describing marker to find the content
 * - The user didn't explicitly request this output
 */
object SummaryExtractor {

    private val log = Logger.getInstance(SummaryExtractor::class.java)

    /** The marker we instruct the LLM to prefix its summary with. */
    private const val SUMMARY_MARKER = "Summary: "

    /** Marker for extracting the contract portion from hierarchical summaries. */
    private const val CONTRACT_MARKER = "Contract: "

    // ==================== Prompt Templates ====================
    //
    // Each template uses placeholders:
    //   {languageId}        — programming language (e.g., "Kotlin")
    //   {sourceText}        — the raw source code (for leaf-level: METHOD)
    //   {childSummaries}    — contracts from the level below (for CLASS, FILE, MODULE, PROJECT)
    //   {structuralContext}  — PSI/heuristic context (annotations, types, etc.)
    //
    // Templates produce dual output for code hierarchy:
    //   Summary: <internal summary>
    //   Contract: <one-line upward-facing description for parent level>

    /** Default/fallback template — used when no level-specific template matches. */
    private val DEFAULT_PROMPT_TEMPLATE = """
        |Summarize this {languageId} code concisely in plain text.
        |No markdown formatting, no code blocks, just a clear description of what this code does.
        |Start your response with "Summary: " followed by the summary text.
        |
        |{sourceText}
    """.trimMargin()

    /**
     * METHOD_SUMMARY template.
     * Input: raw source code + structural context.
     * Output: what the method does internally + contract (signature + one-line purpose).
     * This is the leaf node — no child summaries to consume.
     */
    private val METHOD_TEMPLATE = """
        |You are summarizing a single {languageId} method/function.
        |
        |Structural context:
        |{structuralContext}
        |
        |Source code:
        |{sourceText}
        |
        |Produce two sections:
        |1. Start with "Summary: " — describe what this method does internally (logic, side effects, key decisions). Plain text, no markdown. Keep it concise (2-4 sentences).
        |2. Then on a new line, "Contract: " — one line: the method signature and a brief purpose statement. This will be consumed by a class-level summary, so focus on WHAT it offers, not HOW it works.
    """.trimMargin()

    /**
     * CLASS_SUMMARY template.
     * Input: method contracts (NOT raw method code) + structural context.
     * Output: how methods coordinate + class-level contract.
     */
    private val CLASS_TEMPLATE = """
        |You are summarizing a {languageId} class/object/interface.
        |You are given the contracts of its methods — each describes what the method offers.
        |Do NOT assume access to method internals; work only from the contracts.
        |
        |Structural context:
        |{structuralContext}
        |
        |Method contracts:
        |{childSummaries}
        |
        |Produce two sections:
        |1. Start with "Summary: " — describe the class's responsibility: how do the methods coordinate? What state is shared? What are the key dependencies? Plain text, no markdown. Keep it concise (3-5 sentences).
        |2. Then on a new line, "Contract: " — one line: the class name, its primary responsibility, and its public interface summary. This will be consumed by a file-level summary.
    """.trimMargin()

    /**
     * FILE_SUMMARY template (hierarchical version).
     * Input: class contracts (NOT raw class code) + structural context.
     * Output: how classes compose + file-level contract.
     *
     * Note: the original DEFAULT_PROMPT_TEMPLATE is kept for backward compatibility
     * with the existing pipeline path that sends raw source code for file summaries.
     * This template is used when class contracts are available (hierarchical path).
     */
    private val FILE_TEMPLATE = """
        |You are summarizing a {languageId} source file.
        |You are given the contracts of its classes/objects — each describes what the class offers.
        |Do NOT assume access to class internals; work only from the contracts.
        |
        |Structural context:
        |{structuralContext}
        |
        |Class contracts:
        |{childSummaries}
        |
        |Produce two sections:
        |1. Start with "Summary: " — describe the file's purpose: how do the classes compose? What is the file's role in the larger system? Plain text, no markdown. Keep it concise (2-4 sentences).
        |2. Then on a new line, "Contract: " — one line: the file name, its primary purpose, and what it exports/provides. This will be consumed by a module-level summary.
    """.trimMargin()

    /**
     * MODULE_SUMMARY template.
     * Input: file contracts.
     * Output: module purpose, boundaries, external interface.
     * Generation is gated behind user confirmation (can cascade).
     */
    private val MODULE_TEMPLATE = """
        |You are summarizing a {languageId} module/package.
        |You are given the contracts of its files — each describes what the file provides.
        |Do NOT assume access to file internals; work only from the contracts.
        |
        |Structural context:
        |{structuralContext}
        |
        |File contracts:
        |{childSummaries}
        |
        |Produce two sections:
        |1. Start with "Summary: " — describe the module's purpose: what boundary does it define? What is its external interface? How do the files work together? Plain text, no markdown. Keep it concise (3-5 sentences).
        |2. Then on a new line, "Contract: " — one line: the module name, its responsibility, and its public API surface. This will be consumed by a project-level summary.
    """.trimMargin()

    /**
     * PROJECT_SUMMARY template.
     * Input: module contracts.
     * Output: system architecture, entry points, data flows.
     * Top of the hierarchy — no contract produced (nothing above to consume it).
     * Generation is gated behind user confirmation (can cascade).
     */
    private val PROJECT_TEMPLATE = """
        |You are summarizing an entire {languageId} project/application.
        |You are given the contracts of its modules — each describes what the module provides.
        |Do NOT assume access to module internals; work only from the contracts.
        |
        |Module contracts:
        |{childSummaries}
        |
        |Start with "Summary: " — describe the project's architecture: what are the major components? How do they interact? What are the entry points and data flows? Plain text, no markdown. Keep it concise (4-6 sentences).
        |
        |No "Contract:" line needed — this is the top of the hierarchy.
    """.trimMargin()

    /**
     * PROFILE_SUMMARY template.
     *
     * Summarizes the user's assistant profile YAML into a concise directive set
     * that is prepended to every API request's system prompt.
     *
     * ## Design Principles
     *
     * - **Faithful:** Every directive in the original must appear in the summary.
     *   Omitting a directive is worse than a longer summary.
     * - **Concise:** Merge overlapping instructions, remove redundancy, compress
     *   verbose explanations into direct commands.
     * - **Structured:** Preserve section grouping so the AI can distinguish between
     *   e.g. communication style and coding conventions.
     * - **No interpretation:** The summary must not add, infer, or reframe directives.
     *   If the user says "never use tabs", the summary says "never use tabs" — not
     *   "prefers spaces" (which loses the emphasis).
     *
     * ## {content} Placeholder
     *
     * SummarizationService replaces {content} with the full profile text
     * (all sections concatenated with labels via AssistantProfileData.toFullText).
     *
     * @see com.youmeandmyself.summary.consumers.AssistantProfileSummarizer
     */
    private val PROFILE_TEMPLATE = """
        |You are summarizing an AI assistant's personality profile. This profile contains
        |the user's directives for how the AI should behave across all conversations.
        |
        |Your task: produce a concise version that preserves EVERY directive faithfully.
        |
        |Rules:
        |1. Every instruction in the original MUST appear in your summary. Do not omit anything.
        |2. Merge overlapping or redundant instructions into single clear statements.
        |3. Use direct imperative language ("Use 4-space indentation" not "The user prefers 4-space indentation").
        |4. Preserve the section structure (keep section headings).
        |5. Do not add, infer, or reinterpret directives beyond what is explicitly stated.
        |6. Do not include meta-commentary about the summarization process.
        |7. Output ONLY the summarized profile — no preamble, no explanation.
        |
        |Profile to summarize:
        |
        |{content}
    """.trimMargin()

    /**
     * CHAT_SUMMARY template (placeholder).
     *
     * TODO (Phase B): Will take older conversation turns, produce a compressed summary
     * preserving key context (decisions, code references, action items).
     * Triggered when conversation exceeds the verbatim context window.
     * Uses SummarizationService with CHAT_SUMMARY purpose.
     *
     * Important: the original exchanges persist in JSONL (source of truth — never lost).
     * This summary is a context window optimization overlay — the AI sees the summary
     * instead of raw turns, but the user can still browse all original turns.
     *
     * @see com.youmeandmyself.summary.consumers.ChatHistorySummarizer
     */
    private val CHAT_TEMPLATE = """
        |TODO: Chat compression template — not implemented yet (Phase B).
        |Will compress older conversation turns preserving key context.
        |Original exchanges remain in JSONL — this is an optimization overlay, not a replacement.
        |
        |{content}
    """.trimMargin()

    // ==================== Template Selection ====================

    /**
     * Get the prompt template for a given [ExchangePurpose].
     *
     * For code hierarchy purposes (METHOD → PROJECT), returns the level-specific
     * template that instructs the LLM to produce the contract-based output format.
     *
     * For CHAT and future consumer purposes, returns the appropriate template
     * (or placeholder if not yet implemented).
     *
     * @param purpose The exchange purpose determining which template to use
     * @return The prompt template string with placeholders
     */
    fun getTemplate(purpose: ExchangePurpose): String = when (purpose) {
        ExchangePurpose.METHOD_SUMMARY -> METHOD_TEMPLATE
        ExchangePurpose.CLASS_SUMMARY -> CLASS_TEMPLATE
        ExchangePurpose.FILE_SUMMARY -> FILE_TEMPLATE
        ExchangePurpose.MODULE_SUMMARY -> MODULE_TEMPLATE
        ExchangePurpose.PROJECT_SUMMARY -> PROJECT_TEMPLATE
        ExchangePurpose.PROFILE_SUMMARY -> PROFILE_TEMPLATE
        ExchangePurpose.CHAT_SUMMARY -> CHAT_TEMPLATE
        ExchangePurpose.CHAT -> DEFAULT_PROMPT_TEMPLATE // shouldn't happen, but safe fallback
    }

    // ==================== Prompt Building ====================

    /**
     * Build the complete prompt for a summary request (original API — backward compatible).
     *
     * Takes the prompt template (from settings or default) and replaces placeholders:
     * - {languageId} → the programming language (e.g., "Kotlin", "Python")
     * - {sourceText} → the actual code to summarize
     *
     * This method is used by the existing file-level summary path that sends raw
     * source code directly (non-hierarchical). For hierarchical prompts that use
     * child summaries, use [buildHierarchicalPrompt] instead.
     *
     * @param languageId Programming language identifier (e.g., "Kotlin", "Java", "Python").
     *                   If null or blank, uses "code" as a generic fallback.
     * @param sourceText The code snippet to summarize.
     * @param settings Optional request settings containing a custom prompt template.
     *                 If null or if systemPrompt is blank, uses DEFAULT_PROMPT_TEMPLATE.
     * @return The complete prompt ready to send to the LLM.
     */
    fun buildPrompt(
        languageId: String?,
        sourceText: String,
        settings: RequestSettings? = null
    ): String {
        // Use custom template if provided, otherwise default
        val template = settings?.systemPrompt?.takeIf { it.isNotBlank() }
            ?: DEFAULT_PROMPT_TEMPLATE

        // Normalize language ID
        val language = languageId?.takeIf { it.isNotBlank() } ?: "code"

        // Replace placeholders
        val prompt = template
            .replace("{languageId}", language)
            .replace("{sourceText}", sourceText)

        Dev.info(log, "prompt.built",
            "languageId" to language,
            "sourceLength" to sourceText.length,
            "promptLength" to prompt.length,
            "usingCustomTemplate" to (settings?.systemPrompt != null)
        )

        return prompt
    }

    /**
     * Build a hierarchical prompt for contract-based summarization.
     *
     * Used by the hierarchical generation chain where each level consumes
     * contracts from the level below rather than raw source code.
     *
     * @param purpose The exchange purpose — determines which template to use
     * @param languageId Programming language identifier
     * @param sourceText Raw source code (used for METHOD_SUMMARY leaf nodes)
     * @param childSummaries Contracts from child-level summaries (used for CLASS and above).
     *                       Each string is a contract line from a child element.
     * @param structuralContext Structural info from [StructuralContextExtractor]
     *                          (annotations, types, visibility, etc.)
     * @param customTemplate Optional user-provided template override. If provided, takes
     *                       precedence over the built-in template for this purpose.
     * @return The complete prompt ready to send to the LLM.
     */
    fun buildHierarchicalPrompt(
        purpose: ExchangePurpose,
        languageId: String?,
        sourceText: String = "",
        childSummaries: List<String> = emptyList(),
        structuralContext: String = "",
        customTemplate: String? = null
    ): String {
        val template = customTemplate?.takeIf { it.isNotBlank() } ?: getTemplate(purpose)
        val language = languageId?.takeIf { it.isNotBlank() } ?: "code"

        val prompt = template
            .replace("{languageId}", language)
            .replace("{sourceText}", sourceText)
            .replace("{childSummaries}", childSummaries.joinToString("\n") { "- $it" }.ifBlank { "(none available)" })
            .replace("{structuralContext}", structuralContext.ifBlank { "(none available)" })
            .replace("{content}", sourceText) // fallback for non-code templates using {content}

        Dev.info(log, "prompt.hierarchical.built",
            "purpose" to purpose.name,
            "languageId" to language,
            "sourceLength" to sourceText.length,
            "childCount" to childSummaries.size,
            "hasStructuralContext" to structuralContext.isNotBlank(),
            "promptLength" to prompt.length,
            "usingCustomTemplate" to (customTemplate != null)
        )

        return prompt
    }

    // ==================== Response Extraction ====================

    /**
     * Extract summary text from a parsed LLM response.
     *
     * Extraction strategy (in order):
     * 1. Use displayText from standard parsing if available and valid
     * 2. Scan raw JSON for "Summary: " marker if standard parsing failed
     * 3. Return null if all extraction attempts fail (caller should handle)
     *
     * The extracted text has the "Summary: " prefix stripped if present.
     *
     * @param response The parsed response from the LLM provider.
     * @param rawJson The raw JSON response (for fallback extraction).
     * @return The extracted summary text, or null if extraction failed.
     */
    fun extract(response: ParsedResponse, rawJson: String?): ExtractionResult {
        Dev.info(log, "extract.start",
            "exchangeId" to response.exchangeId,
            "isError" to response.isError,
            "hasDisplayText" to (response.displayText.isNotBlank())
        )

        // Strategy 1: Standard parsing succeeded
        if (!response.isError && response.displayText.isNotBlank()) {
            val cleaned = stripMarkerIfPresent(response.displayText)
            Dev.info(log, "extract.standard_success",
                "exchangeId" to response.exchangeId,
                "length" to cleaned.length
            )
            return ExtractionResult.Success(cleaned)
        }

        // Strategy 2: Scan raw JSON for marker
        if (!rawJson.isNullOrBlank()) {
            val fromMarker = extractByMarker(rawJson)
            if (fromMarker != null) {
                Dev.info(log, "extract.marker_success",
                    "exchangeId" to response.exchangeId,
                    "length" to fromMarker.length
                )
                return ExtractionResult.Success(fromMarker)
            }
        }

        // Strategy 3: All extraction failed
        Dev.warn(log, "extract.failed", null,
            "exchangeId" to response.exchangeId,
            "errorType" to response.errorType?.name,
            "errorMessage" to response.errorMessage?.take(100)
        )

        return ExtractionResult.Failed(
            reason = response.errorMessage ?: "Could not extract summary from response",
            rawJson = rawJson
        )
    }

    /**
     * Extract the contract portion from a hierarchical summary response.
     *
     * Looks for the "Contract: " marker in the response text and extracts
     * everything after it (typically one line). This contract is what the
     * parent level consumes when building its summary.
     *
     * @param fullResponse The full LLM response text (may contain both Summary and Contract)
     * @return The contract text, or null if no contract marker found.
     *         For PROJECT_SUMMARY, null is expected (top of hierarchy).
     */
    fun extractContract(fullResponse: String): String? {
        val markerIndex = fullResponse.indexOf(CONTRACT_MARKER, ignoreCase = true)
        if (markerIndex == -1) return null

        val afterMarker = fullResponse.substring(markerIndex + CONTRACT_MARKER.length)
        // Contract is typically one line — take until next newline or end of string
        val endIndex = afterMarker.indexOfAny(charArrayOf('\n', '\r'))
        val contract = if (endIndex == -1) {
            afterMarker.trim()
        } else {
            afterMarker.substring(0, endIndex).trim()
        }

        return contract.takeIf { it.isNotBlank() }
    }

    /**
     * Extract just the summary portion (without the contract) from a hierarchical response.
     *
     * Looks for text between "Summary: " and "Contract: " markers.
     * If no Contract marker, returns everything after Summary marker.
     *
     * @param fullResponse The full LLM response text
     * @return The summary portion only, or the full stripped text if markers aren't found
     */
    fun extractSummaryOnly(fullResponse: String): String {
        val stripped = stripMarkerIfPresent(fullResponse)

        // If there's a contract marker, take only what's before it
        val contractIndex = stripped.indexOf(CONTRACT_MARKER, ignoreCase = true)
        return if (contractIndex > 0) {
            stripped.substring(0, contractIndex).trim()
        } else {
            stripped
        }
    }

    /**
     * Attempt to extract summary by scanning for the "Summary: " marker.
     *
     * This is the fallback strategy for unknown response formats. It recursively
     * scans all string values in the JSON looking for one that starts with our marker.
     *
     * @param rawJson The raw JSON response string.
     * @return The summary text (marker stripped), or null if not found.
     */
    private fun extractByMarker(rawJson: String): String? {
        return try {
            val jsonElement = Json.parseToJsonElement(rawJson)
            findMarkerInJson(jsonElement)
        } catch (e: Exception) {
            Dev.warn(log, "extract.marker_parse_failed", e,
                "rawPreview" to rawJson.take(200)
            )
            // Last ditch: maybe it's not even JSON, just search the raw string
            findMarkerInText(rawJson)
        }
    }

    /**
     * Recursively search a JSON structure for a string starting with "Summary: ".
     *
     * Traverses objects, arrays, and primitives looking for our marker.
     * Returns the first match found (with marker stripped).
     */
    private fun findMarkerInJson(element: JsonElement): String? {
        return when (element) {
            is JsonPrimitive -> {
                if (element.isString) {
                    val text = element.content
                    if (text.startsWith(SUMMARY_MARKER, ignoreCase = true)) {
                        text.substring(SUMMARY_MARKER.length).trim()
                    } else {
                        null
                    }
                } else {
                    null
                }
            }
            is JsonArray -> {
                // Search each element, return first match
                element.firstNotNullOfOrNull { findMarkerInJson(it) }
            }
            is JsonObject -> {
                // Search each value, return first match
                element.values.firstNotNullOfOrNull { findMarkerInJson(it) }
            }
        }
    }

    /**
     * Search plain text for the "Summary: " marker.
     *
     * Used when the response isn't valid JSON at all.
     * Finds the marker and returns everything after it until end of line or string.
     */
    private fun findMarkerInText(text: String): String? {
        val markerIndex = text.indexOf(SUMMARY_MARKER, ignoreCase = true)
        if (markerIndex == -1) return null

        val afterMarker = text.substring(markerIndex + SUMMARY_MARKER.length)
        // Take until end of line or end of string, then trim
        val endIndex = afterMarker.indexOfAny(charArrayOf('\n', '\r', '"'))
        return if (endIndex == -1) {
            afterMarker.trim()
        } else {
            afterMarker.substring(0, endIndex).trim()
        }
    }

    /**
     * Strip the "Summary: " marker prefix if present.
     *
     * The marker is just an extraction aid — we don't want it in the stored summary.
     */
    fun stripMarkerIfPresent(text: String): String {
        val trimmed = text.trim()
        return if (trimmed.startsWith(SUMMARY_MARKER, ignoreCase = true)) {
            trimmed.substring(SUMMARY_MARKER.length).trim()
        } else {
            trimmed
        }
    }

    // ==================== Result Types ====================

    /**
     * Result of a summary extraction attempt.
     *
     * Sealed class to clearly distinguish between:
     * - Success: we got a summary
     * - Failed: extraction failed, contains reason for logging/debugging
     */
    sealed class ExtractionResult {
        /**
         * Summary was successfully extracted.
         * @param summary The clean summary text (no marker prefix).
         */
        data class Success(val summary: String) : ExtractionResult()

        /**
         * Extraction failed.
         * @param reason Human-readable explanation of what went wrong.
         * @param rawJson The raw response for debugging (can be shown to user as last resort).
         */
        data class Failed(val reason: String, val rawJson: String?) : ExtractionResult()

        /** Convenience check for success. */
        val isSuccess: Boolean get() = this is Success

        /** Get summary if successful, null otherwise. */
        fun summaryOrNull(): String? = (this as? Success)?.summary
    }
}