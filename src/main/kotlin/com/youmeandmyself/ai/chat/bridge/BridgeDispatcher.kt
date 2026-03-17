package com.youmeandmyself.ai.chat.bridge

import com.intellij.openapi.project.Project
import com.youmeandmyself.ai.chat.orchestrator.ChatOrchestrator
import com.youmeandmyself.ai.chat.orchestrator.ChatResult
import com.youmeandmyself.ai.library.LibraryPanelHolder
import com.youmeandmyself.ai.settings.AiProfilesState
import com.youmeandmyself.dev.Dev
import com.youmeandmyself.dev.DevCommandHandler
import com.youmeandmyself.ai.providers.parsing.ui.CorrectionFlowHelper
import com.youmeandmyself.ai.metrics.DefaultContextWindows
import com.youmeandmyself.ai.metrics.MetricsService
import com.youmeandmyself.storage.LocalStorageFacade
import com.youmeandmyself.storage.TabStateService
import com.youmeandmyself.ai.settings.MetricsSettingsState
import com.youmeandmyself.tier.CompositeTierProvider
import com.youmeandmyself.tier.Feature
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Routes bridge commands to the ChatOrchestrator and sends events back.
 *
 * ## R5 Changes
 *
 * New command handlers:
 * - BookmarkCodeBlock: Bookmarks the entire exchange via the storage layer.
 *   Uses the existing bookmark/collection system — the code block ribbon in
 *   the chat creates a bookmark on the exchange, same as the Library star.
 * - OpenConversation: Opens a conversation in a new or existing tab.
 *   TEMPORARY: Part of the cross-panel bridge between vanilla HTML Library
 *   and React Chat. Remove when Library migrates to React.
 *
 * ## R4 Changes (preserved)
 *
 * - SwitchTab, CloseTab, SaveTabState, RequestTabState
 * - LoadConversation, ToggleStar
 *
 * ## Per-Tab Provider Changes
 *
 * - handleSendMessage: passes command.providerId to orchestrator.send() so
 *   each tab uses its own provider rather than the global selection.
 * - handleSwitchTabProvider: updates a single tab's provider_id in the
 *   open_tabs table without touching the global AiProfilesState selection.
 *
 * @param project The IntelliJ project context
 * @param orchestrator The chat orchestrator that processes all business logic
 * @param sendEvent Callback to send events back to the frontend
 */
