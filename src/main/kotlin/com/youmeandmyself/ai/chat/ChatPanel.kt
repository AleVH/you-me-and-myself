package com.youmeandmyself.ai.chat

import com.intellij.openapi.Disposable
import com.intellij.ui.components.JBTextField
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Font
import java.awt.FlowLayout
import java.nio.charset.Charset
import javax.swing.*
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants
import javax.swing.text.StyledDocument
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.application.ReadAction
import com.youmeandmyself.context.orchestrator.ContextOrchestrator
import com.youmeandmyself.context.orchestrator.ContextRequest
import com.youmeandmyself.context.orchestrator.DetectorRegistry
import com.youmeandmyself.context.orchestrator.ContextBundle
import com.youmeandmyself.context.orchestrator.detectors.FrameworkDetector
import com.youmeandmyself.context.orchestrator.detectors.LanguageDetector
import com.youmeandmyself.context.orchestrator.detectors.ProjectStructureDetector
import com.youmeandmyself.context.orchestrator.detectors.RelevantFilesDetector
import com.youmeandmyself.context.orchestrator.ContextKind
import com.youmeandmyself.ai.settings.AiProfilesState
import com.youmeandmyself.ai.providers.ProviderRegistry
import com.youmeandmyself.ai.providers.parsing.ui.CorrectionFlowHelper
import com.youmeandmyself.ai.providers.parsing.ui.FormatHintStorageImpl
import com.youmeandmyself.storage.LocalStorageFacade
import com.youmeandmyself.dev.Dev
import com.youmeandmyself.ai.chat.service.BrowserChatService
import com.youmeandmyself.ai.chat.service.ChatUIService
import com.youmeandmyself.ai.chat.service.SwingChatService
import com.youmeandmyself.ai.chat.service.SystemMessageType
import com.youmeandmyself.dev.DevCommandHandler
import java.awt.GridBagConstraints
import java.awt.GridBagLayout

/**
 * Main chat UI panel for AI interactions.
 *
 * Responsibilities:
 * - Renders chat transcript (via BrowserChatService or SwingChatService fallback)
 * - Handles user input and sends to AI provider
 * - Manages context attachment (IDE-derived context for code questions)
 * - Coordinates response parsing correction flow (when heuristic parsing is uncertain)
 *
 * ## Response Parsing Correction Flow
 *
 * When AI providers return responses in unknown formats, the parser uses heuristics
 * to extract content. This can result in three scenarios:
 *
 * 1. **Known Format**: Parser recognizes the schema (OpenAI, Gemini, etc.) → display immediately
 * 2. **Heuristic + Confident**: Parser guessed but is fairly sure → display with "/correct" option
 * 3. **Low Confidence**: Parser uncertain → show dialog BEFORE displaying, let user pick
 *
 * The CorrectionFlowHelper manages this logic. Users can also type "/correct" to fix
 * the last auto-detected response, or "/raw" to view the raw JSON for debugging.
 *
 * NOTE: Storage/persistence of exchanges happens in GenericLlmProvider.
 * ChatPanel just displays the results.
 */
class ChatPanel(private val project: Project, private val onReady: ((Boolean) -> Unit)? = null) {
    private val log = Dev.logger(ChatPanel::class.java)

    private val chatService: ChatUIService by lazy {
        Dev.info(log, "chatpanel.service", "lazy_init_start" to true)
        try {
            BrowserChatService(project).apply {
                Dev.info(log, "chatpanel.browser", "browser_service_created" to true)
                setOnReady { isReady ->
                    Dev.info(log, "chatpanel.browser_ready", "callback_called" to true, "isReady" to isReady)
                    cancelTimeout()
                    onReady?.invoke(isReady)
                }
            }.also {
                it.getComponent()
                Dev.info(log, "chatpanel.browser", "component_retrieved" to true)
            }
        } catch (e: Throwable) {
            Dev.error(log, "chatpanel.browser_failed", e, "falling_back" to true)
            cancelTimeout()
            onReady?.invoke(false)
            SwingChatService(project).also {
                it.addSystemMessage("Enhanced chat unavailable - using basic mode")
                Dev.info(log, "chatpanel.swing", "fallback_created" to true)
            }
        }
    }

