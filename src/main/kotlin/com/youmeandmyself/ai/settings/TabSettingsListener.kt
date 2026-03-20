package com.youmeandmyself.ai.settings

import com.intellij.util.messages.Topic

/**
 * Notification topic: tab-related settings have been changed by the user.
 *
 * ## Why This Exists
 *
 * When the user changes maxTabs or keepTabs in General Settings and clicks
 * Apply, the new values are persisted to [TabSettingsState] XML. However,
 * the frontend only reads these values once at startup via the TAB_STATE
 * bridge event. Without this topic, the frontend would show stale values
 * until the next IDE restart.
 *
 * ## How It Works
 *
 * 1. [GeneralConfigurable.apply] publishes to this topic after persisting
 * 2. [BridgeDispatcher] subscribes and re-emits a TAB_STATE event so the
 *    frontend picks up the new maxTabs/keepTabs immediately
 *
 * ## Registration
 *
 * No plugin.xml registration needed — Topic.create() with PROJECT level
 * scopes the topic to the project message bus automatically.
 */
fun interface TabSettingsListener {

    /**
     * Called when tab-related settings have been applied by the user.
     * Subscribers should refresh any cached tab settings values.
     */
    fun onTabSettingsChanged()

    companion object {
        /**
         * Project-level topic for tab settings change notifications.
         *
         * Usage (publish):
         * ```kotlin
         * project.messageBus.syncPublisher(TabSettingsListener.TOPIC).onTabSettingsChanged()
         * ```
         *
         * Usage (subscribe):
         * ```kotlin
         * project.messageBus.connect(disposable).subscribe(TabSettingsListener.TOPIC, TabSettingsListener {
         *     // settings changed, refresh
         * })
         * ```
         */
        @JvmField
        val TOPIC: Topic<TabSettingsListener> = Topic.create(
            "com.youmeandmyself.settings.tab",
            TabSettingsListener::class.java
        )
    }
}
