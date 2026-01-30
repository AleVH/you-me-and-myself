package com.youmeandmyself.ai.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import javax.swing.*
import javax.swing.event.ListSelectionListener
import javax.swing.event.DocumentListener
import javax.swing.event.DocumentEvent
import com.youmeandmyself.ai.settings.ApiProtocol
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import com.youmeandmyself.ai.net.HttpClientFactory
import com.youmeandmyself.dev.Dev
import io.ktor.client.request.header

/**
 * Configuration UI for managing AI profiles in the YMM Assistant plugin.
 *
 * ## Architecture Overview
 *
 * This configurable manages a list of AI profiles, each containing:
 * - Label (display name)
 * - Protocol (OpenAI-compatible, Gemini, or Custom)
 * - API Key and Base URL (credentials)
 * - Model (selected from a dynamically fetched list)
 * - Roles (chat, summary, or both)
 *
 * ## User Flow for Creating a Profile
 *
 * 1. User fills in the form fields (label, protocol, API key, base URL)
 * 2. When API key + base URL are both entered, models are fetched automatically
 * 3. User selects a model from the dropdown
 * 4. User checks the role boxes (chat, summary, or both)
 * 5. User clicks "Add" → profile is created from form values and added to list
 * 6. Form is CLEARED so user can add another profile (or click existing to edit)
 * 7. User clicks "Apply" → all profiles in workingCopy are persisted to state
 *
 * ## Two Modes of Operation
 *
 * The form operates in two distinct modes:
 *
 * **CREATE MODE** (no list selection):
 * - Form is empty/cleared
 * - User fills in fields
 * - "Add" button creates a new profile from form values
 * - After adding, form clears and stays in CREATE mode
 *
 * **EDIT MODE** (profile selected in list):
 * - Form shows the selected profile's data
 * - All field changes update the selected profile in real-time
 * - "Add" button still works (creates new profile from current form values)
 * - Clicking a different profile switches to editing that one
 *
 * ## Data Flow
 *
 * 1. On open: `reset()` loads profiles from persistent state into `workingCopy`
 * 2. No selection = CREATE mode (form is for new profiles)
 * 3. Selecting a profile = EDIT mode (form bound to that profile)
 * 4. "Add" always creates from form, then clears selection (back to CREATE mode)
 * 5. On Apply: `apply()` saves `workingCopy` back to persistent state
 * 6. On Cancel: changes in `workingCopy` are discarded
 *
 * ## Important: Model Fetching
 *
 * Models are fetched dynamically from the API when BOTH apiKey AND baseUrl are provided.
 * The fetch is triggered by:
 * - Typing in apiKey or baseUrl fields (via document listeners)
 * - Selecting an existing profile (via `onListSelectionChanged`)
 *
 * The selected model MUST be saved to the profile, otherwise `ChatPanel` will filter
 * out the profile as invalid (it requires: apiKey + baseUrl + model all non-blank).
 *
 * ## Common Pitfalls (for future developers)
 *
 * 1. Model is null after creation: Ensure modelField selection is read in addProfile()
 * 2. Profiles not showing in chat: Check the filter in `ChatPanel.refreshProviderSelector()`
 *    - Requires: roles.chat=true, apiKey not blank, baseUrl not blank, model not blank
 * 3. Models not fetching: Both apiKey AND baseUrl must be non-blank
 * 4. Changes lost on cancel: That's expected - we work on `workingCopy`, not state
 * 5. Form creates blank profile: addProfile() must READ from form fields, not use defaults
 * 6. Duplicate profiles on Add: Must clear selection after adding to exit EDIT mode
 */
class AiProfilesConfigurable(private val project: Project) : Configurable {
    private val log = Dev.logger(AiProfilesConfigurable::class.java)

    // ==================== STATE ====================
    // We maintain a working copy that's only persisted on Apply

    private val state get() = AiProfilesState.getInstance(project)

    /** Working copy of profiles - changes here don't affect persistent state until apply() */
    private var workingCopy: MutableList<AiProfile> = mutableListOf()

    /** Currently selected profile ID for chat role */
    private var selectedChatId: String? = null

    /** Currently selected profile ID for summary role */
    private var selectedSummaryId: String? = null

    // ==================== UI COMPONENTS ====================

