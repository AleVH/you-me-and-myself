package com.youmeandmyself.ai.chat.orchestrator

import com.youmeandmyself.ai.chat.context.ContextBlock
import com.youmeandmyself.storage.model.ConversationTurn

/**
 * Structured representation of the four independent blocks in an API request.
 *
 * ## Purpose
 *
 * Before this class, the request was a flat prompt string — profile, history,
 * context, and user message concatenated together. This made it impossible to
 * independently manage, budget, or evolve each section.
 *
 * RequestBlocks holds the four sections as structured data. Serialization into
 * the API's system/user/assistant message format happens as the last step in
 * [GenericLlmProvider], not here.
 *
 * ## Block Responsibilities
 *
 * | Block | Content | Lifecycle |
 * |-------|---------|-----------|
 * | [profile] | Assistant persona from YAML | Loaded once, cached, updated when YAML changes |
 * | [compactedHistory] | Summarized older turns | Null until Phase 4 (history compaction) |
 * | [verbatimHistory] | Recent conversation turns | Built per-request from ConversationManager |
 * | [context] | IDE-provided information | Managed by staging area (Phase 2), snapshotted at send time |
 * | [userMessage] | What the user just typed | Ephemeral — the current turn's input |
 *
 * ## Serialization
 *
 * The provider maps these blocks to the API's message format:
 * - [profile] → system message (OpenAI/Custom) or prefixed user message (Gemini)
 * - [compactedHistory] → user/assistant summary turn (when non-null)
 * - [verbatimHistory] → user/assistant message pairs
 * - [context] + [userMessage] → final user message (context prepended to user input)
 *
 * ## Per-Block Metrics
 *
 * Each block's token cost can be estimated independently (content.length / 4 heuristic).
 * This powers the per-block breakdown in MetricsRecord and the stacked fill bar in
 * MetricsBar (Phase 4 UI).
 *
 * @property profile System prompt from AssistantProfileService. Null if no profile configured
 *   or the feature is disabled. Injected as the first message in every request.
 * @property compactedHistory Summarized older conversation turns. Null until Phase 4
 *   implements progressive history compaction. When non-null, inserted before
 *   [verbatimHistory] in the message array.
 * @property verbatimHistory Recent conversation turns sent as-is. Built by
 *   ConversationManager.buildHistory() with a configurable verbatim window (default 5).
 * @property context IDE-provided context — file summaries, raw code, framework info.
 *   Managed by ContextStagingService (Phase 2) or ContextAssembler (Phase 1).
 *   Snapshotted at send time.
 * @property userMessage The raw text the user typed. Never mixed with context in this
 *   data class — concatenation happens at serialization time in the provider.
 *
 * @see com.youmeandmyself.ai.chat.context.ContextBlock
 * @see com.youmeandmyself.ai.providers.generic.GenericLlmProvider
 */
data class RequestBlocks(
    val profile: String?,
    val compactedHistory: String?,
    val verbatimHistory: List<ConversationTurn>,
    val context: ContextBlock,
    val userMessage: String
)
