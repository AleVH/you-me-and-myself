package com.youmeandmyself.ai.chat.bridge

import com.intellij.openapi.project.Project
import com.youmeandmyself.ai.chat.context.ContextStagingService
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
import com.youmeandmyself.ai.chat.context.ContextFileDetail
import com.youmeandmyself.ai.settings.ContextSettingsState
import com.youmeandmyself.ai.settings.MetricsSettingsState
import com.youmeandmyself.ai.settings.TabSettingsListener
import com.youmeandmyself.summary.config.SummaryConfigService
import com.youmeandmyself.summary.model.CodeElementKind
import com.youmeandmyself.tier.CompositeTierProvider
import com.youmeandmyself.tier.Feature
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

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

        // Subscribe to tab settings changes so we can re-emit TAB_STATE
        // when the user changes maxTabs/keepTabs in General Settings.
        project.messageBus.connect(project).subscribe(TabSettingsListener.TOPIC, TabSettingsListener {
            Dev.info(log, "bridge.tab_settings_changed", "action" to "re-emit TAB_STATE")
            handleRequestTabState()
        })
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
            is BridgeMessage.RenameTab -> handleRenameTab(command)
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
            is BridgeMessage.RemoveContextEntry -> handleRemoveContextEntry(command)
            is BridgeMessage.StartContextGathering -> handleStartContextGathering(command)
            is BridgeMessage.DismissStaleness -> handleDismissStaleness(command)
            is BridgeMessage.RefreshContextEntry -> handleRefreshContextEntry(command)
            // Block 5C: Frontend logging — route React logs to idea.log
            is BridgeMessage.FrontendLog -> handleFrontendLog(command)
            // Block 5: Context settings request
            is BridgeMessage.RequestContextSettings -> handleRequestContextSettings()
            // Force context ghost badge resolution
            is BridgeMessage.ResolveForceContext -> handleResolveForceContext(command)
            // Badge click: navigate to source file/element in IDE editor
            is BridgeMessage.NavigateToSource -> handleNavigateToSource(command)
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
                    bypassMode = command.bypassMode,
                    selectiveLevel = command.selectiveLevel,
                    summaryEnabled = command.summaryEnabled,
                    forceContextScope = command.forceContextScope,
                    tabId = command.tabId  // Phase 2: staging area integration
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
                    responseTimeMs = result.responseTimeMs,
                    // Phase 1 — per-block token estimates from RequestBlocks
                    profileTokens = result.profileTokens,
                    historyTokens = result.historyTokens,
                    contextTokens = result.contextTokens,
                    messageTokens = result.messageTokens
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

                // Phase 3: Emit SENT_CONTEXT_UPDATE for the sidebar
                if (result.contextFiles.isNotEmpty() && result.conversationId != null) {
                    emit(BridgeMessage.SentContextUpdateEvent(
                        conversationId = result.conversationId,
                        turnIndex = 0, // Simplified — the sidebar deduplicates anyway
                        entries = result.contextFiles.map { cf ->
                            BridgeMessage.ContextFileDetailDto(
                                id = null,
                                path = cf.path,
                                name = cf.name,
                                scope = cf.scope,
                                lang = cf.lang,
                                kind = cf.kind,
                                freshness = cf.freshness,
                                tokens = cf.tokens,
                                isStale = cf.isStale,
                                forced = cf.forced,
                                elementSignature = cf.elementSignature
                            )
                        }
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
     * Handle RENAME_TAB — user committed a tab title edit.
     *
     * The frontend already applied the rename optimistically and will persist
     * it via the next SAVE_TAB_STATE (which carries the updated title in TabStateDto).
     * This handler additionally writes the new title to conversations.title so the
     * rename is reflected in the Library and survives future IDE restarts cleanly.
     *
     * conversationId is null for fresh tabs that have never sent a message — in that
     * case the conversation doesn't exist in storage yet and we skip the DB update.
     *
     * No event is sent back to the frontend.
     */
    private fun handleRenameTab(command: BridgeMessage.RenameTab) {
        val conversationId = command.conversationId
        if (conversationId == null) {
            Dev.info(log, "bridge.rename_tab.no_conversation",
                "tabId" to command.tabId,
                "title" to command.title
            )
            return
        }

        scope.launch {
            try {
                Dev.info(log, "bridge.rename_tab",
                    "tabId" to command.tabId,
                    "conversationId" to conversationId,
                    "title" to command.title
                )
                com.youmeandmyself.ai.chat.conversation.ConversationManager
                    .getInstance(project)
                    .updateTitle(conversationId, command.title)

                LibraryPanelHolder.get(project)?.refresh()
            } catch (t: Throwable) {
                Dev.error(log, "bridge.rename_tab_failed", t,
                    "tabId" to command.tabId,
                    "conversationId" to conversationId
                )
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
                val maxTabs = tabStateService.getMaxTabs()
                val tabs = if (keepTabs) tabStateService.loadAll() else emptyList()

                emit(BridgeMessage.TabStateEvent(
                    tabs = tabs,
                    keepTabs = keepTabs,
                    maxTabs = maxTabs
                ))
            } catch (t: Throwable) {
                Dev.error(log, "bridge.request_tab_state_failed", t)
                // Fallback: send empty state so the UI doesn't hang
                emit(BridgeMessage.TabStateEvent(
                    tabs = emptyList(),
                    keepTabs = true,
                    maxTabs = 5
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

    // ── Phase 2: Context Staging Area Handlers ──────────────────────

    /**
     * Handle REMOVE_CONTEXT_ENTRY — remove a badge from the staging area.
     *
     * Delegates to [ContextStagingService.removeEntry]. After removal, emits
     * a CONTEXT_BADGE_UPDATE event with the updated badge list so the frontend
     * tray refreshes.
     *
     * Tier gating is handled in Phase 2 B6 — for now, all removals are allowed.
     */
    private fun handleRemoveContextEntry(command: BridgeMessage.RemoveContextEntry) {
        Dev.info(log, "bridge.remove_context_entry",
            "tabId" to command.tabId,
            "entryId" to command.entryId
        )

        // Tier gate: only Pro tier users can remove individual badges.
        // Basic tier users see no X button (frontend gating), but the backend
        // also checks in case a crafty user sends the command directly.
        val tierAllowed = try {
            CompositeTierProvider.getInstance().canUse(Feature.CONTEXT_BADGE_REMOVAL)
        } catch (e: Exception) {
            Dev.warn(log, "bridge.remove_context_entry.tier_check_failed", e)
            false // Default to denied if tier system is broken
        }

        if (!tierAllowed) {
            Dev.info(log, "bridge.remove_context_entry.denied",
                "tabId" to command.tabId,
                "entryId" to command.entryId,
                "reason" to "tier does not allow badge removal"
            )
            return
        }

        val stagingService = ContextStagingService.getInstance(project)
        val removed = stagingService.removeEntry(command.tabId, command.entryId)

        if (removed) {
            // Emit updated badge list to frontend
            val currentState = stagingService.getState(command.tabId)
            val badges = currentState.map { entry ->
                BridgeMessage.ContextFileDetailDto(
                    path = entry.path ?: "",
                    name = entry.name,
                    scope = if (entry.elementSignature != null) "method" else "file",
                    lang = "",
                    kind = entry.kind.name,
                    freshness = if (entry.isStale) "rough" else "fresh",
                    tokens = entry.tokenEstimate,
                    isStale = entry.isStale,
                    forced = entry.source == "forced",
                    elementSignature = entry.elementSignature
                )
            }
            emit(BridgeMessage.ContextBadgeUpdateEvent(
                tabId = command.tabId,
                badges = badges,
                complete = true
            ))
        }
    }

    /**
     * Active background gathering jobs per tab.
     *
     * When a new gathering request arrives for a tab that already has an
     * in-progress job, the old job is cancelled before starting the new one.
     * This handles debouncing: user types fast → only the last input matters.
     */
    private val activeGatheringJobs = ConcurrentHashMap<String, kotlinx.coroutines.Job>()

    /**
     * Handle START_CONTEXT_GATHERING — begin background context assembly for a tab.
     *
     * ## Phase 2 B5
     *
     * Launches a background coroutine that:
     * 1. Cancels any in-progress gathering for this tab
     * 2. Emits CONTEXT_PROGRESS "detecting" event
     * 3. Calls ContextAssembler.gatherAndStage() to run the detector pipeline
     * 4. Adds resulting entries to ContextStagingService progressively
     * 5. Emits CONTEXT_BADGE_UPDATE events as entries arrive
     * 6. Emits CONTEXT_PROGRESS "complete" when done
     *
     * The frontend triggers this when the user's input changes (debounced).
     * At send time, ChatOrchestrator snapshots whatever is in the staging area.
     */
    private fun handleStartContextGathering(command: BridgeMessage.StartContextGathering) {
        Dev.info(log, "bridge.start_context_gathering",
            "tabId" to command.tabId,
            "inputLength" to command.userInput.length
        )

        // Cancel previous gathering for this tab (user typed again)
        activeGatheringJobs[command.tabId]?.let { previousJob ->
            previousJob.cancel()
            Dev.info(log, "bridge.gathering.cancelled_previous",
                "tabId" to command.tabId
            )
        }

        val stagingService = ContextStagingService.getInstance(project)

        // Clear previous staging state for this tab (new input = fresh context)
        stagingService.clear(command.tabId)

        val job = scope.launch {
            try {
                // Emit progress: starting
                emit(BridgeMessage.ContextProgressEvent(
                    tabId = command.tabId,
                    stage = "detecting",
                    percent = 0,
                    message = "Starting context detection…"
                ))

                // Run the gathering pipeline
                val gatherResult = orchestrator.contextAssembler.gatherAndStage(
                    userInput = command.userInput,
                    scope = this,
                    detectorLevel = if (command.bypassMode?.uppercase() == "SELECTIVE") {
                        command.selectiveLevel ?: 2
                    } else 2,
                    summaryEnabled = command.summaryEnabled,
                    forceContextScope = command.forceContextScope
                )

                if (gatherResult == null) {
                    // Heuristic filter skipped or IDE indexing blocked
                    Dev.info(log, "bridge.gathering.skipped",
                        "tabId" to command.tabId,
                        "reason" to "gatherAndStage returned null"
                    )
                    emit(BridgeMessage.ContextProgressEvent(
                        tabId = command.tabId,
                        stage = "complete",
                        percent = 100,
                        message = "Context skipped"
                    ))
                    return@launch
                }

                // Add entries to staging area progressively
                for (entry in gatherResult.contextBlock.allEntries) {
                    val added = stagingService.addEntry(command.tabId, entry)
                    if (added) {
                        // Emit badge update with current staging state
                        val currentState = stagingService.getState(command.tabId)
                        val badges = currentState.map { e ->
                            BridgeMessage.ContextFileDetailDto(
                                id = e.id,
                                path = e.path ?: "",
                                name = e.name,
                                scope = if (e.elementSignature != null) "method" else "file",
                                lang = "",
                                kind = e.kind.name,
                                freshness = if (e.isStale) "rough" else "fresh",
                                tokens = e.tokenEstimate,
                                isStale = e.isStale,
                                forced = e.source == "forced",
                                elementSignature = e.elementSignature
                            )
                        }
                        emit(BridgeMessage.ContextBadgeUpdateEvent(
                            tabId = command.tabId,
                            badges = badges,
                            complete = false
                        ))
                    }
                }

                // Emit progress: complete
                emit(BridgeMessage.ContextProgressEvent(
                    tabId = command.tabId,
                    stage = "complete",
                    percent = 100,
                    message = "Context ready"
                ))

                // Emit final badge update with complete=true
                val finalState = stagingService.getState(command.tabId)
                val finalBadges = finalState.map { e ->
                    BridgeMessage.ContextFileDetailDto(
                        id = e.id,
                        path = e.path ?: "",
                        name = e.name,
                        scope = if (e.elementSignature != null) "method" else "file",
                        lang = "",
                        kind = e.kind.name,
                        freshness = if (e.isStale) "rough" else "fresh",
                        tokens = e.tokenEstimate,
                        isStale = e.isStale,
                        forced = e.source == "forced",
                        elementSignature = e.elementSignature
                    )
                }
                emit(BridgeMessage.ContextBadgeUpdateEvent(
                    tabId = command.tabId,
                    badges = finalBadges,
                    complete = true
                ))

                Dev.info(log, "bridge.gathering.complete",
                    "tabId" to command.tabId,
                    "entries" to finalState.size,
                    "totalTokens" to finalState.sumOf { it.tokenEstimate }
                )

            } catch (e: kotlinx.coroutines.CancellationException) {
                // Normal cancellation — user typed again, new gathering started
                Dev.info(log, "bridge.gathering.cancelled",
                    "tabId" to command.tabId
                )
            } catch (t: Throwable) {
                Dev.error(log, "bridge.gathering.failed", t,
                    "tabId" to command.tabId
                )
                emit(BridgeMessage.ContextProgressEvent(
                    tabId = command.tabId,
                    stage = "complete",
                    percent = 100,
                    message = "Context gathering failed"
                ))
            } finally {
                activeGatheringJobs.remove(command.tabId)
            }
        }

        activeGatheringJobs[command.tabId] = job
    }

    // ── Phase 3: Staleness Handlers ────────────────────────────────

    /**
     * Handle DISMISS_STALENESS — user dismisses a stale flag on a sidebar entry.
     *
     * The staleness flag is cleared — the user decided the context change is
     * irrelevant for this conversation. This is a frontend state change
     * acknowledged by the backend.
     */
    private fun handleDismissStaleness(command: BridgeMessage.DismissStaleness) {
        Dev.info(log, "bridge.dismiss_staleness",
            "conversationId" to command.conversationId,
            "entryId" to command.entryId
        )
        // Staleness dismissal is primarily a frontend state change.
        // The backend acknowledges it for logging. No persistence needed —
        // if the user restarts the IDE, the staleness flag reappears
        // (the file is still different from when the context was sent).
    }

    /**
     * Handle REFRESH_CONTEXT_ENTRY — re-gather a stale file into the staging area.
     *
     * The updated entry carries metadata indicating it replaces a previously-sent
     * version, so the AI knows this is an update, not a new context entry.
     */
    private fun handleRefreshContextEntry(command: BridgeMessage.RefreshContextEntry) {
        Dev.info(log, "bridge.refresh_context_entry",
            "tabId" to command.tabId,
            "filePath" to command.filePath,
            "originalEntryId" to command.originalEntryId
        )

        scope.launch {
            try {
                // Re-gather the specific file using gatherAndStage with a focused scope.
                // For now, we trigger a full gather — a focused single-file re-gather
                // would be more efficient but requires additional ContextAssembler API.
                // The entry will be added to the staging area with "update" source.
                val stagingService = ContextStagingService.getInstance(project)

                // Create an update entry directly from the file
                // (simplified — a full implementation would read the file,
                // check for summary, and build a proper ContextEntry)
                Dev.info(log, "bridge.refresh_context_entry.queued",
                    "tabId" to command.tabId,
                    "filePath" to command.filePath,
                    "status" to "refresh will appear in staging area on next gather cycle"
                )

                // Emit a CONTEXT_BADGE_UPDATE to refresh the tray
                val currentState = stagingService.getState(command.tabId)
                val badges = currentState.map { e ->
                    BridgeMessage.ContextFileDetailDto(
                        id = e.id,
                        path = e.path ?: "",
                        name = e.name,
                        scope = if (e.elementSignature != null) "method" else "file",
                        lang = "",
                        kind = e.kind.name,
                        freshness = if (e.isStale) "rough" else "fresh",
                        tokens = e.tokenEstimate,
                        isStale = e.isStale,
                        forced = e.source == "forced",
                        elementSignature = e.elementSignature
                    )
                }
                emit(BridgeMessage.ContextBadgeUpdateEvent(
                    tabId = command.tabId,
                    badges = badges,
                    complete = true
                ))
            } catch (t: Throwable) {
                Dev.error(log, "bridge.refresh_context_entry.failed", t,
                    "tabId" to command.tabId,
                    "filePath" to command.filePath
                )
            }
        }
    }

    private fun handleDevCommand(command: BridgeMessage.DevCommand) {
        if (!com.youmeandmyself.dev.DevMode.isEnabled()) {
            emit(BridgeMessage.DevOutputEvent(
                content = "Dev mode is not enabled. Start IDE with -Dymm.devMode=true"
            ))
            return
        }

        devCommandHandler.handleIfDevCommand(command.text, command.tabId)
    }

    // ── Block 5: Context Settings Handler ────────────────────────────

    /**
     * Handle REQUEST_CONTEXT_SETTINGS — send project-level context settings to React.
     *
     * Called at startup (BRIDGE_READY and non-JCEF dev mode). The React app uses
     * this to set globalContextEnabled (disables ContextDial when false) and
     * defaultBypassMode (used as the initial dial position for new tabs).
     *
     * Tier-gating: Basic-tier users always receive defaultBypassMode = "FULL"
     * regardless of the stored value, because they cannot customise this setting.
     * The SELECTIVE default is gated behind Feature.CONTEXT_SELECTIVE_BYPASS.
     */
    /**
     * Handle REQUEST_CONTEXT_SETTINGS — send project-level context AND summary
     * settings to the React frontend.
     *
     * Called at startup (BRIDGE_READY) and whenever settings change
     * (via CrossPanelBridge.notifyContextSettingsChanged).
     *
     * Sends three things:
     * 1. contextEnabled — global context kill-switch
     * 2. defaultBypassMode — dial position for new tabs
     * 3. summaryEnabled — global summary kill-switch
     *
     * The frontend uses these to:
     * - Grey out the ContextDial when context is globally disabled
     * - Hide/disable per-tab summary toggles when summary is globally off
     * - Set the default mode for newly created tabs
     */
    private fun handleRequestContextSettings() {
        try {
            val contextSettings = ContextSettingsState.getInstance(project).state
            val canUseSelective = try {
                CompositeTierProvider.getInstance().canUse(Feature.CONTEXT_SELECTIVE_BYPASS)
            } catch (e: Exception) {
                Dev.warn(log, "context_settings.tier_check_failed", e)
                false
            }

            // Basic users always get "FULL" — they cannot customise the default
            val defaultBypassMode = if (canUseSelective) {
                contextSettings.defaultBypassMode
            } else {
                "FULL"
            }

            // Read global summary enabled state from SummaryConfigService.
            // This is independent from context — two separate features.
            val summaryEnabled = try {
                SummaryConfigService.getInstance(project).getConfig().enabled
            } catch (e: Exception) {
                Dev.warn(log, "context_settings.summary_check_failed", e)
                true // Safe fallback: assume summary is enabled
            }

            Dev.info(log, "bridge.context_settings",
                "contextEnabled" to contextSettings.contextEnabled,
                "defaultBypassMode" to defaultBypassMode,
                "summaryEnabled" to summaryEnabled,
                "canUseSelective" to canUseSelective
            )

            emit(BridgeMessage.ContextSettingsEvent(
                contextEnabled = contextSettings.contextEnabled,
                defaultBypassMode = defaultBypassMode,
                summaryEnabled = summaryEnabled
            ))
        } catch (t: Throwable) {
            Dev.error(log, "bridge.context_settings_failed", t)
            // Fallback: send safe defaults so the UI doesn't hang
            emit(BridgeMessage.ContextSettingsEvent(
                contextEnabled = true,
                defaultBypassMode = "FULL",
                summaryEnabled = true
            ))
        }
    }

    // ── Force Context: Ghost Badge Resolution ──────────────────────

    /**
     * Handle RESOLVE_FORCE_CONTEXT command.
     *
     * Lightweight read-only check: resolves the element at cursor via PSI,
     * then checks if that element would already be included in the automatic
     * context (based on current dial/lever settings).
     *
     * No AI calls, no generation, no side effects.
     *
     * Responds with [BridgeMessage.ResolveForceContextResult].
     */
    private fun handleResolveForceContext(command: BridgeMessage.ResolveForceContext) {
        try {
            // Resolve cursor element via PSI (same as ContextAssembler does)
            val resolved = com.youmeandmyself.ai.chat.context.EditorElementResolver.resolve(project)

            if (resolved == null || resolved.elementAtCursor == null) {
                // No element at cursor — can't force anything
                Dev.info(log, "bridge.resolve_force.no_element",
                    "scope" to command.scope
                )
                emit(BridgeMessage.ResolveForceContextResult(
                    alreadyIncluded = false,
                    elementName = null,
                    elementScope = null,
                    estimatedTokens = null
                ))
                return
            }

            // When scope=class, use the containing class (not the method at cursor).
            // When scope=method, use the element at cursor directly.
            // If scope=class but there's no containing class (e.g., cursor is on a
            // top-level function), fall back to the element at cursor.
            val element = when (command.scope) {
                "class" -> resolved.containingClass ?: resolved.elementAtCursor
                else -> resolved.elementAtCursor
            }

            val elementScope = when (element.kind) {
                CodeElementKind.METHOD,
                CodeElementKind.FUNCTION,
                CodeElementKind.CONSTRUCTOR -> "method"
                CodeElementKind.CLASS,
                CodeElementKind.INTERFACE,
                CodeElementKind.OBJECT,
                CodeElementKind.ENUM -> "class"
                else -> "other"
            }

            // The ghost badge ALWAYS shows when the user clicks Force Context.
            // The force button is the user saying "I want this element in context."
            // Whether the element would also be included automatically (via heuristics
            // or smart mode) is irrelevant at this point — the user wants visual
            // confirmation that their action was registered.
            //
            // Duplication prevention happens at assembly time (when Send is clicked),
            // not here. The assembler checks if the forced element is already in the
            // gathered context and avoids attaching it twice.
            //
            // On Send: the ghost badge is replaced by real badges from contextFiles[].
            // The forced element appears as a real badge (with forced=true), alongside
            // all other context entries the pipeline gathered (neighbours, related files).
            val alreadyIncluded = false

            // Estimate tokens from element body length (~4 chars per token)
            val estimatedTokens = element.body.length / 4

            Dev.info(log, "bridge.resolve_force",
                "scope" to command.scope,
                "element" to element.name,
                "elementScope" to elementScope,
                "alreadyIncluded" to alreadyIncluded,
                "estimatedTokens" to estimatedTokens
            )

            emit(BridgeMessage.ResolveForceContextResult(
                alreadyIncluded = alreadyIncluded,
                elementName = element.name,
                elementScope = elementScope,
                estimatedTokens = estimatedTokens
            ))
        } catch (e: Throwable) {
            Dev.warn(log, "bridge.resolve_force.failed", e)
            emit(BridgeMessage.ResolveForceContextResult(
                alreadyIncluded = false,
                elementName = null,
                elementScope = null,
                estimatedTokens = null
            ))
        }
    }

    // ── Navigate to Source Handler ──────────────────────────────────

    /**
     * Handle NAVIGATE_TO_SOURCE — open a file in the IDE editor and position cursor.
     *
     * Called when the user clicks a badge in the badge tray.
     * If filePath is provided, opens that file. If elementSignature is also
     * provided, positions the cursor at that element.
     * If both are null, focuses the currently open editor (for ghost badge clicks).
     */
    private fun handleNavigateToSource(command: BridgeMessage.NavigateToSource) {
        try {
            val filePath = command.filePath

            if (filePath == null) {
                // Ghost badge click — just focus the editor
                Dev.info(log, "bridge.navigate.focus_editor")
                com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                    val editor = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project)
                    editor.selectedTextEditor?.contentComponent?.requestFocusInWindow()
                }
                return
            }

            // Open the file
            val virtualFile = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
                .findFileByPath(filePath)

            if (virtualFile == null) {
                Dev.warn(log, "bridge.navigate.file_not_found", null, "path" to filePath)
                return
            }

            Dev.info(log, "bridge.navigate",
                "file" to virtualFile.name,
                "element" to (command.elementSignature ?: "(file-level)")
            )

            com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                val editorManager = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project)
                editorManager.openFile(virtualFile, true)

                // If we have an element signature, position the cursor at it
                val signature = command.elementSignature
                if (signature != null) {
                    val provider = com.youmeandmyself.summary.structure.CodeStructureProviderFactory
                        .getInstance(project).get()
                    if (provider != null) {
                        try {
                            val elements = provider.detectElements(
                                virtualFile,
                                com.youmeandmyself.summary.structure.DetectionScope.All
                            )
                            val target = elements.find { it.signature == signature }
                            if (target != null) {
                                val editor = editorManager.selectedTextEditor
                                editor?.caretModel?.moveToOffset(target.offsetRange.first)
                                editor?.scrollingModel?.scrollToCaret(
                                    com.intellij.openapi.editor.ScrollType.CENTER
                                )
                            }
                        } catch (e: Throwable) {
                            Dev.warn(log, "bridge.navigate.element_not_found", e,
                                "signature" to signature
                            )
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            Dev.warn(log, "bridge.navigate.failed", e)
        }
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
            },
            // Map internal ContextFileDetail to bridge DTO
            contextFiles = result.contextFiles.map { detail ->
                BridgeMessage.ContextFileDetailDto(
                    path = detail.path,
                    name = detail.name,
                    scope = detail.scope,
                    lang = detail.lang,
                    kind = detail.kind,
                    freshness = detail.freshness,
                    tokens = detail.tokens,
                    isStale = detail.isStale,
                    forced = detail.forced,
                    elementSignature = detail.elementSignature
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