    // Left panel: list of profiles
    private val listModel = DefaultListModel<AiProfile>()
    private val list = JBList(listModel)

    // Right panel: editor form fields
    private val labelField = JBTextField()
    private val apiKeyField = JPasswordField()
    private val baseUrlField = JBTextField()
    private val modelField = JComboBox<String>()
    private val chatCheck = JBCheckBox("Use for Chat")
    private val summaryCheck = JBCheckBox("Use for Summary")

    // Bottom panel: global profile selection for chat/summary
    private val chatProfileBox = JComboBox<String>()
    private val summaryProfileBox = JComboBox<String>()

    // Protocol selection
    private lateinit var protocolLabel: JLabel
    private lateinit var protocolCombo: JComboBox<ApiProtocol>
    private lateinit var protocolNote: JLabel

    // Root container
    private val root = JPanel(BorderLayout(12, 12))

    // ==================== FLAGS ====================

    /**
     * Prevents recursive/duplicate model fetches.
     * Set to true while a fetch is in progress.
     */
    private var isFetchingModels = false

    /**
     * Tracks whether we're programmatically updating fields (vs user editing).
     * When true, document listeners should NOT trigger model fetch or profile updates.
     * This prevents fetch loops when we populate fields from a selected profile.
     */
    private var isUpdatingFieldsProgrammatically = false

    override fun getDisplayName(): String = "YMM Assistant"

