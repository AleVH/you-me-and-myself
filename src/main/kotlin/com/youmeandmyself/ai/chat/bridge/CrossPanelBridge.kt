package com.youmeandmyself.ai.bridge

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.youmeandmyself.ai.chat.bridge.BridgeDispatcher
import com.youmeandmyself.ai.chat.bridge.BridgeMessage
import com.youmeandmyself.dev.Dev

/**
 * TEMPORARY cross-panel bridge for Library → Chat communication.
 *
 * ┌──────────────────┐     ┌──────────────────────┐     ┌──────────────────┐
 * │  Library Panel    │     │  CrossPanelBridge     │     │  Chat Panel      │
 * │  (vanilla HTML)   │────▶│  (this service)       │────▶│  (React/JCEF)    │
 * │                   │     │                       │     │                  │
 * │  JS: continueChat │     │  openConversation()   │     │  BridgeDispatcher│
 * │  ↓                │     │  ↓                    │     │  ↓               │
 * │  JBCefJSQuery     │     │  dispatcher.dispatch()│     │  OPEN_CONV event │
 * └──────────────────┘     └──────────────────────┘     └──────────────────┘
 *
 * ## Why This Exists
 *
 * The Library panel is vanilla HTML/JS with its own JBCefJSQuery bridge.
 * The Chat panel is React with BridgeDispatcher. They are separate JCEF
 * browser instances with no direct communication path.
 *
 * This service sits in the middle: the Library calls openConversation()
 * through its Kotlin bridge handler, and this service routes the command
 * to the Chat's BridgeDispatcher, which sends an event to the React frontend.
 *
 * ## When To Remove
 *
 * DELETE THIS ENTIRE FILE when the Library panel migrates to React.
 * At that point, both panels share the same BridgeDispatcher and can
 * communicate directly through commands and events. No cross-panel
 * bridge needed.
 *
 * ## Cleanup Checklist
 *
 * When removing this file, also remove:
 * 1. This file (CrossPanelBridge.kt)
 * 2. Library's "continueChat" command handler in LibraryPanel.kt
 * 3. The registerChatDispatcher() call in ChatPanel.kt
 * 4. BridgeMessage.OpenConversation command (if no longer used)
 * 5. BridgeMessage.OpenConversationResultEvent (if no longer used)
 * 6. The corresponding TypeScript types in types.ts
 * 7. The OPEN_CONVERSATION_RESULT handler in useBridge.ts
 */
@Service(Service.Level.PROJECT)
class CrossPanelBridge(private val project: Project) {

    private val log = Dev.logger(CrossPanelBridge::class.java)

    /**
     * Reference to the Chat panel's BridgeDispatcher.
     * Set by ChatPanel on construction, cleared on dispose.
     * Nullable because the Chat panel may not be visible/initialized yet.
     */
    @Volatile
    private var chatDispatcher: BridgeDispatcher? = null

    /**
     * Register the Chat panel's dispatcher so the Library can route commands to it.
     *
     * Called from ChatPanel.init() after the BridgeDispatcher is created.
     * TEMPORARY: Remove when Library migrates to React.
     */
    fun registerChatDispatcher(dispatcher: BridgeDispatcher) {
        chatDispatcher = dispatcher
        Dev.info(log, "cross_panel.chat_dispatcher_registered")
    }

    /**
     * Unregister the Chat panel's dispatcher.
     *
     * Called from ChatPanel.dispose() to prevent stale references.
     * TEMPORARY: Remove when Library migrates to React.
     */
    fun unregisterChatDispatcher() {
        chatDispatcher = null
        Dev.info(log, "cross_panel.chat_dispatcher_unregistered")
    }

    /**
     * Open a conversation in the Chat tab.
     *
     * Called from the Library panel when the user clicks "Continue chat".
     * Routes the command through the Chat's BridgeDispatcher, which handles
     * tab creation/switching and history loading.
     *
     * TEMPORARY: Remove when Library migrates to React.
     *
     * @param conversationId The conversation to open
     * @return true if the command was dispatched, false if Chat panel is not available
     */
    fun openConversation(conversationId: String): Boolean {
        val dispatcher = chatDispatcher
        if (dispatcher == null) {
            Dev.warn(log, "cross_panel.no_chat_dispatcher", null,
                "conversationId" to conversationId
            )
            return false
        }

        Dev.info(log, "cross_panel.open_conversation",
            "conversationId" to conversationId
        )

        dispatcher.dispatch(
            BridgeMessage.OpenConversation(conversationId = conversationId)
        )

        // Switch the IDE tool window to the Chat tab so the user sees the result
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
            val toolWindow = com.intellij.openapi.wm.ToolWindowManager.getInstance(project)
                .getToolWindow("YMM Chat")
            toolWindow?.contentManager?.let { cm ->
                val chatContent = cm.contents.firstOrNull { it.displayName == "Chat" }
                if (chatContent != null) {
                    cm.setSelectedContent(chatContent)
                    Dev.info(log, "cross_panel.switched_to_chat_tab")
                }
            }
        }

        return true
    }

    /**
     * Push a fresh CONTEXT_SETTINGS event to the React frontend.
     *
     * Called from ContextConfigurable when the user changes context settings
     * so the React panel reflects the new state immediately — the dial greys
     * out or lights up without requiring the user to restart the IDE.
     *
     * Dispatches a RequestContextSettings command to the existing handler which
     * reads the current ContextSettingsState and emits the event.
     */
    fun notifyContextSettingsChanged() {
        val dispatcher = chatDispatcher
        if (dispatcher == null) {
            Dev.warn(log, "cross_panel.no_chat_dispatcher", null,
                "action" to "notifyContextSettings"
            )
            return
        }
        dispatcher.dispatch(BridgeMessage.RequestContextSettings())
    }

    companion object {
        fun getInstance(project: Project): CrossPanelBridge = project.service()
    }
}