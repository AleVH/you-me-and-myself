package com.youmeandmyself.ai.chat

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.youmeandmyself.ai.chat.context.LocalSummaryStoreProvider
import com.youmeandmyself.ai.chat.orchestrator.ChatOrchestrator
import com.youmeandmyself.ai.chat.service.BrowserChatService
import com.youmeandmyself.ai.chat.service.ChatUIService
import com.youmeandmyself.ai.chat.service.SwingChatService
import com.youmeandmyself.ai.chat.service.SystemMessageType
import com.youmeandmyself.ai.library.LibraryPanelHolder
import com.youmeandmyself.ai.providers.parsing.ui.CorrectionFlowHelper
import com.youmeandmyself.ai.providers.parsing.ui.FormatHintStorageImpl
import com.youmeandmyself.ai.settings.AiProfilesState
import com.youmeandmyself.dev.Dev
import com.youmeandmyself.dev.DevCommandHandler
import com.youmeandmyself.storage.LocalStorageFacade
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.*

/**
 * Main chat UI panel for AI interactions.
 *
 * ## Post-Refactoring Role
 *
 * This class is now a **thin UI shell**. All business logic has been extracted:
 *
 * - Context building → [ContextAssembler]
 * - Provider calls + storage + correction flow + conversation bookkeeping → [ChatOrchestrator]
 * - Response parsing + HTTP → [GenericLlmProvider]
 *
 * ChatPanel's remaining responsibilities:
 * - Swing layout (input field, send button, provider combo boxes)
 * - Routing user input to the orchestrator
 * - Rendering ChatResult via ChatUIService (browser or Swing)
 * - Dev commands (only with -Dymm.devMode=true)
 *
 * ## Future: React Replacement
 *
 * When React replaces this panel, the migration is straightforward:
 * 1. React calls ChatOrchestrator.send() through a JCEF bridge
 * 2. React renders ChatResult in its own components
 * 3. This class is deleted — nothing below the orchestrator changes
 *
 * The orchestrator was specifically designed for this: zero UI imports,
 * returns data only, any frontend tech can consume it.
 *
 * @param project The IntelliJ project context
 * @param onReady Callback invoked when the chat service finishes initializing.
 *                True = ready, False = fell back to Swing.
 */
class ChatPanel(private val project: Project, private val onReady: ((Boolean) -> Unit)? = null) {
    private val log = Dev.logger(ChatPanel::class.java)

    // ── Core services ────────────────────────────────────────────────────

    /** Chat rendering service (JCEF browser or Swing fallback). */
    private val chatService: ChatUIService by lazy {
        try {
            BrowserChatService(project).apply {
                setOnReady { isReady ->
                    cancelTimeout()
                    onReady?.invoke(isReady)
                }
            }.also { it.getComponent() }
        } catch (e: Throwable) {
            Dev.error(log, "chatpanel.browser_failed", e, "falling_back" to true)
            cancelTimeout()
            onReady?.invoke(false)
            SwingChatService(project).also {
                it.addSystemMessage("Enhanced chat unavailable - using basic mode")
            }
        }
    }

    /** Correction flow helper — owned per-tab to avoid cross-tab conflicts. */
    private val correctionHelper: CorrectionFlowHelper by lazy {
        CorrectionFlowHelper(
            project = project,
            storageFacade = LocalStorageFacade.getInstance(project),
            hintStorage = FormatHintStorageImpl.getInstance()
        )
    }

    /**
     * The orchestrator — all business logic lives here.
     *
     * Takes user input, returns ChatResult. Handles: context assembly,
     * provider calls, storage persistence, correction flow, conversation
     * bookkeeping. ChatPanel just renders what it returns.
     */
    private val orchestrator: ChatOrchestrator by lazy {
        ChatOrchestrator.create(
            project = project,
            correctionHelper = correctionHelper,
            summaryStore = LocalSummaryStoreProvider(project)
        )
    }

    /** Dev commands — only active with -Dymm.devMode=true system property. */
    private val devCommandHandler: DevCommandHandler by lazy {
        DevCommandHandler(project, chatService, correctionHelper, scope)
    }

    // ── UI components ────────────────────────────────────────────────────

