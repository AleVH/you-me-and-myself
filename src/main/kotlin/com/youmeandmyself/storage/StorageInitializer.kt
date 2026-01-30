package com.youmeandmyself.storage

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.youmeandmyself.dev.Dev
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Initializes the storage system when a project opens.
 *
 * This is a [ProjectActivity] — IntelliJ automatically runs it after
 * the project is fully loaded. It's the recommended way to perform
 * startup tasks that need a project context.
 *
 * What happens during initialization:
 * 1. Storage directories are created if they don't exist
 * 2. Metadata index is loaded into memory
 * 3. Search index is rebuilt from available exchanges
 *
 * If initialization fails, errors are logged but the plugin continues
 * to function — the user can still chat, they just won't have persistence.
 *
 * ## Why ProjectActivity instead of ProjectComponent?
 *
 * ProjectComponent is deprecated since 2019.3. ProjectActivity is:
 * - Non-blocking (runs as a coroutine)
 * - Runs after the project is fully initialized
 * - Doesn't slow down IDE startup
 */
class StorageInitializer : ProjectActivity {

    private val log = Logger.getInstance(StorageInitializer::class.java)

    /**
     * Called by IntelliJ when the project finishes loading.
     *
     * @param project The project that just opened
     */
    override suspend fun execute(project: Project) {
        Dev.info(log, "storage.init.starting", "project" to project.name)

        try {
            // Get the facade instance (created by IntelliJ's service system)
            val facade = LocalStorageFacade.getInstance(project)

            // Run initialization on IO dispatcher
            withContext(Dispatchers.IO) {
                facade.initialize()
            }

            // Validate that raw files still exist (detect manual deletions)
            val unavailableCount = facade.validateRawDataAvailability()
            if (unavailableCount > 0) {
                Dev.info(log, "storage.init.stale_data",
                    "project" to project.name,
                    "unavailableCount" to unavailableCount
                )
            }

            Dev.info(log, "storage.init.complete", "project" to project.name)
        } catch (e: Exception) {
            // Don't crash the IDE — log and continue
            Dev.error(log, "storage.init.failed", e, "project" to project.name)
        }
    }
}