    override fun createComponent(): JComponent {
        // ==================== LEFT PANEL: Profile List ====================

        list.cellRenderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean
            ): Component {
                val c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus) as JLabel
                val p = value as AiProfile
                val proto = (p.protocol ?: ApiProtocol.OPENAI_COMPAT).name
                c.text = "${p.label} [$proto]"
                return c
            }
        }

        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.addListSelectionListener(ListSelectionListener {
            if (!it.valueIsAdjusting) {
                onListSelectionChanged()
            }
        })

        val addBtn = JButton("Add").apply { addActionListener { addProfile() } }
        val dupBtn = JButton("Duplicate").apply { addActionListener { duplicateProfile() } }
        val delBtn = JButton("Delete").apply { addActionListener { deleteProfile() } }

        val leftButtons = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(addBtn); add(Box.createHorizontalStrut(8))
            add(dupBtn); add(Box.createHorizontalStrut(8))
            add(delBtn)
        }

        val leftPanel = JPanel(BorderLayout(0, 8)).apply {
            preferredSize = Dimension(260, 300)
            add(JScrollPane(list), BorderLayout.CENTER)
            add(leftButtons, BorderLayout.SOUTH)
        }

        // ==================== RIGHT PANEL: Profile Editor ====================

        protocolLabel = JLabel("Protocol")
        protocolCombo = JComboBox(ApiProtocol.values()).apply {
            selectedItem = ApiProtocol.OPENAI_COMPAT
        }
        protocolNote = JBLabel("""
        <html>
          <b>Protocol:</b> how the plugin talks to your model API.<br/>
          • <b>Gemini</b> → Google Gemini (unique endpoint & body).<br/>
          • <b>OpenAI-compatible</b> → Most others (OpenAI, OpenRouter, many gateways).<br/>
          • <b>Custom</b> → If the above fail; you can specify a custom path and auth header.<br/>
          <i>Tip:</i> If you use Gemini, choose <b>Gemini</b>. Otherwise choose <b>OpenAI-compatible</b>. If that fails, try <b>Custom</b>.
        </html>
        """.trimIndent()
        )

        val form = FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel("Label:"), labelField, 1, false)
            .addLabeledComponent(protocolLabel, protocolCombo, 1, false)
            .addComponent(protocolNote)
            .addLabeledComponent(JBLabel("API Key:"), apiKeyField, 1, false)
            .addLabeledComponent(JBLabel("Base URL:"), baseUrlField, 1, false)
            .addLabeledComponent(JBLabel("Model:"), modelField, 1, false)
            .addComponent(chatCheck)
            .addComponent(summaryCheck)
            .panel

        val rightPanel = JPanel(BorderLayout()).apply {
            add(form, BorderLayout.NORTH)
        }

        // ==================== BOTTOM PANEL: Global Selection ====================

        val bottom = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(JBLabel("Chat profile: "))
            add(chatProfileBox)
            add(Box.createHorizontalStrut(16))
            add(JBLabel("Summary profile: "))
            add(summaryProfileBox)
        }

        // ==================== ASSEMBLE ROOT ====================

        root.add(leftPanel, BorderLayout.WEST)
        root.add(rightPanel, BorderLayout.CENTER)
        root.add(bottom, BorderLayout.SOUTH)

        // ==================== WIRE UP LISTENERS ====================

        setupFieldListeners()
        setupBottomBoxListeners()

        // Load initial data
        reset()

        // Development mode: add reset button for testing
        if (isDevelopmentMode()) {
            val resetBtn = JButton("DEV: Reset All Profiles").apply {
                addActionListener {
                    workingCopy.clear()
                    selectedChatId = null
                    selectedSummaryId = null
                    listModel.clear()
                    clearEditor()
                    syncBottomBoxes()
                    // Also clear persistent state
                    state.profiles.clear()
                    state.selectedChatProfileId = null
                    state.selectedSummaryProfileId = null
                }
            }
            leftButtons.add(resetBtn)
        }

        return root
    }

    /**
     * Sets up listeners for the bottom Chat/Summary profile dropdown boxes.
     *
     * These listeners update selectedChatId/selectedSummaryId in real-time
     * so the selection is preserved correctly.
     */
    private fun setupBottomBoxListeners() {
        chatProfileBox.addActionListener {
            if (isUpdatingFieldsProgrammatically) return@addActionListener
            val selectedLabel = chatProfileBox.selectedItem as? String
            selectedChatId = selectedLabel?.let { labelToId(it) }
            Dev.info(log, "chatProfileBox.selected",
                "label" to selectedLabel,
                "id" to selectedChatId)
        }

        summaryProfileBox.addActionListener {
            if (isUpdatingFieldsProgrammatically) return@addActionListener
            val selectedLabel = summaryProfileBox.selectedItem as? String
            selectedSummaryId = selectedLabel?.let { labelToId(it) }
            Dev.info(log, "summaryProfileBox.selected",
                "label" to selectedLabel,
                "id" to selectedSummaryId)
        }
    }

    /**
     * Sets up all field listeners for real-time profile updates.
     *
     * ## IMPORTANT: These listeners only update profiles in EDIT MODE
     *
     * When a profile is selected (current() != null), changes to form fields
     * immediately update that profile. This provides real-time editing.
     *
     * When NO profile is selected (CREATE MODE), the listeners do nothing
     * to the working copy - the form is just collecting input for when
     * the user clicks "Add".
     */
    private fun setupFieldListeners() {
        // ----------------------------------------------------------------------
        // Label field: update profile label as user types (EDIT MODE only)
        // ----------------------------------------------------------------------
        labelField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) = updateCurrentProfileLabel()
            override fun removeUpdate(e: DocumentEvent?) = updateCurrentProfileLabel()
            override fun changedUpdate(e: DocumentEvent?) = updateCurrentProfileLabel()

            private fun updateCurrentProfileLabel() {
                if (isUpdatingFieldsProgrammatically) return
                val profile = current() ?: return  // Only update if a profile is selected
                profile.label = labelField.text.trim()
                // Refresh list to show updated label
                list.repaint()
            }
        })

        // ----------------------------------------------------------------------
        // API Key field: update profile and trigger model fetch
        // ----------------------------------------------------------------------
        apiKeyField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) = onApiKeyChanged()
            override fun removeUpdate(e: DocumentEvent?) = onApiKeyChanged()
            override fun changedUpdate(e: DocumentEvent?) = onApiKeyChanged()

            private fun onApiKeyChanged() {
                if (isUpdatingFieldsProgrammatically) return
                current()?.apiKey = String(apiKeyField.password)  // Only if profile selected
                maybeScheduleModelFetch()
            }
        })

        // ----------------------------------------------------------------------
        // Base URL field: update profile and trigger model fetch
        // ----------------------------------------------------------------------
        baseUrlField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) = onBaseUrlChanged()
            override fun removeUpdate(e: DocumentEvent?) = onBaseUrlChanged()
            override fun changedUpdate(e: DocumentEvent?) = onBaseUrlChanged()

            private fun onBaseUrlChanged() {
                if (isUpdatingFieldsProgrammatically) return
                current()?.baseUrl = baseUrlField.text.trim()  // Only if profile selected
                maybeScheduleModelFetch()
            }
        })

        // ----------------------------------------------------------------------
        // Protocol combo: update profile and trigger model fetch
        // (Different protocols may have different model endpoints)
        // ----------------------------------------------------------------------
        protocolCombo.addActionListener {
            if (isUpdatingFieldsProgrammatically) return@addActionListener
            current()?.protocol = protocolCombo.selectedItem as? ApiProtocol ?: ApiProtocol.OPENAI_COMPAT
            maybeScheduleModelFetch()
        }

        // ----------------------------------------------------------------------
        // Model combo: CRITICAL - save selected model to profile
        // Without this, profile.model stays null and ChatPanel filters it out!
        // ----------------------------------------------------------------------
        modelField.addActionListener {
            if (isUpdatingFieldsProgrammatically) return@addActionListener

            val selectedModel = modelField.selectedItem as? String

            // Don't save placeholder/error values
            if (selectedModel != null &&
                !selectedModel.startsWith("Fetching") &&
                !selectedModel.startsWith("⚠️") &&
                !selectedModel.startsWith("(") &&
                selectedModel.isNotBlank()) {

                current()?.model = selectedModel  // Only if profile selected

                Dev.info(log, "model.selected",
                    "model" to selectedModel,
                    "profile" to current()?.label,
                    "profileId" to current()?.id
                )
            }
        }

        // ----------------------------------------------------------------------
        // Role checkboxes: update profile roles and refresh bottom boxes
        // ----------------------------------------------------------------------
        chatCheck.addChangeListener {
            if (isUpdatingFieldsProgrammatically) return@addChangeListener
            current()?.roles = current()?.roles?.copy(chat = chatCheck.isSelected)
                ?: AiRoles(chat = chatCheck.isSelected, summary = summaryCheck.isSelected)
            syncBottomBoxes()
        }

        summaryCheck.addChangeListener {
            if (isUpdatingFieldsProgrammatically) return@addChangeListener
            current()?.roles = current()?.roles?.copy(summary = summaryCheck.isSelected)
                ?: AiRoles(chat = chatCheck.isSelected, summary = summaryCheck.isSelected)
            syncBottomBoxes()
        }
    }

    /**
     * Triggers model fetch if both API key and base URL are provided.
     * Uses debouncing via isFetchingModels flag to prevent duplicate fetches.
     */
    private fun maybeScheduleModelFetch() {
        val url = baseUrlField.text.trim()
        val key = String(apiKeyField.password)

        // Only fetch if we have both credentials
        if (url.isBlank() || key.isBlank()) {
            return
        }

        // Debounce: don't start another fetch if one is in progress
        if (isFetchingModels) {
            return
        }

        scheduleModelFetch()
    }

    private fun isDevelopmentMode(): Boolean {
        return System.getProperty("idea.is.internal") != null
    }

    override fun isModified(): Boolean {
        val s = state
        if (s.selectedChatProfileId != selectedChatId) return true
        if (s.selectedSummaryProfileId != selectedSummaryId) return true
        if (s.profiles.size != workingCopy.size) return true
        return s.profiles.zip(workingCopy).any { (a, b) -> a != b }
    }

    /**
     * Called when user selects a different profile in the list.
     * Populates the editor form with the selected profile's data.
     *
     * If selection is cleared (no profile selected), this puts the form
     * into CREATE MODE where it's ready for new profile input.
     */
    private fun onListSelectionChanged() {
        Dev.info(log, "onListSelectionChanged.called", "selectedIndex" to list.selectedIndex)

        val p = current()
        if (p == null) {
            // No selection = CREATE MODE
            // Don't clear the form here - let clearEditor() handle it when appropriate
            Dev.info(log, "onListSelectionChanged.noSelection")
            return
        }

        Dev.info(log, "onListSelectionChanged.loadingProfile",
            "profile" to p.label,
            "selectedIndex" to list.selectedIndex,
            "model" to p.model
        )

        // Set flag to prevent listeners from triggering during programmatic updates
        isUpdatingFieldsProgrammatically = true
        try {
            labelField.text = p.label
            apiKeyField.text = p.apiKey
            baseUrlField.text = p.baseUrl
            protocolCombo.selectedItem = p.protocol ?: ApiProtocol.OPENAI_COMPAT
            chatCheck.isSelected = p.roles.chat
            summaryCheck.isSelected = p.roles.summary

            // Show saved model while fetching (or placeholder if none)
            modelField.removeAllItems()
            if (!p.model.isNullOrBlank()) {
                modelField.addItem(p.model)
                modelField.selectedItem = p.model
            } else {
                modelField.addItem("(select after fetch)")
            }
            modelField.isEnabled = false
        } finally {
            isUpdatingFieldsProgrammatically = false
        }

        // Fetch models if we have credentials
        if (p.apiKey.isNotBlank() && p.baseUrl.isNotBlank()) {
            scheduleModelFetch()
        }
    }

    /**
     * Saves all changes to persistent state.
     * Called when user clicks OK or Apply in the settings dialog.
     */
    override fun apply() {
        Dev.info(log, "config.apply.start")

        // Update global chat/summary profile selections from the bottom dropdowns
        selectedChatId = (chatProfileBox.selectedItem as? String)?.let { labelToId(it) }
        selectedSummaryId = (summaryProfileBox.selectedItem as? String)?.let { labelToId(it) }

        // Persist working copy to state
        state.profiles.clear()
        state.profiles.addAll(workingCopy)
        state.selectedChatProfileId = selectedChatId
        state.selectedSummaryProfileId = selectedSummaryId

        Dev.info(log, "config.apply.complete",
            "profileCount" to state.profiles.size,
            "chatId" to selectedChatId,
            "summaryId" to selectedSummaryId
        )
    }

    /**
     * Reloads data from persistent state, discarding any unsaved changes.
     * Called on dialog open and when user clicks Reset.
     */
    override fun reset() {
        // Deep copy from state into working copy
        workingCopy = state.profiles.map { it.copy(roles = it.roles.copy()) }.toMutableList()
        selectedChatId = state.selectedChatProfileId
        selectedSummaryId = state.selectedSummaryProfileId

        // Repopulate list
        listModel.clear()
        workingCopy.forEach { listModel.addElement(it) }

        // Start with no selection (CREATE MODE) if no profiles exist
        // Otherwise select the first profile (EDIT MODE)
        if (listModel.size > 0) {
            list.selectedIndex = 0
        } else {
            clearEditor()
        }

        syncBottomBoxes()
    }

    override fun disposeUIResources() { /* nothing to dispose */ }

    // ==================== HELPER METHODS ====================

    /** Returns the currently selected profile in the list, or null if none selected (CREATE MODE) */
    private fun current(): AiProfile? = list.selectedValue

    /** Clears all editor fields to their default/empty state (for CREATE MODE) */
    private fun clearEditor() {
        isUpdatingFieldsProgrammatically = true
        try {
            labelField.text = ""
            apiKeyField.text = ""
            baseUrlField.text = ""
            modelField.removeAllItems()
            modelField.isEnabled = false
            chatCheck.isSelected = true
            summaryCheck.isSelected = true
            protocolCombo.selectedItem = ApiProtocol.OPENAI_COMPAT
        } finally {
            isUpdatingFieldsProgrammatically = false
        }
    }

    /**
     * Creates a new profile from the current form fields and adds it to the list.
     *
     * ## Flow:
     * 1. User fills in the form (label, API key, URL, model, roles)
     * 2. User clicks "Add"
     * 3. We READ the current form values and create a profile from them
     * 4. Add to workingCopy and listModel
     * 5. CLEAR the selection (exit EDIT MODE, enter CREATE MODE)
     * 6. CLEAR the form for the next profile
     * 7. User can add another profile or click an existing one to edit
     * 8. User clicks Apply to persist all changes
     *
     * ## Important:
     * - This reads FROM the form fields - it does NOT create blank defaults!
     * - The form should be filled in BEFORE clicking Add.
     * - After adding, we MUST clear the selection to prevent the "duplicate profile" bug
     */
    private fun addProfile() {
        Dev.info(log, "addProfile.start")

        // Read current values from form fields
        val labelText = labelField.text.trim()
        val apiKey = String(apiKeyField.password)
        val baseUrl = baseUrlField.text.trim()
        val protocol = (protocolCombo.selectedItem as? ApiProtocol) ?: ApiProtocol.OPENAI_COMPAT
        val selectedModel = (modelField.selectedItem as? String)?.takeIf {
            it.isNotBlank() &&
                    !it.startsWith("Fetching") &&
                    !it.startsWith("⚠️") &&
                    !it.startsWith("(")
        }

        // Validate: at minimum we need a label or some identifying info
        if (labelText.isBlank() && apiKey.isBlank() && baseUrl.isBlank()) {
            Dev.warn(log, "addProfile.empty", null, "reason" to "all fields blank")
            // Could show a message to user here, but for now just log
            return
        }

        // Create profile from form values
        val p = AiProfile(
            label = labelText.ifBlank { "Unnamed Profile" },
            providerId = "",
            apiKey = apiKey,
            baseUrl = baseUrl,
            model = selectedModel,
            protocol = protocol,
            roles = AiRoles(chat = chatCheck.isSelected, summary = summaryCheck.isSelected)
        )

        Dev.info(log, "addProfile.creating",
            "label" to p.label,
            "model" to p.model,
            "hasApiKey" to apiKey.isNotBlank(),
            "hasBaseUrl" to baseUrl.isNotBlank()
        )

        workingCopy.add(p)
        listModel.addElement(p)

        // CRITICAL: Clear selection to exit EDIT MODE and enter CREATE MODE
        // This prevents the bug where:
        // 1. Profile is added and selected
        // 2. Form shows the new profile's data (bound via listeners)
        // 3. Any edit modifies that profile
        // 4. Clicking "Add" again creates a duplicate
        //
        // By clearing the selection, the form becomes an input form again,
        // not an editor for the just-added profile.
        list.clearSelection()

        // Clear the form for the next profile
        clearEditor()

        syncBottomBoxes()

        Dev.info(log, "addProfile.complete",
            "profileId" to p.id,
            "label" to p.label,
            "model" to p.model,
            "selectedIndex" to list.selectedIndex,
            "listSize" to listModel.size
        )
    }

    private fun duplicateProfile() {
        val cur = current() ?: return
        val p = cur.copy(
            id = java.util.UUID.randomUUID().toString(),
            label = cur.label + " (copy)",
            roles = cur.roles.copy()
        )
        workingCopy.add(p)
        listModel.addElement(p)
        list.selectedIndex = listModel.size - 1  // Select the duplicate for immediate editing
        syncBottomBoxes()
    }

    private fun deleteProfile() {
        val idx = list.selectedIndex
        if (idx < 0) return

        val removed = workingCopy.removeAt(idx)
        Dev.info(log, "profile.delete", "label" to removed.label, "id" to removed.id)

        listModel.remove(idx)

        // Clear selection references if deleted profile was selected
        if (removed.id == selectedChatId) selectedChatId = null
        if (removed.id == selectedSummaryId) selectedSummaryId = null

        if (listModel.size > 0) {
            list.selectedIndex = 0
        } else {
            clearEditor()
        }

        syncBottomBoxes()
    }

    /**
     * Synchronizes the bottom dropdown boxes with current workingCopy.
     * Only shows profiles that have the appropriate role enabled.
     *
     * IMPORTANT: Uses isUpdatingFieldsProgrammatically flag to prevent
     * the dropdown listeners from firing during this update.
     */
    private fun syncBottomBoxes() {
        isUpdatingFieldsProgrammatically = true
        try {
            // Chat dropdown: only profiles with chat role enabled
            val chatLabels = workingCopy
                .filter { it.roles.chat }
                .map { it.labelOrFallback() }
                .toTypedArray()

            // Summary dropdown: only profiles with summary role enabled
            val summaryLabels = workingCopy
                .filter { it.roles.summary }
                .map { it.labelOrFallback() }
                .toTypedArray()

            chatProfileBox.model = DefaultComboBoxModel(chatLabels)
            summaryProfileBox.model = DefaultComboBoxModel(summaryLabels)

            // Restore selection based on selectedChatId/selectedSummaryId
            // If no prior selection exists, keep the dropdown at whatever the model defaults to
            // (which will be the first item, or nothing if empty)
            val chatLabel = idToLabelForChat(selectedChatId)
            val summaryLabel = idToLabelForSummary(selectedSummaryId)

            if (chatLabel.isNotBlank()) {
                chatProfileBox.selectedItem = chatLabel
            }
            if (summaryLabel.isNotBlank()) {
                summaryProfileBox.selectedItem = summaryLabel
            }
        } finally {
            isUpdatingFieldsProgrammatically = false
        }
    }

    /**
     * Converts a profile ID to its label for the Chat dropdown.
     * If ID is null or not found, falls back to the first available chat profile.
     */
    private fun idToLabelForChat(id: String?): String {
        // First, try to find the profile with the given ID
        if (id != null) {
            val found = workingCopy
                .filter { it.roles.chat }
                .firstOrNull { it.id == id }?.labelOrFallback()
            if (found != null) return found
        }
        // Fallback: first available chat profile
        return workingCopy
            .filter { it.roles.chat }
            .firstOrNull()?.labelOrFallback() ?: ""
    }

    /**
     * Converts a profile ID to its label for the Summary dropdown.
     * If ID is null or not found, falls back to the first available summary profile.
     */
    private fun idToLabelForSummary(id: String?): String {
        // First, try to find the profile with the given ID
        if (id != null) {
            val found = workingCopy
                .filter { it.roles.summary }
                .firstOrNull { it.id == id }?.labelOrFallback()
            if (found != null) return found
        }
        // Fallback: first available summary profile
        return workingCopy
            .filter { it.roles.summary }
            .firstOrNull()?.labelOrFallback() ?: ""
    }

    private fun AiProfile.labelOrFallback(): String =
        if (label.isNotBlank()) label else "(unnamed) [${providerId}]"

    private fun labelToId(label: String): String? =
        workingCopy.firstOrNull { it.labelOrFallback() == label }?.id

    // ==================== MODEL FETCHING ====================

    /**
     * Fetches available models from the API and populates the model dropdown.
     *
     * ## Important behaviors:
     * - Shows "Fetching..." while in progress
     * - Preserves the profile's saved model if it exists in the fetched list
     * - Auto-selects first model if no saved model
     * - Saves the selected model back to the profile (in EDIT MODE)
     */
    private fun scheduleModelFetch() {
        val url = baseUrlField.text.trim()
        val key = String(apiKeyField.password)
        val proto = (protocolCombo.selectedItem as? ApiProtocol) ?: ApiProtocol.OPENAI_COMPAT

        Dev.info(log, "scheduleModelFetch.called", "url" to url)

        if (url.isBlank() || key.isBlank()) {
            return
        }

        isFetchingModels = true

        // UI: show fetching state
        ApplicationManager.getApplication().invokeLater {
            isUpdatingFieldsProgrammatically = true
            modelField.removeAllItems()
            modelField.addItem("Fetching…")
            modelField.isEnabled = false
            isUpdatingFieldsProgrammatically = false
        }

        fetchModelsAsync(url, key, proto) { models ->
            isFetchingModels = false

            isUpdatingFieldsProgrammatically = true
            try {
                modelField.removeAllItems()

                if (models.isEmpty()) {
                    modelField.addItem("(no models found)")
                    modelField.isEnabled = false
                    return@fetchModelsAsync
                }

                // Populate dropdown with fetched models
                models.forEach { modelField.addItem(it) }
                modelField.isEnabled = true

                // Try to restore the profile's saved model (only relevant in EDIT MODE)
                val savedModel = current()?.model

                if (!savedModel.isNullOrBlank() && models.contains(savedModel)) {
                    // Saved model exists in fetched list - select it
                    modelField.selectedItem = savedModel
                    Dev.info(log, "model.restored", "model" to savedModel)
                } else if (!savedModel.isNullOrBlank()) {
                    // Saved model NOT in fetched list - show warning but keep it
                    modelField.insertItemAt("⚠️ $savedModel (not available)", 0)
                    modelField.selectedIndex = 0
                    Dev.warn(log, "model.notFound", null, "savedModel" to savedModel)
                } else {
                    // No saved model - auto-select first
                    // In EDIT MODE, also save it to the profile
                    modelField.selectedIndex = 0
                    val autoSelected = models.first()
                    current()?.model = autoSelected  // Only saves if profile is selected
                    Dev.info(log, "model.autoSelected", "model" to autoSelected)
                }
            } finally {
                isUpdatingFieldsProgrammatically = false
            }
        }
    }

    private fun fetchModelsAsync(
        baseUrl: String,
        apiKey: String,
        proto: ApiProtocol,
        onComplete: (List<String>) -> Unit
    ) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(null, "Fetching models", false) {
            override fun run(indicator: com.intellij.openapi.progress.ProgressIndicator) {
                try {
                    val models: List<String> = when (proto) {
                        ApiProtocol.OPENAI_COMPAT, ApiProtocol.CUSTOM -> fetchOpenAiCompatModels(baseUrl, apiKey)
                        ApiProtocol.GEMINI -> fetchGeminiModels(baseUrl, apiKey)
                    }
                    ApplicationManager.getApplication().invokeLater {
                        onComplete(models)
                    }
                } catch (e: Throwable) {
                    Dev.error(log, "fetchModelsAsync.failed", e, "url" to baseUrl)
                    ApplicationManager.getApplication().invokeLater {
                        onComplete(emptyList())
                    }
                }
            }
        })
    }

    private suspend fun httpGetText(url: String, headers: List<Pair<String, String>> = emptyList()): String {
        val client = HttpClientFactory.client
        val resp = client.get(url) {
            headers.forEach { (k, v) -> header(k, v) }
        }
        return resp.body()
    }

    private fun parseOpenAiModels(json: String): List<String> {
        val regex = """"id"\s*:\s*"([^"]+)"""".toRegex()
        return regex.findAll(json).map { it.groupValues[1] }.distinct().toList().sorted()
    }

    private fun parseGeminiModels(json: String): List<String> {
        val regex = """"name"\s*:\s*"([^"]+)"""".toRegex()
        return regex.findAll(json)
            .map { it.groupValues[1].substringAfterLast('/') }
            .distinct()
            .toList()
            .sorted()
    }

    private fun fetchOpenAiCompatModels(baseUrl: String, apiKey: String): List<String> {
        try {
            val base = normalizeBaseUrl(baseUrl)
            val url = "$base/v1/models"
            Dev.info(log, "config.openai.fetch.start", "url" to url, "baseUrl" to baseUrl)

            val json = kotlinx.coroutines.runBlocking {
                httpGetText(url, headers = listOf(HttpHeaders.Authorization to "Bearer $apiKey"))
            }

            Dev.info(log, "config.openai.fetch.success",
                "responseLength" to json.length,
                "first100chars" to json.take(100))
            return parseOpenAiModels(json)
        } catch (e: Exception) {
            Dev.error(log, "config.openai.fetch.failed", e,
                "url" to baseUrl,
                "message" to e.message)

            // Try alternative endpoint (some providers use /api/models)
            return try {
                val base = normalizeBaseUrl(baseUrl)
                val altUrl = "$base/api/models"
                Dev.info(log, "config.openai.alt.try", "url" to altUrl)

                val json = kotlinx.coroutines.runBlocking {
                    httpGetText(altUrl, headers = listOf(HttpHeaders.Authorization to "Bearer $apiKey"))
                }
                Dev.info(log, "config.openai.alt.success", "url" to altUrl)
                parseOpenAiModels(json)
            } catch (e2: Exception) {
                Dev.error(log, "config.openai.alt.fetch.failed", e2,
                    "url" to baseUrl,
                    "message" to e2.message)
                emptyList()
            }
        }
    }

    private fun fetchGeminiModels(baseUrl: String, apiKey: String): List<String> {
        val base = normalizeBaseUrl(baseUrl)
        val url = if (base.endsWith("/v1beta") || base.contains("/v1beta/")) {
            "$base/models?key=$apiKey"
        } else {
            "$base/v1beta/models?key=$apiKey"
        }
        val json = kotlinx.coroutines.runBlocking { httpGetText(url) }
        return parseGeminiModels(json)
    }

    private fun normalizeBaseUrl(u: String): String {
        var b = u.trim()
        if (!b.startsWith("http://") && !b.startsWith("https://")) b = "https://$b"
        return b.trimEnd('/')
    }
}