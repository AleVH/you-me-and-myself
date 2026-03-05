package com.youmeandmyself.storage

import com.intellij.util.messages.Topic

/**
 * Notification topic: storage subsystem is fully initialized and ready for queries.
 *
 * ## Why This Exists
 *
 * Race condition fix: LibraryPanel's JCEF loads and fires JS bridge queries
 * before [LocalStorageFacade.initialize] completes (especially after dev-mode
 * schema wipes where JSONL rebuild takes time). All queries hit the
 * "Storage not initialized yet" guard, return empty, and the Library never
 * retries.
 *
 * ## How It Works
 *
 * 1. [StorageInitializer] publishes to this topic after init + validation complete
 * 2. [LibraryPanel] subscribes on construction and calls refresh() when notified
 * 3. If LibraryPanel loads AFTER init already completed, it checks
 *    [LocalStorageFacade.isInitialized] and loads immediately — the subscription
 *    is the fallback for the race case
 *
 * ## Registration
 *
 * No plugin.xml registration needed — Topic.create() with PROJECT level
 * scopes the topic to the project message bus automatically.
 *
 * ## Thread Safety
 *
 * The notification fires on whatever thread StorageInitializer.execute() runs
 * on (a background coroutine thread). Subscribers that touch UI (like
 * LibraryPanel calling JCEF executeJavaScript) should handle thread switching
 * themselves if needed — though JCEF's executeJavaScript is thread-safe.
 */
fun interface StorageReadyListener {

    /**
     * Called once after the storage subsystem is fully initialized.
     *
     * At this point:
     * - SQLite database is open with all tables created
     * - JSONL rebuild has completed (if needed)
     * - Project is registered
     * - Search index is rebuilt
     * - Storage healing has run
     * - [LocalStorageFacade.isInitialized] is true
     */
    fun onStorageReady()

    companion object {
        /**
         * Project-level topic for storage readiness notifications.
         *
         * Usage (publish):
         * ```kotlin
         * project.messageBus.syncPublisher(StorageReadyListener.TOPIC).onStorageReady()
         * ```
         *
         * Usage (subscribe):
         * ```kotlin
         * project.messageBus.connect(disposable).subscribe(StorageReadyListener.TOPIC, StorageReadyListener {
         *     // storage is ready, refresh UI
         * })
         * ```
         */
        @JvmField
        val TOPIC: Topic<StorageReadyListener> = Topic.create(
            "com.youmeandmyself.storage.ready",
            StorageReadyListener::class.java
        )
    }
}