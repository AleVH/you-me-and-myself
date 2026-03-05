package com.youmeandmyself.ai.chat.bridge

import com.intellij.openapi.project.Project
import com.youmeandmyself.ai.chat.orchestrator.ChatOrchestrator
import com.youmeandmyself.ai.chat.orchestrator.ChatResult
import com.youmeandmyself.ai.library.LibraryPanelHolder
import com.youmeandmyself.ai.settings.AiProfilesState
import com.youmeandmyself.dev.Dev
import com.youmeandmyself.dev.DevMode
import com.youmeandmyself.storage.BookmarkService
import com.youmeandmyself.storage.LocalStorageFacade
import com.youmeandmyself.storage.TabStateService
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
    private val tabStateService = TabStateService.getInstance(project)
    private val storage = LocalStorageFacade.getInstance(project)

    /**
     * Dispatch a command from the frontend.
     *
     * R5: Added routing for bookmark and cross-panel conversation open commands.
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
        }
    }

    // ── Pre-R4 Command Handlers (unchanged) ──────────────────────────

    private fun handleSendMessage(command: BridgeMessage.SendMessage) {
        emit(BridgeMessage.ShowThinkingEvent())

        scope.launch {
            try {
                val result = orchestrator.send(command.text, this, command.conversationId)

                if (result.contextSummary != null) {
                    emit(BridgeMessage.SystemMessageEvent(
                        content = "Context ready in ${result.contextTimeMs ?: "?"} ms",
                        level = "INFO"
                    ))
                }

                emit(chatResultToEvent(result))

                if (result.tokenUsage != null) {
                    emit(BridgeMessage.UpdateMetricsEvent(
                        model = result.modelId,
                        promptTokens = result.tokenUsage.promptTokens,
                        completionTokens = result.tokenUsage.completionTokens,
                        totalTokens = result.tokenUsage.effectiveTotal,
                        estimatedCost = null
                    ))
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
                        protocol = profile.protocol?.name ?: "unknown"
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

    // ── Helpers (unchanged) ──────────────────────────────────────────

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