    /**
     * Handles correction flow when response parsing uses heuristics.
     * Decides when to show correction dialogs and manages format hint learning.
     */
    private val correctionHelper: CorrectionFlowHelper by lazy {
        CorrectionFlowHelper(
            project = project,
            storageFacade = LocalStorageFacade.getInstance(project),
            hintStorage = FormatHintStorageImpl.getInstance()
        )
    }

    /**
     * Handles dev-only test commands. Only active when -Dymm.devMode=true.
     */
    private val devCommandHandler: DevCommandHandler by lazy {
        DevCommandHandler(project, chatService, correctionHelper, scope)
    }

    internal val root = JPanel(BorderLayout())

    private val transcript = JTextPane().apply {
        isEditable = false
        margin = JBUI.insets(8)
    }
    private val doc: StyledDocument = transcript.styledDocument

    private val input = JBTextField().apply {
        emptyText.text = "Type a prompt…"
    }

    private val send = JButton("Send")

    private val providerSelector = JComboBox<String>()
    private val summarySelector = JComboBox<String>()

    private val attachContext = JCheckBox("Attach context").apply {
        setToolTipText("Include IDE-derived context (language, frameworks, project structure, related files)")
    }

    private val scope = CoroutineScope(Dispatchers.IO)

    private var stateDisposable: Disposable? = null

    private var readinessTimeout: Timer? = null

    init {
        Dev.info(log, "chatpanel.init", "start" to true)

        readinessTimeout = Timer(5000) {
            Dev.warn(log, "chatpanel.timeout", null,"readiness_timeout" to true)
            onReady?.invoke(false)
        }.apply {
            isRepeats = false
            start()
        }

        root.layout = BorderLayout()

        val service = chatService
        Dev.info(log, "chatpanel.init", "service_created" to true)

        root.add(service.getComponent(), BorderLayout.CENTER)
        root.add(createInputPanel(), BorderLayout.SOUTH)

        send.addActionListener { doSend() }
        input.addActionListener { doSend() }

        refreshProviderSelector()
        refreshSummarySelector()

        Dev.info(log, "chatpanel.init", "complete" to true)

        setupProfileObservation()
    }

    private fun cancelTimeout() {
        readinessTimeout?.stop()
        readinessTimeout = null
    }