    /** Root panel that gets added to the IntelliJ tool window. */
    internal val root = JPanel(BorderLayout())

    private val input = JBTextField().apply {
        emptyText.text = "Type a prompt…"
    }
    private val send = JButton("Send")
    private val providerSelector = JComboBox<String>()
    private val summarySelector = JComboBox<String>()
    private val scope = CoroutineScope(Dispatchers.IO)

    private var stateDisposable: Disposable? = null
    private var readinessTimeout: Timer? = null

    // ── Combo box state ──────────────────────────────────────────────────
    //
    // These track items in each combo box so ActionListeners can map
    // selectedIndex → profile ID.
    //
    // The isRefreshing guard prevents listener callbacks during rebuild:
    //   removeAllItems() → triggers selection → null
    //   addItem(x)       → triggers selection → x
    //   setSelectedIndex  → triggers selection → final
    // Without the guard, each fires the listener → writes to state → refresh loop.

    private var isRefreshing = false
    private var chatProfileItems: List<Pair<String, String>> = emptyList()
    private var summaryProfileItems: List<Pair<String, String>> = emptyList()

    // ── Initialization ───────────────────────────────────────────────────

    init {
        // Safety timeout: if JCEF never reports ready, unblock the UI
        readinessTimeout = Timer(5000) {
            Dev.warn(log, "chatpanel.timeout", null, "readiness_timeout" to true)
            onReady?.invoke(false)
        }.apply {
            isRepeats = false
            start()
        }

        root.layout = BorderLayout()

        // Force lazy init of the chat service
        val service = chatService

        root.add(service.getComponent(), BorderLayout.CENTER)
        root.add(createInputPanel(), BorderLayout.SOUTH)

        // Input actions — both Enter key and Send button trigger doSend()
        send.addActionListener { doSend() }
        input.addActionListener { doSend() }

        // Combo listeners registered ONCE — never re-added during refresh
        providerSelector.addActionListener {
            if (isRefreshing) return@addActionListener
            val ps = AiProfilesState.getInstance(project)
            val idx = providerSelector.selectedIndex
            ps.selectedChatProfileId =
                if (idx in chatProfileItems.indices) chatProfileItems[idx].first else null
        }

        summarySelector.addActionListener {
            if (isRefreshing) return@addActionListener
            val ps = AiProfilesState.getInstance(project)
            val idx = summarySelector.selectedIndex
            ps.selectedSummaryProfileId =
                if (idx in summaryProfileItems.indices) summaryProfileItems[idx].first else null
        }

        // Initial population of combo boxes
        refreshProviderSelector()
        refreshSummarySelector()

        // Watch for profile changes (tool window state changes, settings edits)
        setupProfileObservation()
    }

    private fun cancelTimeout() {
        readinessTimeout?.stop()
        readinessTimeout = null
    }

    // ── Message Send (the slim version) ──────────────────────────────────
    //
    // Before refactoring: ~300 lines doing 6 jobs.
    // After refactoring: ~30 lines calling the orchestrator.

    /**
     * Handle user input submission.
     *
     * Supports special commands:
     * - Dev commands (with -Dymm.devMode=true)
     * - /correct: Opens correction dialog for last auto-detected response
     * - /raw: Shows raw JSON of last response (debugging)
     *
     * For regular messages: shows thinking indicator, calls orchestrator,
     * renders the result. That's it.
     */
    private fun doSend() {
        val userInput = input.text?.trim().orEmpty()
        if (userInput.isBlank()) return
        input.text = ""

        // Dev mode test commands (only active with -Dymm.devMode=true)
        if (devCommandHandler.handleIfDevCommand(userInput)) return

        // Handle special commands
        when {
            userInput.equals("/correct", ignoreCase = true) -> {
                handleCorrectionCommand()
                return
            }
            userInput.startsWith("/raw", ignoreCase = true) -> {
                handleRawCommand()
                return
            }
        }

        // ── Regular message flow ─────────────────────────────────────
        // 1. Show the user's message immediately
        chatService.sendUserMessage(userInput, true)

        // 2. Show thinking indicator
        chatService.showThinking()

        // 3. Call the orchestrator (does everything: context, provider, storage, correction)
        scope.launch {
            val result = orchestrator.send(userInput, scope)

            // 4. Show context info if context was gathered
            if (result.contextSummary != null) {
                chatService.addSystemMessage(
                    "Context ready in ${result.contextTimeMs ?: "?"} ms",
                    SystemMessageType.INFO
                )
            }

            // 5. Render the response (or error)
            chatService.addAssistantMessage(
                result.displayText,
                result.providerId,
                result.isError
            )

            // 6. Update metrics display if token data is available
            if (result.tokenUsage != null) {
                chatService.updateMetrics(
                    model = result.modelId,
                    promptTokens = result.tokenUsage.promptTokens,
                    completionTokens = result.tokenUsage.completionTokens,
                    totalTokens = result.tokenUsage.effectiveTotal,
                    estimatedCost = null
                )
            }

            // 7. Show correction hint if the response was auto-detected
            if (result.correctionAvailable) {
                chatService.addSystemMessage(
                    "ℹ️ Response auto-detected. Not what you expected? Type /correct to fix.",
                    SystemMessageType.INFO
                )
            }

            // 8. Refresh Library panel to show the new exchange
            LibraryPanelHolder.get(project)?.refresh()
        }
    }

