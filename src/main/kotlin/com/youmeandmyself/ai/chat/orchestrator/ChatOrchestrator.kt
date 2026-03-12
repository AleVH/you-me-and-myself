package com.youmeandmyself.ai.chat.orchestrator

import com.intellij.openapi.project.Project
import com.youmeandmyself.ai.chat.bridge.BridgeMessage
import com.youmeandmyself.ai.chat.context.ContextAssembler
import com.youmeandmyself.ai.chat.conversation.ConversationManager
import com.youmeandmyself.ai.providers.AiProvider
import com.youmeandmyself.ai.providers.ProviderRegistry
import com.youmeandmyself.ai.providers.ProviderResponse
import com.youmeandmyself.ai.providers.parsing.ui.CorrectionFlowHelper
import com.youmeandmyself.ai.settings.AiProfilesState
import com.youmeandmyself.dev.Dev
import com.youmeandmyself.budget.BudgetChecker
import com.youmeandmyself.storage.LocalStorageFacade
import com.youmeandmyself.storage.model.AiExchange
import com.youmeandmyself.storage.model.DerivedMetadata
import com.youmeandmyself.storage.model.ExchangePurpose
import com.youmeandmyself.storage.model.ExchangeRawResponse
import com.youmeandmyself.storage.model.ExchangeRequest
import com.youmeandmyself.storage.model.ExchangeTokenUsage
import com.youmeandmyself.profile.AssistantProfileService
import com.youmeandmyself.storage.model.IdeContext
import com.youmeandmyself.storage.model.IdeContextCapture
import kotlinx.coroutines.CoroutineScope
import java.time.Instant

/**
 * Central orchestrator for the chat pipeline.
 *
 * ## Why This Exists
 *
 * Before this refactoring, `ChatPanel.doSend()` was ~300 lines doing 6 jobs:
 * UI updates, provider orchestration, context assembly, correction flow,
 * conversation bookkeeping, and profile management. `GenericLlmProvider.handleResponse()`
 * was doing HTTP + storage writes + token indexing + assistant text caching +
 * derived metadata extraction + IDE context storage.
 *
 * This class extracts all the **logic** out of both, leaving:
 * - ChatPanel (and future React) as a thin UI shell
 * - GenericLlmProvider as a pure HTTP client
 *
 * ## Architecture
 *
 * ```
 * UI Layer (React / Swing / future)
 *     │ calls send()
 *     ▼
 * ChatOrchestrator (this class)
 *     ├── ContextAssembler (prompt building)
 *     ├── AiProvider (HTTP only, returns ProviderResponse)
 *     ├── CorrectionFlowHelper (parsing uncertainty handling)
 *     ├── ConversationManager (conversation bookkeeping)
 *     ├── LocalStorageFacade (persistence)
 *     └── Returns: ChatResult (UI just renders this)
 * ```
 *
 * ## Key Design Rules
 *
 * 1. **Zero UI imports.** No Swing, no JCEF, no React. If it touches a pixel,
 *    it doesn't belong here.
 *
 * 2. **ChatResult is the only output.** The UI receives ChatResult and renders it.
 *    The UI never calls storage, never talks to providers, never decides correction flow.
 *
 * 3. **Stateless per-call.** Each `send()` call is independent. The orchestrator
 *    doesn't hold conversation state between calls — that's in ConversationManager.
 *    The only mutable state is `currentConversationId` which tracks the active
 *    conversation for the current chat tab.
 *
 * 4. **Storage writes happen here.** The provider returns raw data; we save it.
 *    This means mocking the provider for tests doesn't skip storage, and mocking
 *    storage doesn't affect provider behavior. Clean separation.
 *
 * ## Thread Safety
 *
 * `send()` is a suspend function meant to be called from a coroutine scope.
 * Multiple concurrent calls are safe because:
 * - LocalStorageFacade has its own write mutex
 * - ConversationManager delegates to LocalStorageFacade
 * - CorrectionFlowHelper's mutable state is per-orchestrator-instance
 * - IDE context capture is a snapshot (no side effects)
 *
 * Each chat tab should have its own orchestrator instance to avoid
 * correction flow conflicts between tabs.
 *
 * @param project The IntelliJ project context
 * @param contextAssembler Builds enriched prompts with IDE context and summaries
 * @param correctionHelper Manages the three parsing confidence scenarios
 * @param conversationManager Handles conversation lifecycle and exchange linking
 * @param storage Persistence layer for exchanges, tokens, metadata
 */
