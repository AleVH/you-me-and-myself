package com.youmeandmyself.profile

import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.youmeandmyself.dev.Dev
import java.io.File
import java.security.MessageDigest

/**
 * File I/O operations for the assistant profile system.
 *
 * Handles reading the profile YAML from disk, generating the default template
 * when no profile exists, computing content hashes for change detection, and
 * creating VFS watchers for file modification events.
 *
 * ## Storage Location
 *
 * Default: `{storage-root}/profile/profile.yaml`
 * Configurable via `assistant_profile_file_path` in `storage_config` table.
 *
 * ## Content Hashing
 *
 * SHA-256 hash of the raw file content. Compared against `source_hash` in the
 * `assistant_profile_summary` SQLite table to determine if re-summarization is needed.
 * This avoids unnecessary AI calls when the file hasn't changed.
 */
object AssistantProfileFileManager {

    private val log = Dev.logger(AssistantProfileFileManager::class.java)

    /**
     * Default profile directory name within the storage root.
     */
    const val PROFILE_DIR = "profile"

    /**
     * Default profile file name.
     */
    const val PROFILE_FILENAME = "profile.yaml"

    /**
     * Default template generated when no profile file exists.
     *
     * Includes guidance comments so the user knows how to author their profile.
     * The `sections: []` means the profile is valid but empty — the plugin won't
     * attach any directives until the user adds content.
     */
    private val DEFAULT_TEMPLATE = """
        |# AI Assistant Profile
        |# ====================
        |# Each section contains direct instructions sent to the AI.
        |# Be specific. "Use 4-space indentation" works.
        |# "Write good code" doesn't.
        |# Vague or contradictory rules degrade AI response quality.
        |#
        |# Example section:
        |#   - tag: "communication"
        |#     label: "Communication Style"
        |#     content: |
        |#       Be direct and concise. Don't sugarcoat things.
        |#       If I'm wrong, tell me I'm wrong.
        |#
        |version: 1
        |sections: []
    """.trimMargin()

    /**
     * Read the profile YAML from disk.
     *
     * @param profilePath Absolute path to the profile.yaml file
     * @return The raw YAML content, or null if the file doesn't exist or can't be read
     */
    fun readProfile(profilePath: String): String? {
        val file = File(profilePath)
        if (!file.exists()) {
            Dev.info(log, "assistant_profile.file.not_found", "path" to profilePath)
            return null
        }

        return try {
            val content = file.readText(Charsets.UTF_8)
            Dev.info(log, "assistant_profile.file.read",
                "path" to profilePath,
                "length" to content.length
            )
            content
        } catch (e: Exception) {
            Dev.error(log, "assistant_profile.file.read_failed", e, "path" to profilePath)
            null
        }
    }

    /**
     * Generate the default template at the given path.
     *
     * Creates parent directories if needed. Only writes if the file does not
     * already exist — never overwrites an existing profile.
     *
     * @param profilePath Absolute path where the template should be created
     * @return True if the template was created, false if it already exists or creation failed
     */
    fun generateDefaultTemplate(profilePath: String): Boolean {
        val file = File(profilePath)
        if (file.exists()) {
            Dev.info(log, "assistant_profile.file.template_exists", "path" to profilePath)
            return false
        }

        return try {
            file.parentFile?.mkdirs()
            file.writeText(DEFAULT_TEMPLATE, Charsets.UTF_8)
            Dev.info(log, "assistant_profile.file.template_created", "path" to profilePath)
            true
        } catch (e: Exception) {
            Dev.error(log, "assistant_profile.file.template_failed", e, "path" to profilePath)
            false
        }
    }

    /**
     * Compute SHA-256 hash of the given content.
     *
     * Used to compare against `source_hash` in the SQLite table to determine
     * if the profile has changed since last summarization.
     *
     * @param content The raw file content to hash
     * @return Hex-encoded SHA-256 hash
     */
    fun contentHash(content: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(content.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Build the default profile path from the storage root.
     *
     * @param storageRoot The plugin's storage root directory
     * @return Absolute path to the default profile.yaml location
     */
    fun defaultProfilePath(storageRoot: File): String {
        return File(storageRoot, "$PROFILE_DIR/$PROFILE_FILENAME").absolutePath
    }

    /**
     * Create a VFS listener that fires [onChange] when the profile file is modified.
     *
     * The listener watches for content changes on the specific file path.
     * Used by AssistantProfileService to trigger re-validation and re-summarization.
     *
     * ## VFS vs File Watcher
     *
     * IntelliJ's VFS layer provides file change events integrated with the IDE's
     * refresh cycle. This is more reliable than java.nio.file.WatchService because
     * it handles both external edits and edits through the IDE editor.
     *
     * @param profilePath Absolute path to watch
     * @param onChange Callback invoked when the file content changes
     * @return The listener instance (caller must manage its lifecycle via message bus subscription)
     */
    fun createFileWatcher(
        profilePath: String,
        onChange: () -> Unit
    ): BulkFileListener {
        return object : BulkFileListener {
            override fun after(events: MutableList<out VFileEvent>) {
                val relevant = events.any { event ->
                    event is VFileContentChangeEvent &&
                            event.file.path == profilePath
                }
                if (relevant) {
                    Dev.info(log, "assistant_profile.file.changed", "path" to profilePath)
                    onChange()
                }
            }
        }
    }
}