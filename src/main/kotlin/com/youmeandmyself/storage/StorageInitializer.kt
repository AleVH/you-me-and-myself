package com.youmeandmyself.storage

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.Disposer
import com.youmeandmyself.dev.Dev

/**
 * Initializes the storage subsystem when a project opens.
 *
 * ## What It Does
 *
 * 1. Gets the [LocalStorageFacade] service for the project
 * 2. Calls [LocalStorageFacade.initialize] which:
 *    - Creates the storage root directory structure
 *    - Opens the SQLite database and creates all 11 tables
 *    - Registers the project in the projects table
 *    - Rebuilds the search index from existing JSONL files
 * 3. Validates raw data availability (checks for deleted JSONL files)
 * 4. Registers a dispose handler to close the database on project close
 * 5. Publishes [StorageReadyListener.TOPIC] so UI components (LibraryPanel)
 *    know it's safe to query storage
 *
 * ## Registration
 *
 * This class must be registered in plugin.xml as a postStartupActivity:
 * ```xml
 * <postStartupActivity implementation="com.youmeandmyself.storage.StorageInitializer"/>
 * ```
 *
 * ## Error Handling
 *
 * If initialization fails, the error is logged but the IDE continues normally.
 * The user can still use the plugin — they just won't have storage persistence
 * until the issue is resolved and the IDE is restarted.
 *
 * The [StorageReadyListener.TOPIC] is only published on successful init.
 * If init fails, subscribers are never notified — this is intentional since
 * there's nothing useful they could do with a broken storage layer.
 */
class StorageInitializer : ProjectActivity {

    private val log = Logger.getInstance(StorageInitializer::class.java)

    /**
     * Called by IntelliJ after the project is fully loaded.
     *
     * This runs on a background thread (ProjectActivity is non-blocking),
     * so it's safe to do I/O here.
     */
    override suspend fun execute(project: Project) {
        try {
            Dev.info(log, "storage.init.start", "project" to project.name)

            val facade = LocalStorageFacade.getInstance(project)

            // Initialize storage (creates dirs, opens DB, builds search index)
            facade.initialize()

            // Check for deleted raw files and update metadata
            val projectId = facade.resolveProjectId()
            val unavailable = facade.validateRawDataAvailability(projectId)
            if (unavailable > 0) {
                Dev.info(log, "storage.init.stale_data",
                    "project" to project.name,
                    "unavailable" to unavailable
                )
            }

            // Register dispose handler to close DB when project closes.
            // Disposer.register requires a parent Disposable — the project
            // itself implements Disposable and is disposed when closed.
            @Suppress("DEPRECATION")
            Disposer.register(project as Disposable, Disposable {
                Dev.info(log, "storage.dispose", "project" to project.name)
                facade.dispose()
            })

            Dev.info(log, "storage.init.complete", "project" to project.name)

            // ── Notify subscribers that storage is ready ──
            // This unblocks UI components (LibraryPanel, future panels) that
            // need storage for their initial data load. Published AFTER
            // init.complete so subscribers see fully-initialized state.
            project.messageBus.syncPublisher(StorageReadyListener.TOPIC).onStorageReady()
            Dev.info(log, "storage.init.notified", "project" to project.name)

        } catch (e: Exception) {
            // StorageReadyListener.TOPIC is NOT published on failure — intentional.
            // UI components that depend on storage will remain in their empty/loading
            // state, which is the correct behavior when storage is broken.
            Dev.error(log, "storage.init.failed", e, "project" to project.name)
        }
    }
}