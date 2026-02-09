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
 *    - Opens the SQLite database and creates all 10 tables
 *    - Registers the project in the projects table
 *    - Rebuilds the search index from existing JSONL files
 * 3. Validates raw data availability (checks for deleted JSONL files)
 * 4. Registers a dispose handler to close the database on project close
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
        } catch (e: Exception) {
            Dev.error(log, "storage.init.failed", e, "project" to project.name)
        }
    }
}