    private fun setupProfileObservation() {
        val connection = project.messageBus.connect()
        stateDisposable = connection

        project.messageBus.connect().subscribe(
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

    fun refresh() {
        refreshProviderSelector()
        refreshSummarySelector()
    }

    fun dispose() {
        stateDisposable?.dispose()
    }

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
                gbc.weightx = 0.75
                gbc.weighty = 1.0
                add(dropdownsPanel, gbc)

                gbc.gridx = 1
                gbc.fill = GridBagConstraints.NONE
                gbc.weightx = 0.25
                gbc.anchor = GridBagConstraints.CENTER

                val checkboxPanel = JPanel().apply {
                    layout = BoxLayout(this, BoxLayout.Y_AXIS)

                    val checkboxWrapper = JPanel(FlowLayout(FlowLayout.CENTER, 0, 0))
                    checkboxWrapper.add(attachContext.apply { text = "" })
                    add(checkboxWrapper)

                    val labelWrapper = JPanel(FlowLayout(FlowLayout.CENTER, 0, 0))
                    labelWrapper.add(JBLabel("Attach context").apply {
                        font = font.deriveFont(10f)
                        foreground = Color.GRAY
                    })
                    add(labelWrapper)
                }

                add(checkboxPanel, gbc)
            }

            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(inputRow)
            add(controlsRow)
        }
    }

    /**
     * Handles user input submission.
     *
     * Supports special commands:
     * - /correct: Opens correction dialog for the last auto-detected response
     * - /raw: Shows raw JSON of last response (debugging)
     *
     * For regular messages, builds context if needed and sends to AI provider.
     */
    private fun doSend() {
        val userInput = input.text?.trim().orEmpty()
        if (userInput.isBlank()) return
        input.text = ""

        // Dev mode test commands (only active with -Dymm.devMode=true)
        if (devCommandHandler.handleIfDevCommand(userInput)) {
            return
        }

        // Handle special commands for response correction and debugging
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

        chatService.sendUserMessage(userInput, attachContext.isSelected)

        val editorFile = FileEditorManager.getInstance(project).selectedFiles.firstOrNull()

        Dev.info(log, "chat.send", "text" to Dev.preview(userInput), "editor" to Dev.fileName(editorFile))

        val isEditorCodeFile = editorFile?.extension?.lowercase() in setOf(
            "kt","kts","java","js","jsx","ts","tsx","py","php","rb","go","rs","c","cpp","h","cs","xml","json","yml","yaml"
        )

        val t = userInput.lowercase()
        val isGenericExplain = listOf("what does this do", "explain", "analyze", "describe")
            .any { it in t }

        val needContext = (attachContext.isSelected
                || isContextLikelyUseful(userInput)
                || refersToCurrentFile(userInput))
                || (isEditorCodeFile && isGenericExplain)

        Dev.info(log, "chat.ctx", "need" to needContext, "isEditorCodeFile" to isEditorCodeFile, "genericExplain" to isGenericExplain)

        scope.launch {
            val provider = ProviderRegistry.selectedChatProvider(project)
            if (provider == null) {
                chatService.addAssistantMessage(
                    "[No provider selected/configured. Pick one in the dropdown or set keys in Settings.]",
                    isError = true
                )
                return@launch
            }

            try {
                val effectivePrompt: String

                if (needContext) {
                    Dev.info(log, "chat.context_building",
                        "userInput" to Dev.preview(userInput),
                        "editorFile" to Dev.fileName(editorFile)
                    )
                    if (DumbService.isDumb(project)) {
                        chatService.addAssistantMessage(
                            "This question requires project context, but the IDE is currently indexing files. " +
                                    "Please wait until indexing finishes and then ask your question again.",
                            isError = true
                        )
                        return@launch
                    }

                    val registry = DetectorRegistry(
                        listOf(
                            LanguageDetector(),
                            FrameworkDetector(),
                            ProjectStructureDetector(),
                            RelevantFilesDetector()
                        )
                    )
                    val orchestrator = ContextOrchestrator(registry, Logger.getInstance(ChatPanel::class.java))
                    val request = ContextRequest(project = project, maxMillis = 1400L)

                    if (DumbService.isDumb(project)) {
                        chatService.addSystemMessage("Context skipped: project indexing in progress")
                        val result = provider.chat(userInput)
                        chatService.addAssistantMessage(result.displayText, provider.id, result.isError)
                        return@launch
                    }

                    val (bundle, metrics) = orchestrator.gather(request, scope)

                    chatService.addSystemMessage("Context ready in ${metrics.totalMillis} ms")

                    val contextNote = formatContextNote(bundle)

                    val rawCount = bundle.files.count { it.kind == ContextKind.RAW }
                    val summaryCount = bundle.files.count { it.kind == ContextKind.SUMMARY }
                    val manifest = "[Context: ${bundle.files.size} files ($rawCount raw, $summaryCount summaries), ~${bundle.totalChars} chars]"

                    val filesBlock = buildString {
                        appendLine("### Files")
                        bundle.files.forEach { cf ->
                            appendLine()
                            appendLine("```text")
                            appendLine("// path: ${cf.path} | lang: ${cf.languageId ?: "unknown"} | why: ${cf.reason}")
                            when (cf.kind) {
                                ContextKind.RAW -> {
                                    appendLine("// kind: RAW${if (cf.truncated) " (truncated)" else ""}")
                                }
                                ContextKind.SUMMARY -> {
                                    val status = when {
                                        cf.modelSynopsis.isNullOrBlank() -> " (synopsis pending)"
                                        cf.isStale -> " (synopsis may be outdated)"
                                        else -> ""
                                    }
                                    appendLine("// kind: SUMMARY$status")
                                    if (!cf.headerSample.isNullOrBlank()) {
                                        appendLine("[HEADER SAMPLE]")
                                        appendLine(cf.headerSample)
                                    }
                                    if (!cf.modelSynopsis.isNullOrBlank()) {
                                        appendLine()
                                        appendLine("[SYNOPSIS]")
                                        appendLine(cf.modelSynopsis)
                                    }
                                }
                            }
                            appendLine("```")
                        }
                    }

                    val includeCurrentFileContent = editorFile != null && refersToCurrentFile(userInput)

                    val fileBlock = if (includeCurrentFileContent) {
                        val text = readFileTextCapped(editorFile)
                        if (text != null && text.isNotBlank()) {
                            val lang = fenceLangFor(editorFile)
                            val path = editorFile.path.substringAfterLast('/')
                            """
                        [File: $path]
                        ```$lang
                        ${text.trim()}
                        ```
                        """.trimIndent()
                        } else ""
                    } else ""

                    effectivePrompt = """
                    $contextNote
                    $manifest
                    
                    $filesBlock
                    
                    $fileBlock
                    
                    $userInput
                    """.trimIndent()

                    Dev.info(log, "chat.context_built",
                        "bundleFiles" to bundle.files.size,
                        "bundleTotalChars" to bundle.totalChars,
                        "rawFiles" to bundle.files.count { it.kind == ContextKind.RAW },
                        "summaryFiles" to bundle.files.count { it.kind == ContextKind.SUMMARY }
                    )

                } else {
                    effectivePrompt = userInput
                }

                Dev.info(log, "chat.actual_prompt",
                    "effectivePromptLength" to effectivePrompt.length,
                    "effectivePromptPreview" to Dev.preview(effectivePrompt, 500)
                )

                // Show thinking indicator while waiting for AI response
                (chatService as? BrowserChatService)?.showThinking()

                // Call provider - returns ParsedResponse
                val result = provider.chat(effectivePrompt)

                // Clear any previous correction context when new message arrives
                correctionHelper.clearCorrectionContext()

                // Get modelId for format hint storage (hints are per-provider/model)
                val modelId = AiProfilesState.getInstance(project).profiles
                    .find { it.id == provider.id }?.model

                // Handle the three parsing confidence scenarios
                val finalDisplayText: String
                val finalIsError: Boolean

                when {
                    // Scenario 3: Low confidence - ask user to pick correct content before displaying
                    correctionHelper.shouldAskImmediately(result) && result.metadata.candidates.isNotEmpty() -> {
                        Dev.info(log, "chat.correction_flow",
                            "scenario" to 3,
                            "confidence" to result.metadata.confidence.name,
                            "candidates" to result.metadata.candidates.size
                        )

                        val corrected = correctionHelper.handleImmediateCorrection(
                            result = result,
                            providerId = provider.id,
                            modelId = modelId
                        )

                        if (corrected != null) {
                            finalDisplayText = corrected.displayText
                            finalIsError = false
                        } else {
                            // User cancelled - show best guess, store for potential post-correction
                            finalDisplayText = result.displayText
                            finalIsError = result.isError
                            correctionHelper.storeForPostCorrection(result, provider.id, modelId)
                        }
                    }

                    // Scenario 2: Heuristic used but confident - show response with correction option
                    correctionHelper.shouldOfferPostCorrection(result) -> {
                        Dev.info(log, "chat.correction_flow",
                            "scenario" to 2,
                            "confidence" to result.metadata.confidence.name,
                            "candidates" to result.metadata.candidates.size
                        )

                        finalDisplayText = result.displayText
                        finalIsError = result.isError
                        correctionHelper.storeForPostCorrection(result, provider.id, modelId)
                    }

                    // Scenario 1: Known format - just display
                    else -> {
                        Dev.info(log, "chat.correction_flow",
                            "scenario" to 1,
                            "strategy" to result.metadata.parseStrategy.name
                        )

                        finalDisplayText = result.displayText
                        finalIsError = result.isError
                    }
                }

                // Display the response
                chatService.addAssistantMessage(finalDisplayText, provider.id, finalIsError)

                // For Scenario 2: Show hint about correction option
                if (correctionHelper.hasCorrectableResponse()) {
                    chatService.addSystemMessage(
                        "ℹ️ Response auto-detected. Not what you expected? Type /correct to fix.",
                        SystemMessageType.INFO
                    )
                }

                Dev.info(log, "chat.result",
                    "isError" to finalIsError,
                    "errorType" to result.errorType?.name,
                    "parseStrategy" to result.metadata.parseStrategy.name,
                    "confidence" to result.metadata.confidence.name,
                    "exchangeId" to result.exchangeId,
                    "wasHeuristic" to result.metadata.wasHeuristicUsed
                )

            } catch (t: Throwable) {
                // Hide thinking indicator on error (it won't auto-hide since no assistant message is added before the error)
                (chatService as? BrowserChatService)?.hideThinking()
                chatService.addAssistantMessage("Error: ${t.message}", null, true)
            }
        }
    }

    /**
     * Handle /correct command - opens correction dialog for the last auto-detected response.
     * Allows user to pick the correct content from ranked candidates.
     */
    private fun handleCorrectionCommand() {
        if (!correctionHelper.hasCorrectableResponse()) {
            chatService.addSystemMessage(
                "No response available for correction. This command works after an auto-detected response.",
                SystemMessageType.INFO
            )
            return
        }

        scope.launch {
            val corrected = correctionHelper.handlePostCorrection()
            if (corrected != null) {
                chatService.addSystemMessage(
                    "✓ Response corrected:",
                    SystemMessageType.INFO
                )
                chatService.addAssistantMessage(corrected.displayText, null, false)
            } else {
                chatService.addSystemMessage(
                    "Correction cancelled.",
                    SystemMessageType.INFO
                )
            }
        }
    }

    /**
     * Handle /raw command - shows raw JSON of the last response for debugging.
     */
    private fun handleRawCommand() {
        val context = correctionHelper.lastCorrectionContext
        if (context == null) {
            chatService.addSystemMessage(
                "No recent response available. Send a message first.",
                SystemMessageType.INFO
            )
            return
        }

        scope.launch {
            correctionHelper.showRawResponse(
                exchangeId = context.exchangeId,
                providerId = context.providerId,
                modelId = context.modelId
            )
        }
    }

    /**
     * Heuristic to determine if the prompt likely benefits from project context.
     * Looks for code markers, error keywords, file paths, etc.
     */
    private fun isContextLikelyUseful(text: String): Boolean {
        val t = text.lowercase()

        if ("```" in t) return true

        val errorHints = listOf(
            "error", "exception", "traceback", "stack trace", "unresolved reference",
            "undefined", "cannot find symbol", "no such method", "classnotfound"
        )
        if (errorHints.any { it in t }) return true

        val extHints = listOf(
            ".kt", ".kts", ".java", ".js", ".ts", ".tsx", ".py", ".php", ".rb",
            ".go", ".rs", ".cpp", ".c", ".h", ".cs", ".xml", ".json", ".gradle",
            ".yml", ".yaml"
        )
        if (extHints.any { it in t }) return true
        if (Regex("""[\\/].+\.(\w{1,6})""").containsMatchIn(t)) return true

        val codeHints = listOf(
            "import ", "package ", "class ", "interface ", "fun ", "def ",
            "require(", "include(", "from ", "new ", "extends ", "implements "
        )
        if (codeHints.any { it in t }) return true

        val words = t.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.size <= 3 && setOf("hi", "hello", "hey", "yo").any { it == t || t.startsWith(it) }) return false

        return false
    }

    private fun appendUser(text: String) {
        appendBlock("You:", bold = true)
        appendBlock(text)
        appendSpacer()
    }

    private fun appendAssistant(text: String, isError: Boolean = false) {
        appendBlock("Assistant:", bold = true)
        val parts = splitByTripleBackticks(text)
        parts.forEachIndexed { i, part ->
            if (i % 2 == 1) appendCodeBlock(part) else appendBlock(part, isError = isError)
        }
        appendSpacer()
    }

    private fun splitByTripleBackticks(s: String): List<String> {
        return s.split("```")
    }

    private fun appendBlock(text: String, bold: Boolean = false, isError: Boolean = false) {
        SwingUtilities.invokeLater {
            val attrs = SimpleAttributeSet()
            StyleConstants.setFontFamily(attrs, transcript.font.family)
            StyleConstants.setBold(attrs, bold)
            if (isError) StyleConstants.setForeground(attrs, Color(0xB00020))
            doc.insertString(doc.length, text + "\n", attrs)
        }
    }

    private fun appendCodeBlock(code: String) {
        SwingUtilities.invokeLater {
            val attrs = SimpleAttributeSet()
            StyleConstants.setFontFamily(attrs, Font.MONOSPACED)
            StyleConstants.setBackground(attrs, Color(0xF5F5F5))
            doc.insertString(doc.length, code.trim() + "\n", attrs)
        }
    }

    private fun appendSpacer() = appendBlock("")

    private fun hasValidChatProfiles(): Boolean {
        val ps = AiProfilesState.getInstance(project)
        return ps.profiles.any { profile ->
            profile.roles.chat &&
                    profile.apiKey.isNotBlank() &&
                    profile.baseUrl.isNotBlank() &&
                    profile.model?.isNotBlank() == true
        }
    }

    private fun initProviderSelector() {
        refreshProviderSelector()
    }

    private fun refreshProviderSelector() {
        val ps = AiProfilesState.getInstance(project)

        Dev.info(log, "chat.profiles.debug",
            "allProfiles" to ps.profiles.size,
            "profilesDetails" to ps.profiles.map {
                "${it.label} (chat:${it.roles.chat}, summary:${it.roles.summary}, apiKey:${it.apiKey.isNotBlank()}, baseUrl:${it.baseUrl.isNotBlank()}, model:${it.model?.isNotBlank()})"
            })

        val chatProfiles = ps.profiles
            .filter { it.roles.chat }
            .filter { it.apiKey.isNotBlank() && it.baseUrl.isNotBlank() && it.model?.isNotBlank() == true }
            .sortedByDescending { it.id }

        Dev.info(log, "chat.filtered.profiles",
            "beforeFilter" to ps.profiles.count { it.roles.chat },
            "afterFilter" to chatProfiles.size)

        providerSelector.removeAllItems()
        summarySelector.removeAllItems()

        val hasValidProfiles = chatProfiles.isNotEmpty()

        if (!hasValidProfiles) {
            providerSelector.addItem("(No chat profiles)")
            providerSelector.isEnabled = false
            send.isEnabled = false
            input.isEnabled = false
            input.emptyText.text = "No AI profiles available - configure in Settings"

            summarySelector.removeAllItems()
            summarySelector.addItem("(No summary profiles)")
            summarySelector.isEnabled = false

            appendAssistant("❌ No AI profiles configured\n\nTo use this plugin, please:\n1. Go to Settings/Preferences → YMM Assistant\n2. Create at least one AI profile with API credentials\n3. Enable the 'Chat' role for the profile\n\nOnce configured, the chat will be enabled automatically.", isError = true)
            return
        }

        val items: List<Pair<String, String>> = chatProfiles.map { profile ->
            profile.id to "${profile.label.ifBlank { "Generic LLM" }} [${profile.protocol?.name ?: "unknown"}]"
        }
        items.forEach { (_, label) -> providerSelector.addItem(label) }

        val currentId = ps.selectedChatProfileId
        val selectedIdx = when {
            currentId != null -> items.indexOfFirst { it.first == currentId }.takeIf { it >= 0 } ?: 0
            items.isNotEmpty() -> 0
            else -> -1
        }

        if (selectedIdx >= 0) {
            providerSelector.selectedIndex = selectedIdx
            if (ps.selectedChatProfileId != items[selectedIdx].first) {
                ps.selectedChatProfileId = items[selectedIdx].first
            }
        }

        providerSelector.addActionListener {
            val idx = providerSelector.selectedIndex
            ps.selectedChatProfileId = if (idx in items.indices) items[idx].first else null
        }

        refreshSummarySelector()

        send.isEnabled = true
        input.isEnabled = true
        providerSelector.isEnabled = true
        input.emptyText.text = "Type a prompt…"

        transcript.text = ""
    }

    private fun initSummarySelector() {
        val ps = AiProfilesState.getInstance(project)
        val profiles = ps.profiles

        val summaryProfiles = profiles.filter { it.roles.summary }

        if (summaryProfiles.isEmpty()) {
            summarySelector.model = DefaultComboBoxModel(arrayOf("(no summary profiles)"))
            summarySelector.isEnabled = false
            return
        }

        val labels = summaryProfiles.map { it.labelOrFallback() }.toTypedArray()
        summarySelector.model = DefaultComboBoxModel(labels)

        val preferredId = ps.selectedSummaryProfileId
            ?: ps.selectedChatProfileId
            ?: summaryProfiles.first().id

        summarySelector.selectedItem = idToLabel(summaryProfiles, preferredId)

        summarySelector.addActionListener {
            val chosenLabel = summarySelector.selectedItem as String
            ps.selectedSummaryProfileId = labelToId(summaryProfiles, chosenLabel)
        }
    }

    private fun refreshSummarySelector() {
        val ps = AiProfilesState.getInstance(project)

        val summaryProfiles = ps.profiles
            .filter { it.roles.summary }
            .filter { it.apiKey.isNotBlank() && it.baseUrl.isNotBlank() && it.model?.isNotBlank() == true }
            .sortedByDescending { it.id }

        summarySelector.removeAllItems()

        val hasValidSummaryProfiles = summaryProfiles.isNotEmpty()

        if (!hasValidSummaryProfiles) {
            summarySelector.addItem("(No summary profiles)")
            summarySelector.isEnabled = false
            return
        }

        val items: List<Pair<String, String>> = summaryProfiles.map { profile ->
            profile.id to "${profile.label.ifBlank { "Generic LLM" }} [${profile.protocol?.name ?: "unknown"}]"
        }
        items.forEach { (_, label) -> summarySelector.addItem(label) }

        val currentId = ps.selectedSummaryProfileId
        val selectedIdx = when {
            currentId != null -> items.indexOfFirst { it.first == currentId }.takeIf { it >= 0 } ?: 0
            items.isNotEmpty() -> 0
            else -> -1
        }

        if (selectedIdx >= 0) {
            summarySelector.selectedIndex = selectedIdx
            if (ps.selectedSummaryProfileId != items[selectedIdx].first) {
                ps.selectedSummaryProfileId = items[selectedIdx].first
            }
        }

        summarySelector.addActionListener {
            val idx = summarySelector.selectedIndex
            ps.selectedSummaryProfileId = if (idx in items.indices) items[idx].first else null
        }

        summarySelector.isEnabled = true
    }

    private fun com.youmeandmyself.ai.settings.AiProfile.labelOrFallback(): String =
        if (label.isNotBlank()) label else "(unnamed) [${providerId}]"

    private fun idToLabel(
        list: List<com.youmeandmyself.ai.settings.AiProfile>,
        id: String?
    ): String {
        val found = list.firstOrNull { it.id == id } ?: list.first()
        return found.labelOrFallback()
    }

    private fun labelToId(
        list: List<com.youmeandmyself.ai.settings.AiProfile>,
        label: String
    ): String {
        return list.first { it.labelOrFallback() == label }.id
    }

    /**
     * Formats a compact context note from a ContextBundle for the model.
     */
    private fun formatContextNote(bundle: ContextBundle): String {
        val lang = bundle.language?.languageId ?: "unknown"
        val frameworks = bundle.frameworks.joinToString(", ") { f ->
            if (f.version.isNullOrBlank()) f.name else "${f.name} ${f.version}"
        }.ifBlank { "none" }
        val build = bundle.projectStructure?.buildSystem ?: "unknown"
        val modules = bundle.projectStructure?.modules?.joinToString(", ")?.ifBlank { null }

        val filesLine = if (bundle.files.isNotEmpty()) {
            val preview = bundle.files.take(5).joinToString("\n- ") { it.path }
            val truncNote = if (bundle.truncatedCount > 0) " (truncated: ${bundle.truncatedCount})" else ""
            "\nFiles: ${bundle.files.size}$truncNote (~${bundle.totalChars} chars)\n- $preview"
        } else ""

        val modulesLine = modules?.let { "\nModules: $it" } ?: ""
        return """
        [Context]
        Language: $lang
        Frameworks: $frameworks
        Build: $build$modulesLine$filesLine
    """.trimIndent()
    }

    /**
     * Detect deictic phrasing that clearly refers to the active file.
     */
    private fun refersToCurrentFile(text: String): Boolean {
        val t = text.lowercase()
        return listOf(
            "this file", "explain this file", "walk me through this file",
            "what does this file do", "analyze this file"
        ).any { it in t }
    }

    /**
     * Read editor file content with a safe cap, under ReadAction.
     */
    private fun readFileTextCapped(vf: VirtualFile, maxChars: Int = 50_000): String? {
        return try {
            ReadAction.compute<String?, Throwable> {
                val fdm = FileDocumentManager.getInstance()
                val doc = fdm.getDocument(vf)

                if (doc != null) {
                    val seq = doc.charsSequence
                    val end = minOf(seq.length, maxChars)
                    seq.subSequence(0, end).toString()
                } else {
                    if (!vf.isValid) return@compute null
                    val cs: Charset = vf.charset
                    vf.inputStream.use { input ->
                        val bytes = input.readNBytes(maxChars + 1)
                        val len = bytes.size.coerceAtMost(maxChars)
                        String(bytes, 0, len, cs)
                    }
                }
            }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Derive a reasonable fence language from file type name.
     */
    private fun fenceLangFor(vf: VirtualFile): String {
        val n = vf.fileType.name.lowercase()
        return when {
            n.contains("kotlin") -> "kotlin"
            n.contains("java") -> "java"
            n.contains("typescript") -> "ts"
            n.contains("javascript") -> "js"
            n.contains("json") -> "json"
            n.contains("xml") -> "xml"
            n.contains("yaml") || n.contains("yml") -> "yaml"
            else -> ""
        }
    }
}

// --- helpers ---

private fun ContextBundle.manifestLine(): String {
    val raw = files.count { it.kind == ContextKind.RAW }
    val sum = files.count { it.kind == ContextKind.SUMMARY }
    return "[Context: ${files.size} files ($raw raw, $sum summaries), ~${totalChars} chars]"
}

private fun ContextBundle.filesSection(): String = buildString {
    appendLine("### Files")
    files.forEach { cf ->
        appendLine()
        appendLine("```text")
        appendLine("// path: ${cf.path} | lang: ${cf.languageId ?: "unknown"} | why: ${cf.reason}")
        when (cf.kind) {
            ContextKind.RAW -> {
                appendLine("// kind: RAW${if (cf.truncated) " (truncated)" else ""}")
            }
            ContextKind.SUMMARY -> {
                appendLine("// kind: SUMMARY${if (cf.isStale) " (synopsis may be outdated)" else ""}")
                if (!cf.headerSample.isNullOrBlank()) {
                    appendLine("[HEADER SAMPLE]")
                    appendLine(cf.headerSample)
                }
                if (!cf.modelSynopsis.isNullOrBlank()) {
                    appendLine()
                    appendLine("[SYNOPSIS]")
                    appendLine(cf.modelSynopsis)
                }
            }
        }
        appendLine("```")
    }
}