    // ── Correction Commands ──────────────────────────────────────────────

    /**
     * Handle /correct — opens correction dialog for the last auto-detected response.
     */
    private fun handleCorrectionCommand() {
        scope.launch {
            val result = orchestrator.handleCorrection()
            if (result == null) {
                chatService.addSystemMessage(
                    "No response available for correction.",
                    SystemMessageType.INFO
                )
                return@launch
            }

            chatService.addSystemMessage(result.systemMessage, SystemMessageType.INFO)
            if (result.displayText != null) {
                chatService.addAssistantMessage(result.displayText, null, false)
            }
        }
    }

    /**
     * Handle /raw — shows raw JSON of the last response for debugging.
     */
    private fun handleRawCommand() {
        scope.launch {
            val shown = orchestrator.handleRawCommand()
            if (!shown) {
                chatService.addSystemMessage(
                    "No recent response available. Send a message first.",
                    SystemMessageType.INFO
                )
            }
        }
    }

    // ── Profile Observation ──────────────────────────────────────────────

    /**
     * Watch for IntelliJ tool window state changes that might indicate
     * the user modified AI profiles in Settings.
     */
    private fun setupProfileObservation() {
        val connection = project.messageBus.connect()
        stateDisposable = connection

        connection.subscribe(
            com.intellij.openapi.wm.ex.ToolWindowManagerListener.TOPIC,
            object : com.intellij.openapi.wm.ex.ToolWindowManagerListener {
                @Deprecated("Deprecated in Java")
                override fun stateChanged() {
                    refreshProviderSelector()
                    refreshSummarySelector()
                }
            }
        )
    }

    /** External refresh trigger (e.g., after Settings dialog closes). */
    fun refresh() {
        refreshProviderSelector()
        refreshSummarySelector()
    }

    /** Clean up resources when the panel is disposed. */
    fun dispose() {
        stateDisposable?.dispose()
    }

    // ── Input Panel Layout ───────────────────────────────────────────────

