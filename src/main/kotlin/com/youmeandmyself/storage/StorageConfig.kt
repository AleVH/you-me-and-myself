package com.youmeandmyself.storage

import com.youmeandmyself.storage.model.ExchangePurpose
import java.io.File
import java.time.LocalDate
import java.time.temporal.IsoFields

/**
 * Encapsulates all storage path resolution for the plugin.
 *
 * ## Why This Exists
 *
 * The old architecture scattered path logic across LocalStorageFacade — project dir
 * for raw data, IntelliJ system area for metadata. The new architecture centralizes
 * everything under a single configurable root directory:
 *
 * ```
 * ~/YouMeAndMyself/           (or user-configured path)
 * ├── youmeandmyself.db       (SQLite database)
 * ├── chat/
 * │   └── {project-id}/
 * │       └── exchanges-YYYY-Www.jsonl
 * └── summaries/
 *     └── {project-id}/
 *         └── summaries-YYYY-Www.jsonl
 * ```
 *
 * ## Routing by ExchangePurpose
 *
 * The [ExchangePurpose] field determines which folder a JSONL file belongs in:
 * - CHAT → chat/{projectId}/
 * - FILE_SUMMARY, MODULE_SUMMARY (and future *_SUMMARY types) → summaries/{projectId}/
 *
 * This routing is enforced at both write time ([currentFileForPurpose]) and read time
 * ([chatFile]). A self-healing fallback checks the alternate folder if the expected
 * location is empty — this recovers files misrouted by the pre-Phase 4B bug where
 * all exchanges went to chat/ regardless of purpose.
 *
 * ## Weekly Partitioning
 *
 * JSONL files are partitioned by ISO week (YYYY-Www) rather than month because:
 * - Heavy plugin usage is expected — weekly keeps individual files small
 * - ISO week numbering is unambiguous and standard
 * - Each file stays manageable for inspection, backup, and cleanup
 *
 * ## User-Configurable Root
 *
 * The storage root defaults to ~/YouMeAndMyself/ but can be changed by the user
 * via plugin settings. This is stored in the storage_config SQLite table and
 * resolved at startup. The root must be set before any other storage operations.
 *
 * @param rootPath The absolute path to the storage root directory
 */
class StorageConfig(private val rootPath: File) {

    companion object {
        /** Default storage root when no custom path is configured. */
        val DEFAULT_ROOT = File(System.getProperty("user.home"), "YouMeAndMyself")

        /**
         * Create a StorageConfig with the default root path.
         *
         * Suitable for first-run or when no custom path has been configured.
         */
        fun withDefaultRoot(): StorageConfig = StorageConfig(DEFAULT_ROOT)
    }

    /** The storage root directory. Everything lives under here. */
    val root: File get() = rootPath

    /** The SQLite database file. */
    val databaseFile: File get() = File(rootPath, "youmeandmyself.db")

    /** The top-level chat directory containing per-project subfolders. */
    val chatDir: File get() = File(rootPath, "chat")

    /** The top-level summaries directory containing per-project subfolders. */
    val summariesDir: File get() = File(rootPath, "summaries")

    // ==================== Purpose-Aware File Resolution ====================

    /**
     * Get the current weekly JSONL file for a given exchange purpose.
     *
     * This is the WRITE path — used when saving new exchanges.
     * Routes summaries to summaries/{projectId}/ and chats to chat/{projectId}/.
     *
     * @param projectId The project's unique identifier
     * @param purpose The exchange purpose (determines which folder to write to)
     * @return File path for the current week's JSONL file in the correct folder
     */
    fun currentFileForPurpose(projectId: String, purpose: ExchangePurpose): File {
        return if (purpose.isSummaryType) {
            currentSummaryFile(projectId)
        } else {
            currentChatFile(projectId)
        }
    }

