package com.youmeandmyself.ai.providers.parsing.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import javax.swing.*
import javax.swing.event.ListSelectionListener

/**
 * Dialog for user to select the correct content from ranked candidates.
 *
 * Used in two scenarios:
 *
 * ## Scenario 2: Correction
 * When heuristic showed content but user clicked "Not right?"
 * - Shows: "The auto-detected response doesn't look right? Select the correct one:"
 * - Pre-selects: The second-best candidate (since best was wrong)
 *
 * ## Scenario 3: Initial Selection
 * When confidence was too low to guess
 * - Shows: "We couldn't automatically detect the response. Please select:"
 * - No pre-selection
 *
 * In both cases:
 * - Shows ranked list of candidates with previews
 * - Shows full content of selected candidate in preview pane
 * - Option to "Remember for this provider" (creates FormatHint)
 * - Option to view raw JSON
 */
class CorrectionDialog(
    private val project: Project?,
    private val candidates: List<TextCandidate>,
    private val rawJson: String,
    private val providerId: String,
    private val modelId: String?,
    private val isCorrection: Boolean // true = scenario 2, false = scenario 3
) : DialogWrapper(project) {

    private val candidateList: JBList<TextCandidate>
    private val previewArea: JBTextArea
    private val rememberCheckbox: JCheckBox

    private var selectedCandidate: TextCandidate? = null

    init {
        title = if (isCorrection) {
            "Select Correct Response"
        } else {
            "Response Format Unknown"
        }

        // Setup candidate list
        candidateList = JBList(candidates).apply {
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            cellRenderer = CandidateListCellRenderer()

            // Pre-select based on scenario
            if (candidates.isNotEmpty()) {
                selectedIndex = if (isCorrection && candidates.size > 1) 1 else 0
            }
        }

        // Setup preview area
        previewArea = JBTextArea().apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            font = Font(Font.MONOSPACED, Font.PLAIN, 12)
            border = JBUI.Borders.empty(8)
        }

        // Setup remember checkbox
        rememberCheckbox = JCheckBox("Remember this for $providerId").apply {
            toolTipText = "Next time, we'll try this format first for responses from $providerId"
            isSelected = true
        }

        // Wire up selection listener
        candidateList.addListSelectionListener(ListSelectionListener {
            if (!it.valueIsAdjusting) {
                updatePreview()
            }
        })

        // Initial preview
        updatePreview()

        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(0, 12)).apply {
            border = JBUI.Borders.empty(12)
            preferredSize = Dimension(700, 500)
        }

        // Header label
        val headerText = if (isCorrection) {
            "The auto-detected response doesn't look right? Select the correct one below:"
        } else {
            "We couldn't automatically detect the response format. Please select the actual content:"
        }
        val headerLabel = JBLabel(headerText).apply {
            border = JBUI.Borders.emptyBottom(8)
        }
        panel.add(headerLabel, BorderLayout.NORTH)

        // Split pane: candidates list (left) and preview (right)
        val splitPane = JSplitPane(JSplitPane.HORIZONTAL_SPLIT).apply {
            dividerLocation = 300

            // Left: candidate list
            leftComponent = JPanel(BorderLayout()).apply {
                add(JBLabel("Candidates (ranked by likelihood):").apply {
                    border = JBUI.Borders.emptyBottom(4)
                }, BorderLayout.NORTH)
                add(JBScrollPane(candidateList), BorderLayout.CENTER)
            }

            // Right: preview
            rightComponent = JPanel(BorderLayout()).apply {
                add(JBLabel("Preview:").apply {
                    border = JBUI.Borders.emptyBottom(4)
                }, BorderLayout.NORTH)
                add(JBScrollPane(previewArea), BorderLayout.CENTER)
            }
        }
        panel.add(splitPane, BorderLayout.CENTER)

        // Bottom: options
        val bottomPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.emptyTop(8)

            add(rememberCheckbox, BorderLayout.WEST)

            // View raw JSON button
            val viewRawButton = JButton("View Raw JSON").apply {
                addActionListener {
                    RawResponseDialog(project, rawJson, providerId, modelId).show()
                }
            }
            add(viewRawButton, BorderLayout.EAST)
        }
        panel.add(bottomPanel, BorderLayout.SOUTH)

        return panel
    }

    private fun updatePreview() {
        val selected = candidateList.selectedValue
        selectedCandidate = selected

        if (selected != null) {
            previewArea.text = selected.detailedDescription()
            previewArea.caretPosition = 0
        } else {
            previewArea.text = "(Select a candidate to see preview)"
        }
    }

    override fun doOKAction() {
        if (selectedCandidate == null) {
            Messages.showWarningDialog(
                project,
                "Please select a response candidate.",
                "No Selection"
            )
            return
        }
        super.doOKAction()
    }

    /**
     * Get the user's selection after dialog closes.
     */
    fun getResult(): CorrectionResult? {
        if (exitCode != OK_EXIT_CODE) return null

        val candidate = selectedCandidate ?: return null

        return CorrectionResult(
            selectedCandidate = candidate,
            shouldRemember = rememberCheckbox.isSelected,
            hint = if (rememberCheckbox.isSelected) {
                FormatHint.fromUserSelection(providerId, modelId, candidate)
            } else null
        )
    }

    /**
     * Custom cell renderer for candidate list.
     */
    private class CandidateListCellRenderer : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>?,
            value: Any?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): java.awt.Component {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)

            if (value is TextCandidate) {
                // Show rank, path, and preview
                val rank = index + 1
                val pathDisplay = value.path.ifEmpty { "(root)" }
                text = "<html><b>#$rank</b> $pathDisplay<br><font color='gray'>${escapeHtml(value.preview)}</font></html>"

                // Visual indicator for score
                toolTipText = "Score: ${value.score}"

                border = JBUI.Borders.empty(4, 8)
            }

            return this
        }

        private fun escapeHtml(text: String): String {
            return text
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
        }
    }

    companion object {
        /**
         * Show correction dialog and get user's selection.
         *
         * @return CorrectionResult if user selected and confirmed, null if cancelled
         */
        fun show(
            project: Project?,
            candidates: List<TextCandidate>,
            rawJson: String,
            providerId: String,
            modelId: String?,
            isCorrection: Boolean
        ): CorrectionResult? {
            val dialog = CorrectionDialog(
                project = project,
                candidates = candidates,
                rawJson = rawJson,
                providerId = providerId,
                modelId = modelId,
                isCorrection = isCorrection
            )
            dialog.show()
            return dialog.getResult()
        }
    }
}

/**
 * Result of user correction/selection.
 */
data class CorrectionResult(
    val selectedCandidate: TextCandidate,
    val shouldRemember: Boolean,
    val hint: FormatHint?
)