package com.youmeandmyself.ai.library

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key

/**
 * Holder for cross-component access to the LibraryPanel.
 *
 * File: src/main/kotlin/com/youmeandmyself/ai/library/LibraryPanelHolder.kt
 *
 * Usage from ChatPanel after saving an exchange:
 *   LibraryPanelHolder.get(project)?.refresh()
 */
object LibraryPanelHolder {
    val KEY = Key.create<LibraryPanel>("youmeandmyself.libraryPanel")

    fun get(project: Project): LibraryPanel? = project.getUserData(KEY)

    fun set(project: Project, panel: LibraryPanel) {
        project.putUserData(KEY, panel)
    }
}

// ============================================================================
// INTEGRATION INTO EXISTING ChatToolWindowFactory
// ============================================================================
//
// Your existing factory: com.youmeandmyself.ai.chat.ChatToolWindowFactory
// Tool window ID: "YMM Chat" (registered in plugin.xml)
//
// Modify ChatToolWindowFactory.createToolWindowContent() to add the Library tab:
//
//   override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
//       val contentFactory = ContentFactory.getInstance()
//       val disposable = toolWindow.disposable
//
//       // Tab 1: Chat (existing code — keep as-is)
//       val chatPanel = ChatPanel(project, disposable)
//       val chatContent = contentFactory.createContent(chatPanel, "Chat", false)
//       chatContent.isCloseable = false
//       toolWindow.contentManager.addContent(chatContent)
//
//       // Tab 2: Library (add this)
//       val libraryPanel = LibraryPanel(project, disposable)
//       val libraryContent = contentFactory.createContent(libraryPanel, "Library", false)
//       libraryContent.isCloseable = false
//       toolWindow.contentManager.addContent(libraryContent)
//
//       // Store reference for cross-tab refresh
//       LibraryPanelHolder.set(project, libraryPanel)
//   }
//
// ============================================================================
// CROSS-TAB REFRESH — add to ChatPanel at end of save pipeline
// ============================================================================
//
//   // After: tokens.indexed, assistant_text.cached, derived.stored, ide_context.stored
//   LibraryPanelHolder.get(project)?.refresh()
//
// ============================================================================
// RESOURCE: library.html
// ============================================================================
//
// Place at: src/main/resources/chat-window/library.html
// LibraryPanel loads via: javaClass.getResource("/chat-window/library.html")