class BridgeDispatcher(
    private val project: Project,
    private val orchestrator: ChatOrchestrator,
    private val sendEvent: EventSender
) {
    private val log = Dev.logger(BridgeDispatcher::class.java)
    private val scope = CoroutineScope(Dispatchers.IO)

    init {
        Dev.info(log, "bridge.version", "block" to "5")
    }
    private val tabStateService = TabStateService.getInstance(project)
    private val storage = LocalStorageFacade.getInstance(project)
    private val devCommandHandler: DevCommandHandler by lazy {
        DevCommandHandler(
            project = project,
            correctionHelper = orchestrator.correctionHelper,
            scope = scope,
            sendEvent = sendEvent
        )
    }

    /**
     * Dispatch a command from the frontend.
     */
    fun dispatch(command: BridgeMessage.Command) {
        Dev.info(log, "bridge.dispatch", "type" to command.type)

        when (command) {
            // Pre-R4 commands
            is BridgeMessage.SendMessage -> handleSendMessage(command)
            is BridgeMessage.ConfirmCorrection -> handleConfirmCorrection()
            is BridgeMessage.RequestCorrection -> handleRequestCorrection()
            is BridgeMessage.ClearChat -> handleClearChat()
            is BridgeMessage.NewConversation -> handleNewConversation()
            is BridgeMessage.SwitchProvider -> handleSwitchProvider(command)
            is BridgeMessage.RequestProviders -> handleRequestProviders()
            // Per-tab provider
            is BridgeMessage.SwitchTabProvider -> handleSwitchTabProvider(command)
            // R4: Tab management
            is BridgeMessage.SwitchTab -> handleSwitchTab(command)
            is BridgeMessage.CloseTab -> handleCloseTab(command)
            is BridgeMessage.SaveTabState -> handleSaveTabState(command)
            is BridgeMessage.RequestTabState -> handleRequestTabState()
            // R4: Conversation history
            is BridgeMessage.LoadConversation -> handleLoadConversation(command)
            // R4: Exchange starring
            is BridgeMessage.ToggleStar -> handleToggleStar(command)
            // R5: Code block bookmark
            is BridgeMessage.BookmarkCodeBlock -> handleBookmarkCodeBlock(command)
            // R5: Cross-panel conversation open (TEMPORARY)
            is BridgeMessage.OpenConversation -> handleOpenConversation(command)
            // Dev commands
            is BridgeMessage.DevCommand -> handleDevCommand(command)
            // Block 5C: Frontend logging — route React logs to idea.log
            is BridgeMessage.FrontendLog -> handleFrontendLog(command)
        }
    }

    // ── Pre-R4 Command Handlers ──────────────────────────────────────

    /**
     * Handle SEND_MESSAGE — process a user chat message through the pipeline.
     *
     * Passes command.providerId to orchestrator.send() so the pipeline uses
     * the tab's selected provider rather than the global selection.
     * If providerId is null (old clients, or tab with no per-tab provider set),
     * the orchestrator falls back to the globally selected chat provider.
     */
    private fun handleSendMessage(command: BridgeMessage.SendMessage) {
        emit(BridgeMessage.ShowThinkingEvent())

        scope.launch {
            try {
                val result = orchestrator.send(
                    userInput = command.text,
                    scope = this,
                    conversationId = command.conversationId,
                    providerId = command.providerId,
                    bypassMode = command.bypassMode
                )

                if (result.contextSummary != null) {
                    emit(BridgeMessage.SystemMessageEvent(
                        content = "Context ready in ${result.contextTimeMs ?: "?"} ms",
                        level = "INFO"
                    ))
                }

                emit(chatResultToEvent(result))

                // ── Metrics Module: record + conditionally emit ──────
                // Step 1: ALWAYS record to SQLite (data collection is
                //         unconditional — we want the data even if the
                //         user hides the bar or is on a lower tier).
                val metricsEvent = MetricsService.getInstance(project).recordExchange(
                    exchangeId = result.exchangeId ?: "unknown",
                    conversationId = result.conversationId,
                    providerId = result.providerId ?: "unknown",
                    tokenUsage = result.tokenUsage,
                    modelId = result.modelId,
                    purpose = "CHAT",
                    responseTimeMs = result.responseTimeMs
                )

                // Step 2: Only emit to frontend if:
                // - The tier supports metrics display (METRICS_BASIC)
                // - The user hasn't hidden the metrics bar in settings
                if (metricsEvent != null) {
//                    val tierAllowed = CompositeTierProvider.getInstance()
//                        .canUse(Feature.METRICS_BASIC)
                    val tierAllowed = try {
                        CompositeTierProvider.getInstance().canUse(Feature.METRICS_BASIC)
                    } catch (e: Exception) {
                        Dev.warn(log, "metrics.tier_check_failed", e)
                        true // Default to allowed if tier system is broken
                    }
                    val userEnabled = MetricsSettingsState.getInstance(project)
                        .state.showMetricsBar

                    if (tierAllowed && userEnabled) {
                        emit(metricsEvent)
                    } else {
                        Dev.info(log, "metrics.emit_skipped",
                            "tierAllowed" to tierAllowed,
                            "userEnabled" to userEnabled
                        )
                    }
                }

                if (result.correctionAvailable) {
                    emit(BridgeMessage.SystemMessageEvent(
                        content = "Response auto-detected. Use the buttons above to confirm or correct.",
                        level = "INFO"
                    ))
                }

                LibraryPanelHolder.get(project)?.refresh()

            } catch (t: Throwable) {
                Dev.error(log, "bridge.send_failed", t)
                emit(BridgeMessage.HideThinkingEvent())
                emit(BridgeMessage.ChatResultEvent(
                    displayText = "Error: ${t.message}",
                    isError = true,
                    exchangeId = null,
                    conversationId = null,
                    correctionAvailable = false,
                    parseStrategy = "FAILED",
                    confidence = "HIGH",
                    providerId = null,
                    modelId = null,
                    contextSummary = null,
                    contextTimeMs = null,
                    tokenUsage = null
                ))
            }
        }
    }

    private fun handleConfirmCorrection() {
        scope.launch {
            try {
                val confirmed = orchestrator.confirmCorrection()
                if (confirmed) {
                    emit(BridgeMessage.SystemMessageEvent(
                        content = "✓ Format confirmed. Future responses from this provider will be parsed automatically.",
                        level = "INFO"
                    ))
                }
            } catch (t: Throwable) {
                Dev.error(log, "bridge.confirm_correction_failed", t)
            }
        }
    }

    private fun handleRequestCorrection() {
        scope.launch {
            try {
                val result = orchestrator.handleCorrection()
                if (result != null) {
                    emit(BridgeMessage.SystemMessageEvent(
                        content = result.systemMessage,
                        level = "INFO"
                    ))

                    if (result.displayText != null) {
                        emit(BridgeMessage.ChatResultEvent(
                            displayText = result.displayText,
                            isError = false,
                            exchangeId = null,
                            conversationId = null,
                            correctionAvailable = false,
                            parseStrategy = "CORRECTED",
                            confidence = "HIGH",
                            providerId = null,
                            modelId = null,
                            contextSummary = null,
                            contextTimeMs = null,
                            tokenUsage = null
                        ))
                    }
                }
            } catch (t: Throwable) {
                Dev.error(log, "bridge.request_correction_failed", t)
            }
        }
    }

    private fun handleClearChat() {
        emit(BridgeMessage.ConversationClearedEvent())
    }

    private fun handleNewConversation() {
        orchestrator.setConversation(null)
        emit(BridgeMessage.ConversationClearedEvent())
    }

    /**
     * Handle SWITCH_PROVIDER — update the global chat provider selection.
     *
     * This updates AiProfilesState.selectedChatProfileId, which is the
     * fallback provider for tabs that have no per-tab provider set.
     * For per-tab provider changes, see handleSwitchTabProvider().
     */
    private fun handleSwitchProvider(command: BridgeMessage.SwitchProvider) {
        try {
            val ps = AiProfilesState.getInstance(project)
            ps.selectedChatProfileId = command.providerId
            Dev.info(log, "bridge.provider_switched", "providerId" to command.providerId)
        } catch (t: Throwable) {
            Dev.error(log, "bridge.switch_provider_failed", t)
            emit(BridgeMessage.SystemMessageEvent(
                content = "Failed to switch provider: ${t.message}",
                level = "ERROR"
            ))
        }
    }

    private fun handleRequestProviders() {
        try {
            val ps = AiProfilesState.getInstance(project)

            val chatProfiles = ps.profiles
                .filter { it.roles.chat }
                .filter {
                    it.apiKey.isNotBlank() &&
                            it.baseUrl.isNotBlank() &&
                            it.model?.isNotBlank() == true
                }
                .map { profile ->
                    BridgeMessage.ProviderInfoDto(
                        id = profile.id,
                        label = profile.label.ifBlank { "Generic LLM" },
                        protocol = profile.protocol?.name ?: "unknown",
                        // Metrics Module: send model name and resolved context window
                        // so the frontend can show model info and compute the fill bar
                        // even before the first AI exchange in this session.
                        model = profile.model,
                        contextWindowSize = DefaultContextWindows.resolve(
                            profileContextWindowSize = profile.contextWindowSize,
                            modelName = profile.model
                        )
                    )
                }

            emit(BridgeMessage.ProvidersListEvent(
                providers = chatProfiles,
                selectedId = ps.selectedChatProfileId
            ))
        } catch (t: Throwable) {
            Dev.error(log, "bridge.request_providers_failed", t)
        }
    }

    // ── Per-Tab Provider Handler ─────────────────────────────────────

    /**
     * Handle SWITCH_TAB_PROVIDER — update the provider for a specific tab.
     *
     * Unlike handleSwitchProvider (which updates the global fallback),
     * this updates only the provider_id column for the given tab in
     * open_tabs. The global AiProfilesState.selectedChatProfileId is
     * NOT touched.
     *
     * The updated providerId is then used by handleSendMessage when the
     * next SEND_MESSAGE arrives with this tab's conversationId — the
     * frontend always includes the active tab's providerId in SendMessage.
     *
     * Persistence: the tab's providerId is already included in TabStateDto
     * (sent via SAVE_TAB_STATE), so it survives IDE restarts through the
     * normal tab persistence path. This handler updates the in-DB record
     * immediately so it's correct even if the IDE crashes before the next
     * SAVE_TAB_STATE.
     */
    private fun handleSwitchTabProvider(command: BridgeMessage.SwitchTabProvider) {
        Dev.info(log, "bridge.switch_tab_provider",
            "tabId" to command.tabId,
            "providerId" to command.providerId
        )
        scope.launch {
            try {
                tabStateService.updateTabProvider(command.tabId, command.providerId)
            } catch (t: Throwable) {
                Dev.error(log, "bridge.switch_tab_provider_failed", t,
                    "tabId" to command.tabId,
                    "providerId" to command.providerId
                )
                emit(BridgeMessage.SystemMessageEvent(
                    content = "Failed to update tab provider: ${t.message}",
                    level = "ERROR"
                ))
            }
        }
    }

    // ── R4: Tab Management Handlers ──────────────────────────────────

    /**
     * Handle SWITCH_TAB — user clicked a different tab.
     *
     * Updates the orchestrator's active conversation to match the tab's
     * conversationId. If the tab has no conversation yet (fresh tab),
     * the orchestrator resets to null so the next SEND_MESSAGE creates one.
     */
    private fun handleSwitchTab(command: BridgeMessage.SwitchTab) {
        Dev.info(log, "bridge.switch_tab", "tabId" to command.tabId)

        scope.launch {
            try {
                val tabs = tabStateService.loadAll()
                val tab = tabs.find { it.id == command.tabId }
                if (tab?.conversationId != null) {
                    orchestrator.setConversation(tab.conversationId)
                } else {
                    // Fresh tab with no conversation yet — reset orchestrator
                    orchestrator.setConversation(null)
                }
            } catch (t: Throwable) {
                Dev.error(log, "bridge.switch_tab_failed", t)
            }
        }
    }

    /**
     * Handle CLOSE_TAB — user closed a tab.
     *
     * Removes the tab from persisted state. The conversation itself is NOT
     * deleted — it remains accessible from the Library panel.
     */
    private fun handleCloseTab(command: BridgeMessage.CloseTab) {
        Dev.info(log, "bridge.close_tab", "tabId" to command.tabId)
        scope.launch {
            try {
                tabStateService.removeTab(command.tabId)
            } catch (t: Throwable) {
                Dev.error(log, "bridge.close_tab_failed", t)
            }
        }
    }

    /**
     * Handle SAVE_TAB_STATE — frontend persists its current tab layout.
     *
     * Receives the complete tab state and writes it to the open_tabs table.
     * Uses full replace strategy (delete all + insert) to avoid sync issues.
     * Each TabStateDto now includes providerId for per-tab provider persistence.
     */
    private fun handleSaveTabState(command: BridgeMessage.SaveTabState) {
        scope.launch {
            try {
                Dev.info(log, "bridge.save_tab_state",
                    "tabCount" to command.tabs.size,
                    "activeTabId" to command.activeTabId
                )
                tabStateService.saveAll(command.tabs)
            } catch (t: Throwable) {
                Dev.error(log, "bridge.save_tab_state_failed", t)
            }
        }
    }

    /**
     * Handle REQUEST_TAB_STATE — frontend wants saved tabs on startup.
     *
     * Checks the keep_tabs setting in storage_config. If enabled, reads
     * open_tabs and sends them back. If disabled, sends empty list so
     * the frontend creates a single fresh tab.
     *
     * Each returned TabStateDto includes the tab's providerId so the
     * frontend can restore the per-tab provider selection.
     */
    private fun handleRequestTabState() {
        scope.launch {
            try {
                val keepTabs = tabStateService.isKeepTabsEnabled()
                val tabs = if (keepTabs) tabStateService.loadAll() else emptyList()

                emit(BridgeMessage.TabStateEvent(
                    tabs = tabs,
                    keepTabs = keepTabs
                ))
            } catch (t: Throwable) {
                Dev.error(log, "bridge.request_tab_state_failed", t)
                // Fallback: send empty state so the UI doesn't hang
                emit(BridgeMessage.TabStateEvent(
                    tabs = emptyList(),
                    keepTabs = true
                ))
            }
        }
    }

    // ── R4: Conversation History Handler ─────────────────────────────

    /**
     * Handle LOAD_CONVERSATION — frontend needs messages for a restored tab.
     *
     * Reads the conversation's exchanges from storage (JSONL/SQLite) and
     * maps them to HistoryMessageDto for the frontend to display.
     */
    private fun handleLoadConversation(command: BridgeMessage.LoadConversation) {
        scope.launch {
            try {
                Dev.info(log, "bridge.load_conversation",
                    "conversationId" to command.conversationId,
                    "tabId" to command.tabId
                )

                val messages = orchestrator.loadConversationHistory(command.conversationId)

                emit(BridgeMessage.ConversationHistoryEvent(
                    tabId = command.tabId,
                    conversationId = command.conversationId,
                    messages = messages
                ))
            } catch (t: Throwable) {
                Dev.error(log, "bridge.load_conversation_failed", t)
                // Send empty history so the tab doesn't hang in loading state
                emit(BridgeMessage.ConversationHistoryEvent(
                    tabId = command.tabId,
                    conversationId = command.conversationId,
                    messages = emptyList()
                ))
                emit(BridgeMessage.SystemMessageEvent(
                    content = "Failed to load conversation history: ${t.message}",
                    level = "ERROR"
                ))
            }
        }
    }

    // ── R4: Exchange Starring Handler ─────────────────────────────────

    /**
     * Handle TOGGLE_STAR — user starred/unstarred an assistant response.
     *
     * Toggles the star state in storage and sends back confirmation.
     * Stars are stored as a flag on the chat_exchanges table.
     */
    private fun handleToggleStar(command: BridgeMessage.ToggleStar) {
        scope.launch {
            try {
                Dev.info(log, "bridge.toggle_star", "exchangeId" to command.exchangeId)

                val newState = storage.toggleStar(command.exchangeId)

                emit(BridgeMessage.StarUpdatedEvent(
                    exchangeId = command.exchangeId,
                    isStarred = newState
                ))

                LibraryPanelHolder.get(project)?.refresh()

            } catch (t: Throwable) {
                Dev.error(log, "bridge.toggle_star_failed", t)
            }
        }
    }

    // ── R5: Code Block Bookmark Handler ──────────────────────────────

    /**
     * Handle BOOKMARK_CODE_BLOCK — user clicked the bookmark ribbon on a code block.
     *
     * Delegates to the existing bookmark system in the storage layer.
     * The bookmark applies to the entire exchange, not just the code block.
     * This uses the same storage path as the Library's bookmark toggle.
     */
    private fun handleBookmarkCodeBlock(command: BridgeMessage.BookmarkCodeBlock) {
        Dev.info(log, "bridge.bookmark_code_block.stubbed",
            "exchangeId" to command.exchangeId,
            "blockIndex" to command.blockIndex
        )
        emit(BridgeMessage.BookmarkResultEvent(
            exchangeId = command.exchangeId,
            success = false,
            error = "Code block bookmarks coming soon"
        ))
    }

    // ── R5: Cross-Panel Conversation Open Handler (TEMPORARY) ────────

    /**
     * Handle OPEN_CONVERSATION — Library wants to open a conversation in Chat.
     *
     * TEMPORARY: This handler is part of the cross-panel bridge between the
     * vanilla HTML Library and the React Chat. Remove when Library migrates
     * to React and both panels share the same BridgeDispatcher.
     *
     * Strategy:
     * 1. Check if any open tab already has this conversationId → switch to it
     * 2. If not, create a new tab with this conversationId → load history
     *
     * The frontend handles tab creation on receiving OpenConversationResultEvent.
     * The handler also switches the orchestrator to the correct conversation
     * so subsequent SEND_MESSAGE commands route correctly.
     */
    private fun handleOpenConversation(command: BridgeMessage.OpenConversation) {
        scope.launch {
            try {
                Dev.info(log, "bridge.open_conversation",
                    "conversationId" to command.conversationId
                )

                // Check if a tab with this conversation is already open
                val existingTabs = tabStateService.loadAll()
                val existingTab = existingTabs.find { it.conversationId == command.conversationId }

                if (existingTab != null) {
                    // Tab already open — just switch to it
                    Dev.info(log, "bridge.open_conversation.existing_tab",
                        "tabId" to existingTab.id
                    )
                    orchestrator.setConversation(command.conversationId)

                    val title = storage.getConversationTitle(command.conversationId) ?: "New Chat"
                    emit(BridgeMessage.OpenConversationResultEvent(
                        conversationId = command.conversationId,
                        tabId = existingTab.id,
                        title = title
                    ))
                } else {
                    // No existing tab — frontend will create one.
                    // Generate a tab ID here so both sides agree.
                    val newTabId = "tab-${System.currentTimeMillis()}-${
                        (Math.random() * 36.0).toInt().toString(36).take(4)
                    }"

                    orchestrator.setConversation(command.conversationId)

                    val title = storage.getConversationTitle(command.conversationId) ?: "New Chat"
                    emit(BridgeMessage.OpenConversationResultEvent(
                        conversationId = command.conversationId,
                        tabId = newTabId,
                        title = title
                    ))

                    // Also send conversation history for the new tab
                    val messages = orchestrator.loadConversationHistory(command.conversationId)

                    emit(BridgeMessage.ConversationHistoryEvent(
                        tabId = newTabId,
                        conversationId = command.conversationId,
                        messages = messages
                    ))
                }
            } catch (t: Throwable) {
                Dev.error(log, "bridge.open_conversation_failed", t)
                emit(BridgeMessage.SystemMessageEvent(
                    content = "Failed to open conversation: ${t.message}",
                    level = "ERROR"
                ))
            }
        }
    }

    // ── Dev: Command Handler ─────────────────────────────────────

    /**
     * Handle DEV_COMMAND — route /dev-* commands to DevCommandHandler.
     *
     * DevCommandHandler output goes back through DEV_OUTPUT events,
     * rendered as system messages in the chat UI.
     */
    private fun handleDevCommand(command: BridgeMessage.DevCommand) {
        if (!com.youmeandmyself.dev.DevMode.isEnabled()) {
            emit(BridgeMessage.DevOutputEvent(
                content = "Dev mode is not enabled. Start IDE with -Dymm.devMode=true"
            ))
            return
        }

        devCommandHandler.handleIfDevCommand(command.text)
    }

    // ── Block 5C: Frontend Logging Handler ─────────────────────────

    /**
     * Handle FRONTEND_LOG — route React-side logs to idea.log.
     *
     * console.log is dead inside JCEF (no DevTools in production). The
     * frontend's log.ts utility sends FRONTEND_LOG commands instead.
     * We route them to the standard IDE logging system via [Dev].
     *
     * Tagged as "react.{source}" for easy filtering in idea.log:
     *   react.useBridge — CHAT_RESULT received {exchangeId=abc}
     *
     * No event is sent back — this is fire-and-forget.
     */
    private fun handleFrontendLog(command: BridgeMessage.FrontendLog) {
        val tag = "react.${command.source}"
        when (command.level.uppercase()) {
            "WARN" -> Dev.warn(log, tag, null, "msg" to command.message)
            "ERROR" -> Dev.error(log, tag, null, "msg" to command.message)
            else -> Dev.info(log, tag, "msg" to command.message)
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private fun chatResultToEvent(result: ChatResult): BridgeMessage.ChatResultEvent {
        return BridgeMessage.ChatResultEvent(
            displayText = result.displayText,
            isError = result.isError,
            exchangeId = result.exchangeId,
            conversationId = result.conversationId,
            correctionAvailable = result.correctionAvailable,
            parseStrategy = result.parseStrategy.name,
            confidence = result.confidence.name,
            providerId = result.providerId,
            modelId = result.modelId,
            contextSummary = result.contextSummary,
            contextTimeMs = result.contextTimeMs,
            tokenUsage = result.tokenUsage?.let {
                BridgeMessage.TokenUsageDto(
                    promptTokens = it.promptTokens,
                    completionTokens = it.completionTokens,
                    totalTokens = it.effectiveTotal
                )
            }
        )
    }

    private fun emit(event: BridgeMessage.Event) {
        try {
            val json = BridgeMessage.serializeEvent(event)
            sendEvent(json)
        } catch (t: Throwable) {
            Dev.error(log, "bridge.emit_failed", t,
                "eventType" to event.type
            )
        }
    }
}

typealias EventSender = (jsonString: String) -> Unit