    /**
     * Build the bottom input area: text field + send button + provider selectors.
     */
    private fun createInputPanel(): JPanel {
        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(8)

            val inputRow = JPanel(BorderLayout()).apply {
                add(input, BorderLayout.CENTER)
                add(send, BorderLayout.EAST)
            }

            val controlsRow = JPanel().apply {
                layout = GridBagLayout()
                val gbc = GridBagConstraints()

                val dropdownsPanel = JPanel().apply {
                    layout = BoxLayout(this, BoxLayout.Y_AXIS)

                    val chatRow = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply<JPanel> {
                        add(JBLabel("Chat:"))
                        add(providerSelector)
                    }

                    val summaryRow = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply<JPanel> {
                        add(JBLabel("Summary:"))
                        add(summarySelector)
                    }

                    add(chatRow)
                    add(summaryRow)
                }

                gbc.gridx = 0
                gbc.gridy = 0
                gbc.fill = GridBagConstraints.BOTH
                gbc.weightx = 1.0
                gbc.weighty = 1.0
                add(dropdownsPanel, gbc)
            }

            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(inputRow)
            add(controlsRow)
        }
    }

    // ── Combo Box Refresh (guarded to prevent listener cascade) ──────────

    /**
     * Rebuild the chat provider combo box from current profiles.
     *
     * The isRefreshing guard suppresses ActionListener callbacks during
     * item manipulation, preventing the feedback loop:
     *   removeAllItems → triggers listener → writes state → triggers refresh
     */
    private fun refreshProviderSelector() {
        isRefreshing = true
        try {
            val ps = AiProfilesState.getInstance(project)

            val chatProfiles = ps.profiles
                .filter { it.roles.chat }
                .filter {
                    it.apiKey.isNotBlank() &&
                            it.baseUrl.isNotBlank() &&
                            it.model?.isNotBlank() == true
                }
                .sortedByDescending { it.id }

            Dev.info(log, "chat.filtered.profiles",
                "beforeFilter" to ps.profiles.count { it.roles.chat },
                "afterFilter" to chatProfiles.size
            )

            providerSelector.removeAllItems()

            if (chatProfiles.isEmpty()) {
                providerSelector.addItem("(No chat profiles)")
                providerSelector.isEnabled = false
                send.isEnabled = false
                input.isEnabled = false
                input.emptyText.text = "No AI profiles available - configure in Settings"
                chatProfileItems = emptyList()
                return
            }

            chatProfileItems = chatProfiles.map { profile ->
                profile.id to "${profile.label.ifBlank { "Generic LLM" }} [${profile.protocol?.name ?: "unknown"}]"
            }
            chatProfileItems.forEach { (_, label) -> providerSelector.addItem(label) }

            // Restore or default selection
            val currentId = ps.selectedChatProfileId
            val selectedIdx = when {
                currentId != null ->
                    chatProfileItems.indexOfFirst { it.first == currentId }.takeIf { it >= 0 } ?: 0
                chatProfileItems.isNotEmpty() -> 0
                else -> -1
            }

            if (selectedIdx >= 0) {
                providerSelector.selectedIndex = selectedIdx
                if (ps.selectedChatProfileId != chatProfileItems[selectedIdx].first) {
                    ps.selectedChatProfileId = chatProfileItems[selectedIdx].first
                }
            }

            send.isEnabled = true
            input.isEnabled = true
            providerSelector.isEnabled = true
            input.emptyText.text = "Type a prompt…"
        } finally {
            isRefreshing = false
        }
    }

    /**
     * Rebuild the summary provider combo box. Same guarded pattern.
     */
    private fun refreshSummarySelector() {
        isRefreshing = true
        try {
            val ps = AiProfilesState.getInstance(project)

            val summaryProfiles = ps.profiles
                .filter { it.roles.summary }
                .filter {
                    it.apiKey.isNotBlank() &&
                            it.baseUrl.isNotBlank() &&
                            it.model?.isNotBlank() == true
                }
                .sortedByDescending { it.id }

            summarySelector.removeAllItems()

            if (summaryProfiles.isEmpty()) {
                summarySelector.addItem("(No summary profiles)")
                summarySelector.isEnabled = false
                summaryProfileItems = emptyList()
                return
            }

            summaryProfileItems = summaryProfiles.map { profile ->
                profile.id to "${profile.label.ifBlank { "Generic LLM" }} [${profile.protocol?.name ?: "unknown"}]"
            }
            summaryProfileItems.forEach { (_, label) -> summarySelector.addItem(label) }

            val currentId = ps.selectedSummaryProfileId
            val selectedIdx = when {
                currentId != null ->
                    summaryProfileItems.indexOfFirst { it.first == currentId }.takeIf { it >= 0 } ?: 0
                summaryProfileItems.isNotEmpty() -> 0
                else -> -1
            }

            if (selectedIdx >= 0) {
                summarySelector.selectedIndex = selectedIdx
                if (ps.selectedSummaryProfileId != summaryProfileItems[selectedIdx].first) {
                    ps.selectedSummaryProfileId = summaryProfileItems[selectedIdx].first
                }
            }

            summarySelector.isEnabled = true
        } finally {
            isRefreshing = false
        }
    }
}