    /**
     * Resolve a JSONL file by its stored filename and exchange purpose.
     *
     * This is the READ path — used when loading exchanges from disk.
     * Routes to the correct folder based on purpose, with a self-healing fallback
     * that checks the alternate folder for files misrouted by the pre-Phase 4B bug.
     *
     * ## Self-Healing Behavior
     *
     * Before the Phase 4B fix, ALL exchanges (including FILE_SUMMARY) were written
     * to chat/. This method checks the correct location first, then falls back to
     * the alternate folder. If found in the wrong location, the caller can migrate
     * the data to the correct folder.
     *
     * @param projectId The project's unique identifier
     * @param filename The JSONL filename (e.g., "exchanges-2026-W05.jsonl")
     * @param purpose The exchange purpose (determines expected folder)
     * @return The resolved file — checks correct location first, then fallback.
     *         Returns the expected path even if neither location has the file
     *         (caller handles the "file not found" case).
     */
    fun chatFile(projectId: String, filename: String, purpose: ExchangePurpose): File {
        // Primary: where the file SHOULD be based on purpose
        val expected = if (purpose.isSummaryType) {
            File(summariesDirForProject(projectId), filename)
        } else {
            File(chatDirForProject(projectId), filename)
        }
        if (expected.exists()) return expected

        // Fallback: check the other folder (self-healing for misrouted files)
        val fallback = if (purpose.isSummaryType) {
            File(chatDirForProject(projectId), filename)
        } else {
            File(summariesDirForProject(projectId), filename)
        }
        if (fallback.exists()) return fallback

        // Neither exists — return expected path (caller handles missing file)
        return expected
    }

    // ==================== Chat Exchange Paths ====================

    /**
     * Get the chat exchanges directory for a specific project.
     *
     * @param projectId The project's unique identifier
     * @return Directory path: {root}/chat/{projectId}/
     */
    fun chatDirForProject(projectId: String): File = File(chatDir, projectId)

    /**
     * Get the JSONL file for chat exchanges in the current ISO week.
     *
     * Uses ISO week numbering (Monday-start weeks). The filename format is
     * `exchanges-YYYY-Www.jsonl` where Www is the zero-padded week number.
     *
     * Example: 2026-01-31 (a Saturday) falls in ISO week 5 → exchanges-2026-W05.jsonl
     *
     * @param projectId The project's unique identifier
     * @return File path: {root}/chat/{projectId}/exchanges-2026-W05.jsonl
     */
    fun currentChatFile(projectId: String): File {
        val filename = weeklyFileName("exchanges")
        return File(chatDirForProject(projectId), filename)
    }

    // ==================== Summary Paths ====================

    /**
     * Get the summaries directory for a specific project.
     *
     * @param projectId The project's unique identifier
     * @return Directory path: {root}/summaries/{projectId}/
     */
    fun summariesDirForProject(projectId: String): File = File(summariesDir, projectId)

    /**
     * Get the JSONL file for summaries in the current ISO week.
     *
     * @param projectId The project's unique identifier
     * @return File path: {root}/summaries/{projectId}/summaries-2026-W05.jsonl
     */
    fun currentSummaryFile(projectId: String): File {
        val filename = weeklyFileName("summaries")
        return File(summariesDirForProject(projectId), filename)
    }

    /**
     * Resolve a specific summary file by its stored filename.
     *
     * @param projectId The project's unique identifier
     * @param filename The JSONL filename (e.g., "summaries-2026-W05.jsonl")
     * @return The resolved file path
     */
    fun summaryFile(projectId: String, filename: String): File =
        File(summariesDirForProject(projectId), filename)

    // ==================== Directory Initialization ====================

    /**
     * Ensure all required top-level directories exist.
     *
     * Call this once during initialization. Creates:
     * - The storage root
     * - The chat/ subdirectory
     * - The summaries/ subdirectory
     *
     * Per-project subdirectories are created lazily on first write.
     */
    fun ensureDirectoriesExist() {
        rootPath.mkdirs()
        chatDir.mkdirs()
        summariesDir.mkdirs()
    }

    /**
     * Ensure the project-specific directories exist for both chat and summaries.
     *
     * Called before the first write to a project's data.
     *
     * @param projectId The project's unique identifier
     */
    fun ensureProjectDirectoriesExist(projectId: String) {
        chatDirForProject(projectId).mkdirs()
        summariesDirForProject(projectId).mkdirs()
    }

    // ==================== Internal ====================

    /**
     * Generate a weekly-partitioned filename using ISO week numbering.
     *
     * Format: {prefix}-YYYY-Www.jsonl
     *
     * Uses [IsoFields.WEEK_OF_WEEK_BASED_YEAR] for correct ISO week numbers.
     * Note: ISO week years can differ from calendar years at year boundaries
     * (e.g., Dec 31 might be in week 1 of the next year).
     *
     * @param prefix The file prefix ("exchanges" or "summaries")
     * @return Filename like "exchanges-2026-W05.jsonl"
     */
    private fun weeklyFileName(prefix: String): String {
        val today = LocalDate.now()
        val isoYear = today.get(IsoFields.WEEK_BASED_YEAR)
        val isoWeek = today.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)
        return "$prefix-$isoYear-W${isoWeek.toString().padStart(2, '0')}.jsonl"
    }
}