class ChatOrchestrator(
    private val project: Project,
    private val contextAssembler: ContextAssembler,
    internal val correctionHelper: CorrectionFlowHelper,
    private val conversationManager: ConversationManager,
    private val storage: LocalStorageFacade
) {
    private val log = Dev.logger(ChatOrchestrator::class.java)

    /**
     * The active conversation ID for this chat tab.
     *
     * Null until the first message creates a conversation.
     * Each ChatOrchestrator instance (= each tab) has its own conversation.
     *
     * ## Why Mutable?
     *
     * The conversation is created lazily on the first message, not when
     * the tab opens. This avoids creating empty conversations for tabs
     * the user opened but never used.
     */
    private var currentConversationId: String? = null

    // ── Public API ───────────────────────────────────────────────────────

    /**
     * Process a user message through the complete chat pipeline.
     *
     * This is the single entry point for the UI. The full pipeline:
     *
     * 1. Resolve the active AI provider
     *    - If providerId is supplied (per-tab selection), use that profile.
     *    - If not, fall back to the globally selected chat provider.
     * 2. Capture IDE context (before the HTTP call, so we snapshot what the user sees)
     * 3. Assemble the prompt (user input + IDE context + summaries)
     * 4. Call the AI provider (HTTP only, returns ProviderResponse)
     * 5. Persist the exchange to storage (JSONL + SQLite)
     * 6. Index metadata (tokens, assistant text, derived metadata, IDE context)
     * 7. Run the correction flow (3 scenarios based on parse confidence)
     * 8. Update conversation bookkeeping
     * 9. Return ChatResult for the UI to render
     *
     * If ANY step fails, the pipeline still returns a ChatResult (possibly an error one).
     * Storage and indexing failures are logged but don't block the response display.
     *
     * @param userInput The raw text the user typed
     * @param scope Coroutine scope for context gathering and provider calls
     * @param conversationId Groups this message with its conversation in storage
     * @param providerId Optional: the specific profile ID to use for this request.
     *   If null, falls back to ProviderRegistry.selectedChatProvider().
     *   Set by BridgeDispatcher from the tab's per-tab provider selection.
     * @return ChatResult that the UI renders. Never null, never throws.
     */
    suspend fun send(
        userInput: String,
        scope: CoroutineScope,
        conversationId: String? = null,
        providerId: String? = null
    ): ChatResult {
        // ── Step 1: Resolve provider ──────────────────────────────────
        // Per-tab provider takes precedence over global selection.
        // If the per-tab provider is set but invalid/missing, we warn and
        // fall back to the global selection rather than failing the request.
        val provider = if (providerId != null) {
            val perTabProvider = ProviderRegistry.providerById(project, providerId)
            if (perTabProvider == null) {
                Dev.warn(log, "orchestrator.provider.pertab_fallback",
                    null,
                    "requestedProviderId" to providerId,
                    "reason" to "profile not found or invalid, falling back to global selection"
                )
            }
            perTabProvider ?: ProviderRegistry.selectedChatProvider(project)
        } else {
            ProviderRegistry.selectedChatProvider(project)
        }

        if (provider == null) {
            return ChatResult.error(
                "[No provider selected/configured. Pick one in the dropdown or set keys in Settings.]"
            )
        }

        val profileState = AiProfilesState.getInstance(project)
        val modelId = profileState.profiles.find { it.id == provider.id }?.model

        try {
            // ── Step 2: Capture IDE context snapshot ──────────────────
            // Done BEFORE the HTTP call so we capture what the user was looking at
            // when they sent the message, not what they switched to while waiting.
            val ideContext = captureIdeContext()

            // ── Step 3: Assemble prompt ──────────────────────────────
            val assembled = contextAssembler.assemble(userInput, scope)

            // Check if context gathering was blocked by IDE indexing
            if (assembled.isBlockedByIndexing) {
                return ChatResult.error(
                    "This question requires project context, but the IDE is currently indexing files. " +
                            "Please wait until indexing finishes and then ask your question again."
                )
            }

            Dev.info(log, "orchestrator.prompt_assembled",
                "effectivePromptLength" to assembled.effectivePrompt.length,
                "hasContext" to (assembled.contextSummary != null),
                "contextTimeMs" to assembled.contextTimeMs
            )

            // ── Step 3B: Budget check ────────────────────────────────────
            // Gate every AI call through the budget checker. At launch
            // (Individual Basic), AlwaysAllowBudgetChecker approves everything.
            // Post-launch, the real implementation enforces session/daily/monthly caps.
            // This check MUST happen after prompt assembly (so we have the provider)
            // but BEFORE the HTTP call (so we can abort cheaply).
            val budgetChecker = project.getService(BudgetChecker::class.java)
            val budgetStatus = budgetChecker.check(
                purpose = "CHAT",
                providerId = provider.id,
                estimatedTokens = null  // Chat messages: can't predict token count pre-call
            )

            Dev.info(log, "orchestrator.budget_check",
                "purpose" to "CHAT",
                "providerId" to provider.id,
                "allowed" to budgetStatus.allowed,
                "warning" to budgetStatus.warning
            )

            if (!budgetStatus.allowed) {
                return ChatResult.error(
                    budgetStatus.reason ?: "Budget limit reached. Check your budget settings."
                )
            }

            // ── Step 3C: Build conversation history (Phase A3) ────────
            // Retrieves the last N turns from the active conversation so the
            // AI provider receives multi-turn context. Without this, every
            // message is standalone and the AI cannot reference previous turns.
            //
            // ## How it works
            //
            // 1. ConversationManager.buildHistory() reads the last `verbatimWindow`
            //    exchanges from SQLite and converts them to ConversationTurn objects
            //    (USER + ASSISTANT pairs).
            // 2. The history is passed to provider.chat() which builds a protocol-
            //    specific messages array: [history turns...] + [current prompt].
            // 3. IDE context (from ContextAssembler) is ONLY on the current prompt.
            //    Historical turns carry whatever context was attached when they were
            //    originally sent — this is correct because IDE context reflects the
            //    editor state at the time of each message.
            //
            // ## verbatimWindow
            //
            // Currently hardcoded at 5 (last 5 exchanges = up to 10 turns).
            // This is passed explicitly rather than buried as a magic number so
            // it's easy to find and make configurable later. The conversations
            // table has a `max_history_tokens_override` column (nullable) that
            // will be used by Phase B for per-conversation configuration.
            //
            // ── PHASE B PLACEHOLDER: Smart History Compression ──────────
            // Phase B will add intelligent history management:
            //
            // 1. CONVERSATION_SUMMARY purpose type in ExchangePurpose enum
            //    → allows storing conversation summaries as special exchanges
            //
            // 2. History compression engine in ConversationManager:
            //    → When a conversation exceeds the verbatim window, older turns
            //      are summarized into a CONVERSATION_SUMMARY exchange
            //    → The compressed history becomes: [summary of old turns] +
            //      [verbatim recent turns] + [current message]
            //    → This keeps token usage bounded while preserving long-term context
            //
            // 3. Configuration UI (Settings → YMM Assistant → History):
            //    → verbatimWindow: how many recent turns to keep verbatim (default 5)
            //    → maxHistoryTokens: total token budget for history (global default)
            //    → Per-conversation override via conversations.max_history_tokens_override
            //
            // 4. Cost awareness in MetricsBar:
            //    → Bar turns red/amber as conversation history grows toward the
            //      context window limit, alerting the user before they hit it
            //
            // Connected to: ConversationManager.buildHistory() (compression logic),
            //   ExchangePurpose.CONVERSATION_SUMMARY (new enum value),
            //   SummaryConfigService (configuration), MetricsBar (UI feedback)
            //
            // For now: verbatim-only, last 5 exchanges. This is sufficient for
            // launch and covers the vast majority of interactive chat sessions.
            // ────────────────────────────────────────────────────────────────
            val verbatimWindow = 5  // Phase B: will be configurable per-conversation
            val history = conversationManager.buildHistory(currentConversationId, verbatimWindow)

            Dev.info(log, "orchestrator.history_built",
                "conversationId" to (currentConversationId ?: "none"),
                "historyTurns" to history.size,
                "verbatimWindow" to verbatimWindow,
                "compressionMode" to "verbatim_only"  // Phase B: will show "compressed" when summarization is active
            )

            // ── Step 4: Call AI provider (HTTP only) ─────────────────
            // Timer brackets the HTTP call so MetricsService can record
            // wall-clock response time. This measures the full round-trip:
            // request serialization + network + server processing + response parsing.
            //
            // The provider receives:
            //   - history: previous conversation turns (Phase A3)
            //   - assembled.effectivePrompt: current user message with fresh IDE context
            //
            // ── System prompt from Assistant Profile ─────────────────────
            // The summarized assistant profile is prepended to every request as the
            // system prompt. This gives the AI its persona/instructions before any
            // conversation history.
            //
            // AssistantProfileService.getSystemPrompt() returns:
            // - The cached summary text (hot path — no DB/disk access)
            // - The full profile text if fallback is enabled and summary is unavailable
            // - null if the feature is disabled, unavailable, or no profile exists
            //
            // The provider injects it as the FIRST message:
            // - OpenAI/Custom: role="system"
            // - Gemini: role="user" with "[System Instructions] " prefix
            val systemPrompt = try {
                AssistantProfileService.getInstance(project).getSystemPrompt()
            } catch (e: Exception) {
                Dev.warn(log, "orchestrator.system_prompt_failed", e)
                null
            }

            if (systemPrompt != null) {
                Dev.info(log, "orchestrator.system_prompt_attached",
                    "length" to systemPrompt.length
                )
            }

            val callStartMs = System.currentTimeMillis()
            val response = provider.chat(assembled.effectivePrompt, history, systemPrompt)
            val responseTimeMs = System.currentTimeMillis() - callStartMs

            Dev.info(log, "orchestrator.provider_response",
                "exchangeId" to response.exchangeId,
                "isError" to response.isError,
                "rawLength" to (response.rawJson?.length ?: 0),
                "responseTimeMs" to responseTimeMs,
                "historyTurns" to history.size
            )

            // ── Step 5: Conversation bookkeeping ─────────────────────
            val conversationId = updateConversation(userInput, response, provider.id, modelId)

            // ── Step 6: Persist exchange to storage ──────────────────
            // Raw data is saved IMMEDIATELY — before any further processing.
            // If anything else fails, the raw response is still safe in JSONL.
            persistExchange(response, provider, ExchangePurpose.CHAT, conversationId)

            // ── Step 7: Index metadata ───────────────────────────────
            // Each step is independent — failure in one doesn't block others.
            // All are wrapped in try/catch because they're "nice to have" indexing,
            // not critical path.
            indexTokenUsage(response)
            indexAssistantText(response)
            indexDerivedMetadata(response)
            indexIdeContext(response.exchangeId, ideContext)

            // ── Step 8: Correction flow ──────────────────────────────
            // Clear any previous correction context (new message = new state)
            correctionHelper.clearCorrectionContext()

            val (finalDisplayText, finalIsError, correctionAvailable) =
                runCorrectionFlow(response, provider.id, modelId)

            // ── Step 9: Build and return ChatResult ──────────────────
            val tokenUsage = response.parsed.tokenUsage?.let {
                ExchangeTokenUsage(
                    promptTokens = it.promptTokens,
                    completionTokens = it.completionTokens,
                    totalTokens = it.totalTokens
                )
            }

            return ChatResult(
                displayText = finalDisplayText,
                isError = finalIsError,
                exchangeId = response.exchangeId,
                conversationId = conversationId,
                tokenUsage = tokenUsage,
                modelId = modelId ?: provider.displayName, // BUG (post-launch backlog #1): fallback uses display name, not model ID
                correctionAvailable = correctionAvailable,
                parseStrategy = response.parsed.metadata.parseStrategy,
                confidence = response.parsed.metadata.confidence,
                candidates = response.parsed.metadata.candidates,
                providerId = provider.id,
                contextSummary = assembled.contextSummary,
                contextTimeMs = assembled.contextTimeMs,
                responseTimeMs = responseTimeMs
            )

        } catch (t: Throwable) {
            // Catch-all for unexpected errors. The UI gets an error ChatResult
            // instead of crashing.
            Dev.error(log, "orchestrator.send_failed", t)
            return ChatResult.error("Error: ${t.message}")
        }
    }

    /**
     * Handle the /correct command — opens correction dialog for the last auto-detected response.
     *
     * Delegates to [CorrectionFlowHelper.handlePostCorrection]. The UI should call this
     * when the user types /correct. Returns null if no correctable response is available
     * or the user cancelled the dialog.
     *
     * @return Updated ChatResult with the corrected text, or null if cancelled/unavailable
     */
    suspend fun handleCorrection(): CorrectionCommandResult? {
        if (!correctionHelper.hasCorrectableResponse()) {
            return CorrectionCommandResult(
                displayText = null,
                systemMessage = "No response available for correction. This command works after an auto-detected response."
            )
        }

        val corrected = correctionHelper.handlePostCorrection()
        return if (corrected != null) {
            CorrectionCommandResult(
                displayText = corrected.displayText,
                systemMessage = "✓ Response corrected:"
            )
        } else {
            CorrectionCommandResult(
                displayText = null,
                systemMessage = "Correction cancelled."
            )
        }
    }

    /**
     * Confirm that the heuristic guess was correct — "Looks right" button.
     *
     * When the parser used heuristics (Scenario 2), the UI shows two buttons:
     * - "✓ Looks right" → calls this method
     * - "✗ Not right" → calls [handleCorrection]
     *
     * Confirming does two things:
     * 1. Saves a format hint so future responses from this provider/model
     *    are parsed the same way without guessing (skips Scenario 2 next time)
     * 2. Clears the correction context (buttons disappear)
     *
     * @return True if confirmation was processed, false if no correctable response was available
     */
    suspend fun confirmCorrection(): Boolean {
        val context = correctionHelper.lastCorrectionContext
        if (context == null) {
            Dev.info(log, "orchestrator.confirm_correction", "result" to "no_context")
            return false
        }

        // Save the format hint so this provider/model's format is recognized next time.
        // The hint tells the parser: "when you see this response shape from this
        // provider/model, the best-guess extraction path was correct."
        correctionHelper.confirmAndSaveHint(context)

        Dev.info(log, "orchestrator.confirm_correction",
            "result" to "confirmed",
            "providerId" to context.providerId,
            "modelId" to context.modelId
        )

        return true
    }

    /**
     * Handle the /raw command — shows raw JSON of the last response.
     *
     * Delegates to [CorrectionFlowHelper.showRawResponse]. The UI should call this
     * when the user types /raw.
     *
     * @return True if the raw dialog was shown, false if no response is available
     */
    suspend fun handleRawCommand(): Boolean {
        val context = correctionHelper.lastCorrectionContext ?: return false

        correctionHelper.showRawResponse(
            exchangeId = context.exchangeId,
            providerId = context.providerId,
            modelId = context.modelId
        )
        return true
    }

    /**
     * Check if a correctable response is available (for UI to show hint).
     */
    fun hasCorrectableResponse(): Boolean = correctionHelper.hasCorrectableResponse()

    /**
     * Set the active conversation ID (e.g., when switching tabs).
     *
     * When the user switches to a different chat tab, the tab manager
     * calls this to restore the orchestrator's conversation context.
     *
     * @param conversationId The conversation ID to activate, or null for a new conversation
     */
    fun setConversation(conversationId: String?) {
        currentConversationId = conversationId
    }

    /**
     * Get the active conversation ID.
     *
     * Used by the UI to know which conversation to display in the tab title,
     * and by the tab manager for state persistence.
     */
    fun getConversationId(): String? = currentConversationId

    /**
     * Load the message history for a conversation.
     *
     * Reads exchanges from SQLite (user_prompt + assistant_text) for the
     * given conversation, ordered chronologically. Used by BridgeDispatcher
     * to populate restored tabs after IDE restart.
     *
     * Returns HistoryMessageDto list — each exchange produces up to two
     * messages (user prompt + assistant response). System messages are
     * not persisted and therefore not returned.
     *
     * @param conversationId The conversation to load
     * @return Messages in chronological order, or empty list if not found
     */
    fun loadConversationHistory(conversationId: String): List<BridgeMessage.HistoryMessageDto> {
        return try {
            val exchanges = storage.getExchangesForConversation(conversationId)

            exchanges.flatMap { exchange ->
                val messages = mutableListOf<BridgeMessage.HistoryMessageDto>()

                // User prompt (if stored)
                if (!exchange.userPrompt.isNullOrBlank()) {
                    messages.add(BridgeMessage.HistoryMessageDto(
                        role = "user",
                        content = exchange.userPrompt,
                        timestamp = exchange.timestamp,
                        exchangeId = null,
                        isStarred = false
                    ))
                }

                // Assistant response
                val assistantContent = exchange.assistantText ?: "[Response not cached]"
                messages.add(BridgeMessage.HistoryMessageDto(
                    role = "assistant",
                    content = assistantContent,
                    timestamp = exchange.timestamp,
                    exchangeId = exchange.id,
                    isStarred = exchange.isStarred,
                    promptTokens = exchange.promptTokens,
                    completionTokens = exchange.completionTokens,
                    totalTokens = exchange.totalTokens,
                    model = exchange.modelId
                ))

                messages
            }
        } catch (e: Exception) {
            Dev.warn(log, "orchestrator.load_history_failed", e,
                "conversationId" to conversationId
            )
            emptyList()
        }
    }

    // ── Private: Storage Persistence ─────────────────────────────────────

    /**
     * Save the raw exchange to storage (JSONL + SQLite row).
     *
     * This is the "save immediately" step — raw data is preserved before
     * any further processing. Even if token indexing, metadata extraction,
     * or conversation bookkeeping fails, the raw exchange is safe.
     *
     * @param response The provider's raw response
     * @param provider The provider that handled this request
     * @param purpose CHAT or FILE_SUMMARY (determines storage routing)
     */
    private suspend fun persistExchange(
        response: ProviderResponse,
        provider: AiProvider,
        purpose: ExchangePurpose,
        conversationId: String? = null
    ) {
        try {
            val exchange = AiExchange(
                id = response.exchangeId,
                timestamp = Instant.now(),
                providerId = provider.id,
                modelId = provider.displayName, // BUG (post-launch backlog #1): stores profile display name, not provider-reported model name. Fix at ingest time.
                purpose = purpose,
                request = ExchangeRequest(
                    input = response.prompt,
                    contextFiles = null // TODO: pass context file list from assembler
                ),
                rawResponse = ExchangeRawResponse(
                    json = response.rawJson ?: "",
                    httpStatus = response.httpStatus
                ),
                tokenUsage = null, // Filled in by indexTokenUsage() after parsing
                conversationId = conversationId
            )

            val projectId = storage.resolveProjectId()
            val savedId = storage.saveExchange(exchange, projectId)

            if (savedId != null) {
                Dev.info(log, "orchestrator.exchange_saved",
                    "exchangeId" to response.exchangeId,
                    "purpose" to purpose.name,
                    "rawLength" to (response.rawJson?.length ?: 0)
                )
            } else {
                Dev.warn(log, "orchestrator.exchange_not_saved", null,
                    "exchangeId" to response.exchangeId,
                    "reason" to "storage_disabled_or_failed"
                )
            }
        } catch (e: Exception) {
            // Storage failure should never break the chat flow.
            // The user still sees the response; it just won't be persisted.
            Dev.error(log, "orchestrator.exchange_save_failed", e,
                "exchangeId" to response.exchangeId
            )
        }
    }

    // ── Private: Metadata Indexing ────────────────────────────────────────
    //
    // Each indexing step is independent. If one fails, the others still run.
    // Raw data is already saved by persistExchange(), so these are "enrichment"
    // steps — nice to have, not critical.

    /**
     * Index token usage (prompt, completion, total) from the parsed response.
     *
     * Fills in the token columns that were left NULL by persistExchange().
     * This is the "index after save" pattern: raw data is safe first,
     * then we extract and index metadata from it.
     */
    private suspend fun indexTokenUsage(response: ProviderResponse) {
        val usage = response.parsed.metadata.tokenUsage ?: return

        try {
            val tokenUsage = ExchangeTokenUsage(
                promptTokens = usage.promptTokens,
                completionTokens = usage.completionTokens,
                totalTokens = usage.totalTokens
            )
            storage.updateTokenUsage(response.exchangeId, tokenUsage)
        } catch (e: Exception) {
            Dev.warn(log, "orchestrator.token_index_failed", e,
                "exchangeId" to response.exchangeId
            )
        }
    }

    /**
     * Cache the extracted assistant text for search and display.
     *
     * The assistant text is the actual AI-generated content (after parsing).
     * It's cached separately from the raw JSON for fast retrieval by the
     * search engine and Library view.
     */
    private suspend fun indexAssistantText(response: ProviderResponse) {
        val assistantText = response.parsed.rawText
        if (assistantText.isNullOrBlank()) return

        try {
            storage.cacheAssistantText(response.exchangeId, assistantText)
        } catch (e: Exception) {
            Dev.warn(log, "orchestrator.assistant_text_cache_failed", e,
                "exchangeId" to response.exchangeId
            )
        }
    }

    /**
     * Extract and store derived metadata (code blocks, topics, file paths, duplicate hash).
     *
     * Derived metadata powers features like:
     * - "Has code block" badge in the Library
     * - Topic detection for conversation search
     * - Duplicate exchange detection
     * - Code language breakdown for analytics
     */
    private suspend fun indexDerivedMetadata(response: ProviderResponse) {
        try {
            val derived = DerivedMetadata.extract(response.parsed.rawText, response.prompt)
            storage.updateDerivedMetadata(response.exchangeId, derived)
        } catch (e: Exception) {
            Dev.warn(log, "orchestrator.derived_metadata_failed", e,
                "exchangeId" to response.exchangeId
            )
        }
    }

    /**
     * Store the IDE context snapshot (what the developer was looking at).
     *
     * Only stored for CHAT exchanges — summaries are background tasks
     * with no meaningful editor context.
     */
    private suspend fun indexIdeContext(exchangeId: String, ideContext: IdeContext) {
        if (ideContext.isEmpty) return

        try {
            storage.updateIdeContext(exchangeId, ideContext)
        } catch (e: Exception) {
            Dev.warn(log, "orchestrator.ide_context_store_failed", e,
                "exchangeId" to exchangeId
            )
        }
    }

    // ── Private: IDE Context Capture ─────────────────────────────────────

    /**
     * Capture the current IDE state: open file, cursor position, selected text.
     *
     * This is called BEFORE the HTTP request to snapshot what the developer
     * was looking at when they sent the message. By the time the response
     * arrives, they may have switched files.
     *
     * @return IDE context snapshot, or [IdeContext.empty] if capture fails
     */
    private fun captureIdeContext(): IdeContext {
        return try {
            IdeContextCapture.capture(project)
        } catch (e: Exception) {
            Dev.warn(log, "orchestrator.ide_context_capture_failed", e)
            IdeContext.empty()
        }
    }

    // ── Private: Correction Flow ─────────────────────────────────────────

    /**
     * Run the three-scenario correction flow based on parse confidence.
     *
     * ## Scenarios
     *
     * 1. **Known format** (HIGH confidence, known schema): Display as-is.
     *    No correction needed.
     *
     * 2. **Heuristic + confident** (MEDIUM confidence, heuristic used):
     *    Display the response but offer "/correct" option. Store correction
     *    context so the user can fix it later.
     *
     * 3. **Low confidence** (LOW/NONE confidence, heuristic used):
     *    Show dialog BEFORE displaying. Let the user pick the right content.
     *    If cancelled, fall back to best guess and offer post-correction.
     *
     * @param response The provider's parsed response
     * @param providerId The provider that handled this request
     * @param modelId The model used (for format hint storage)
     * @return Triple of (displayText, isError, correctionAvailable)
     */
    private suspend fun runCorrectionFlow(
        response: ProviderResponse,
        providerId: String,
        modelId: String?
    ): Triple<String, Boolean, Boolean> {
        val parsed = response.parsed

        return when {
            // Scenario 3: Low confidence — ask user to pick before displaying
            correctionHelper.shouldAskImmediately(parsed) && parsed.metadata.candidates.isNotEmpty() -> {
                Dev.info(log, "orchestrator.correction_flow",
                    "scenario" to 3,
                    "strategy" to parsed.metadata.parseStrategy.name
                )

                val corrected = correctionHelper.handleImmediateCorrection(
                    result = parsed,
                    providerId = providerId,
                    modelId = modelId
                )

                if (corrected != null) {
                    // User picked the right content
                    Triple(corrected.displayText, false, false)
                } else {
                    // User cancelled — show best guess, offer post-correction
                    correctionHelper.storeForPostCorrection(parsed, providerId, modelId, force = true)
                    Triple(parsed.displayText, parsed.isError, true)
                }
            }

            // Scenario 2: Heuristic + confident — show with correction option
            correctionHelper.shouldOfferPostCorrection(parsed) -> {
                Dev.info(log, "orchestrator.correction_flow",
                    "scenario" to 2,
                    "strategy" to parsed.metadata.parseStrategy.name
                )

                correctionHelper.storeForPostCorrection(parsed, providerId, modelId)
                Triple(parsed.displayText, parsed.isError, true)
            }

            // Scenario 1: Known format — just display
            else -> {
                Dev.info(log, "orchestrator.correction_flow",
                    "scenario" to 1,
                    "strategy" to parsed.metadata.parseStrategy.name
                )

                Triple(parsed.displayText, parsed.isError, false)
            }
        }
    }

    // ── Private: Conversation Bookkeeping ────────────────────────────────

    /**
     * Create or update the conversation for this chat tab.
     *
     * On the first message, a new conversation is created. Subsequent messages
     * link their exchanges to the existing conversation and update its metadata.
     *
     * Bookkeeping failures are logged but never thrown — they shouldn't break the chat.
     *
     * @param userInput The user's message (used for title generation on first message)
     * @param response The provider response (for exchange linking)
     * @param providerId The active provider
     * @param modelId The active model
     * @return The conversation ID (existing or newly created), or null if bookkeeping failed
     */
    private fun updateConversation(
        userInput: String,
        response: ProviderResponse,
        providerId: String,
        modelId: String?
    ): String? {
        try {
            // Create conversation on first message (lazy creation)
            if (currentConversationId == null) {
                val conversation = conversationManager.createConversation(
                    firstPrompt = userInput,
                    providerId = providerId,
                    modelId = modelId ?: "unknown"
                )
                if (conversation != null) {
                    currentConversationId = conversation.id
                }
            }

            // Link exchange to conversation
            val convId = currentConversationId
            val exchId = response.exchangeId
            if (convId != null) {
                conversationManager.linkExchange(exchId, convId)
                conversationManager.onExchangeAdded(convId, providerId, modelId ?: "unknown")
            }

            return currentConversationId
        } catch (e: Exception) {
            Dev.warn(log, "orchestrator.conversation_bookkeeping_failed", e,
                "reason" to (e.message ?: "unknown")
            )
            return currentConversationId // Return whatever we have, even if update failed
        }
    }

    companion object {
        /**
         * Factory method for creating a ChatOrchestrator with default dependencies.
         *
         * Wires up all the standard dependencies. Use this for production.
         * For testing, construct directly with mock dependencies.
         *
         * @param project The IntelliJ project
         * @param correctionHelper The correction flow helper (owned per-tab)
         * @param summaryStore The summary store provider (based on user tier)
         * @return A fully wired ChatOrchestrator
         */
        fun create(
            project: Project,
            correctionHelper: CorrectionFlowHelper,
            summaryStore: com.youmeandmyself.ai.chat.context.SummaryStoreProvider
        ): ChatOrchestrator {
            return ChatOrchestrator(
                project = project,
                contextAssembler = ContextAssembler(
                    project,
                    summaryStore,
                    com.youmeandmyself.summary.config.SummaryConfigService.getInstance(project)
                ),
                correctionHelper = correctionHelper,
                conversationManager = ConversationManager.getInstance(project),
                storage = LocalStorageFacade.getInstance(project)
            )
        }
    }
}

/**
 * Result of a /correct command.
 *
 * Returned by [ChatOrchestrator.handleCorrection] to tell the UI what to display.
 *
 * @property displayText The corrected response text. Null if correction was cancelled or unavailable.
 * @property systemMessage A system-level message to show (e.g., "✓ Response corrected:" or "Correction cancelled.")
 */
data class CorrectionCommandResult(
    val displayText: String?,
    val systemMessage: String
)