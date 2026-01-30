package com.youmeandmyself.ai.providers.parsing.ui

import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import kotlinx.serialization.json.Json
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import java.awt.datatransfer.StringSelection
import javax.swing.*

/**
 * Dialog to display raw JSON response from an AI provider.
 *
 * Useful for:
 * - Debugging when parsing goes wrong
 * - Understanding what the API actually returned
 * - Copying raw response for bug reports
 * - Power users who want to see everything
 *
 * Features:
 * - Pretty-printed JSON (if valid JSON)
 * - Copy to clipboard button
 * - Shows provider/model info
 */
class RawResponseDialog(
    private val project: Project?,
    private val rawJson: String,
    private val providerId: String?,
    private val modelId: String?
) : DialogWrapper(project) {

    private val textArea: JBTextArea

    init {
        title = "Raw Response"

        textArea = JBTextArea().apply {
            isEditable = false
            lineWrap = false // JSON looks better without wrapping
            font = Font(Font.MONOSPACED, Font.PLAIN, 12)
            border = JBUI.Borders.empty(8)
            text = formatJson(rawJson)
        }

        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(0, 8)).apply {
            border = JBUI.Borders.empty(12)
            preferredSize = Dimension(700, 500)
        }

        // Header with provider info
        if (providerId != null || modelId != null) {
            val headerText = buildString {
                append("Provider: ${providerId ?: "unknown"}")
                if (modelId != null) {
                    append(" | Model: $modelId")
                }
            }
            val headerLabel = JBLabel(headerText).apply {
                border = JBUI.Borders.emptyBottom(8)
            }
            panel.add(headerLabel, BorderLayout.NORTH)
        }

        // Main content: scrollable text area
        val scrollPane = JBScrollPane(textArea).apply {
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_ALWAYS
        }
        panel.add(scrollPane, BorderLayout.CENTER)

        return panel
    }

    override fun createSouthPanel(): JComponent {
        val panel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(8, 12, 12, 12)
        }

        // Left side: Copy button
        val copyButton = JButton("Copy to Clipboard").apply {
            addActionListener {
                copyToClipboard()
            }
        }
        panel.add(copyButton, BorderLayout.WEST)

        // Right side: Close button
        val closeButton = JButton("Close").apply {
            addActionListener {
                close(OK_EXIT_CODE)
            }
        }
        panel.add(closeButton, BorderLayout.EAST)

        return panel
    }

    private fun copyToClipboard() {
        CopyPasteManager.getInstance().setContents(StringSelection(rawJson))
    }

    /**
     * Pretty-print JSON if valid, otherwise return as-is.
     */
    private fun formatJson(json: String): String {
        return try {
            val element = Json.parseToJsonElement(json)
            Json { prettyPrint = true }.encodeToString(
                kotlinx.serialization.json.JsonElement.serializer(),
                element
            )
        } catch (e: Exception) {
            // Not valid JSON, return as-is
            json
        }
    }

    companion object {
        /**
         * Show the raw response dialog.
         */
        fun show(
            project: Project?,
            rawJson: String,
            providerId: String? = null,
            modelId: String? = null
        ) {
            RawResponseDialog(project, rawJson, providerId, modelId).show()
        }
    }
}