package com.youmeandmyself.storage

import com.intellij.openapi.diagnostic.Logger
import com.youmeandmyself.dev.Dev
import java.io.File

/**
 * Self-healing service that migrates misrouted JSONL files to their correct folders.
 *
 * ## Why This Exists
 *
 * Before Phase 4B, a routing bug caused ALL exchanges (including FILE_SUMMARY and
 * MODULE_SUMMARY) to be written to the chat/ folder. The fix in StorageConfig now
 * routes by ExchangePurpose, but existing files are still in the wrong place.
 *
 * This service runs once on startup, after the JSONL rebuild and search index are
 * ready. It finds summary exchanges whose JSONL files are in chat/ instead of
 * summaries/ and moves them to the correct location.
 *
 * ## Safety
 *
 * - Non-fatal: if any migration fails, the StorageConfig.chatFile() fallback
 *   still finds files in the wrong folder. Data is never lost.
 * - Copy-then-delete: the file is copied to the correct location first,
 *   then the original is deleted. If the copy fails, the original stays.
 * - Idempotent: safe to run multiple times. If the file is already in the
 *   correct location, it's skipped.
 * - Logged: every migration is logged so the user (and dev) can see what happened.
 *
 * @param database The database helper for querying exchange metadata
 */
class StorageHealingService(private val database: DatabaseHelper) {

    private val log = Logger.getInstance(StorageHealingService::class.java)

    /**
     * Migrate misrouted JSONL files for a project.
     *
     * Scans SQLite for non-CHAT exchanges whose raw_file might still be in the
     * chat/ folder. If the file exists in chat/ but not in summaries/, it gets
     * moved.
     *
     * @param projectId The project's unique identifier
     * @param config Storage path configuration (provides folder resolution)
     * @return Number of files migrated
     */
    fun healMisroutedFiles(projectId: String, config: StorageConfig): Int {
        return try {
            // Find JSONL files referenced by summary exchanges.
            // These SHOULD be in summaries/ — if they're in chat/, they need moving.
            val summaryFiles = database.query(
                """
                SELECT DISTINCT raw_file FROM chat_exchanges
                WHERE project_id = ? AND purpose != 'CHAT' AND raw_available = 1
                """.trimIndent(),
                projectId
            ) { rs -> rs.getString("raw_file") }

            if (summaryFiles.isEmpty()) return 0

            var migrated = 0
            for (filename in summaryFiles) {
                if (migrateFile(projectId, filename, config)) {
                    migrated++
                }
            }

            if (migrated > 0) {
                Dev.info(log, "self_heal.complete",
                    "projectId" to projectId,
                    "migrated" to migrated,
                    "checked" to summaryFiles.size
                )
            }

            migrated
        } catch (e: Exception) {
            // Non-fatal — StorageConfig.chatFile() fallback still finds misplaced files
            Dev.warn(log, "self_heal.failed", e, "projectId" to projectId)
            0
        }
    }

    /**
     * Migrate a single JSONL file from chat/ to summaries/ if misplaced.
     *
     * Conditions for migration:
     * - File exists in chat/{projectId}/
     * - File does NOT exist in summaries/{projectId}/
     *
     * If both exist (shouldn't happen, but defensive), skip — don't delete anything.
     *
     * @return true if the file was migrated, false if no action was needed
     */
    private fun migrateFile(projectId: String, filename: String, config: StorageConfig): Boolean {
        val wrongLocation = File(config.chatDirForProject(projectId), filename)
        val correctLocation = File(config.summariesDirForProject(projectId), filename)

        // Already in the right place, or not in the wrong place — nothing to do
        if (!wrongLocation.exists()) return false
        if (correctLocation.exists()) return false

        return try {
            // Ensure target directory exists
            config.summariesDirForProject(projectId).mkdirs()

            // Copy first, then delete — if copy fails, original stays safe
            wrongLocation.copyTo(correctLocation)
            wrongLocation.delete()

            Dev.info(log, "self_heal.migrated",
                "file" to filename,
                "from" to "chat/${projectId}/",
                "to" to "summaries/${projectId}/"
            )
            true
        } catch (e: Exception) {
            // Non-fatal — fallback in StorageConfig.chatFile() still finds it
            Dev.warn(log, "self_heal.migrate_failed", e,
                "file" to filename,
                "projectId" to projectId
            )
            false
        }
    }
}