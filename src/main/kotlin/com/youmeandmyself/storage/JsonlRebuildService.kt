package com.youmeandmyself.storage

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException
import com.intellij.openapi.diagnostic.Logger
import com.youmeandmyself.ai.providers.parsing.ResponseParser
import com.youmeandmyself.dev.Dev
import com.youmeandmyself.storage.model.DerivedMetadata
import java.io.File
import java.time.Instant
import java.util.UUID

/**
 * Rebuilds the SQLite index from JSONL files on disk.
 *
 * ## Philosophy
 *
 * "JSONL is truth, SQLite is a rebuildable index."
 *
 * This service makes that promise real. When the SQLite database is wiped
 * (dev mode), corrupted, or missing, this service scans all `.jsonl` files
 * under the storage root and reconstructs as much of the index as possible.
 *
 * ## Resilience
 *
 * The rebuild is maximally tolerant of bad, old, or weird data:
 *
 * - **Not JSON at all** → skip the line (only total failure case)
 * - **Valid JSON but missing fields** → extract what exists, defaults for the rest
 * - **Old format** (e.g., `response.content` instead of `rawResponse.json`) → handled
 * - **Personal notes** (just a `text` field) → imported as Notes & Fragments
 * - **Unknown fields** → ignored (forward compatibility)
 *
 * Every field extraction is independent. Failure in one field does not
 * prevent extraction of others. The minimum bar for creating a row is
 * having at least one piece of meaningful content:
 * - A user prompt (`request.input`)
 * - A raw response (`rawResponse.json`)
 * - Pre-parsed response text (`response.content`)
 * - A personal note (`text`)
 *
 * If a line has none of these, it's skipped — an ID and timestamp
 * alone aren't useful to the developer.
 *
 * ## Enrichment Pipeline
 *
 * After inserting each row, the service runs the same enrichment pipeline
 * as live ingest (each step independent, non-fatal):
 *
 * 1. Parse assistant text from raw response (if not already available)
 * 2. Extract token usage from raw response
 * 3. Derive metadata (code blocks, languages, topics, file paths, duplicate hash)
 *
 * ## Usage
 *
 * Called from [LocalStorageFacade.initialize] after schema creation:
 *
 * ```kotlin
 * // After: database.open() and schema creation
 * val rebuild = JsonlRebuildService(database)
 * val stats = rebuild.rebuildFromDirectory(config.root)
 * Dev.info(log, "rebuild.complete",
 *     "imported" to stats.imported,
 *     "skipped" to stats.skipped,
 *     "files" to stats.filesScanned
 * )
 * ```
 */
class JsonlRebuildService(
    private val db: DatabaseHelper
) {
    private val log = Logger.getInstance(JsonlRebuildService::class.java)

    /**
     * Statistics from a rebuild operation.
     */
    data class RebuildStats(
        val filesScanned: Int,
        val linesRead: Int,
        val imported: Int,
        val skippedNoContent: Int,
        val skippedNotJson: Int,
        val skippedDuplicate: Int,
        val enriched: Int,
        val enrichmentErrors: Int
    )

    /**
     * Scan all `.jsonl` files under the storage root and import into SQLite.
     *
     * Scans recursively — picks up files in `chat/{projectId}/`, `summaries/`,
     * and any user-created `.jsonl` files (personal notes) at any depth.
     *
     * @param storageRoot The top-level storage directory (e.g., ~/YouMeAndMyself/)
     * @return Statistics about the rebuild
     */
    fun rebuildFromDirectory(storageRoot: File): RebuildStats {
        val jsonlFiles = storageRoot.walkTopDown()
            .filter { it.isFile && it.extension == "jsonl" }
            .toList()

        Dev.info(log, "rebuild.start",
            "root" to storageRoot.absolutePath,
            "files" to jsonlFiles.size
        )

        var linesRead = 0
        var imported = 0
        var skippedNoContent = 0
        var skippedNotJson = 0
        var skippedDuplicate = 0
        var enriched = 0
        var enrichmentErrors = 0

        // Track imported IDs to avoid duplicates (same exchange in multiple files)
        val importedIds = mutableSetOf<String>()

        // Track registered project IDs to avoid repeated INSERT OR IGNORE calls
        val registeredProjects = mutableSetOf<String>()

        for (file in jsonlFiles) {
            Dev.info(log, "rebuild.file.start",
                "file" to file.name,
                "path" to file.absolutePath,
                "size" to file.length()
            )

            val fileModifiedTime = try {
                Instant.ofEpochMilli(file.lastModified())
            } catch (_: Exception) {
                Instant.now()
            }

            // Infer projectId from directory structure: chat/{projectId}/file.jsonl
            val inferredProjectId = inferProjectId(file, storageRoot)

            file.useLines { lines ->
                for (line in lines) {
                    if (line.isBlank()) continue
                    linesRead++

                    // Step 1: Parse as JSON — the only total failure case
                    val json: JsonObject = try {
                        val element = JsonParser.parseString(line)
                        if (!element.isJsonObject) {
                            skippedNotJson++
                            continue
                        }
                        element.asJsonObject
                    } catch (e: JsonSyntaxException) {
                        skippedNotJson++
                        Dev.warn(log, "rebuild.line.not_json", null,
                            "file" to file.name,
                            "preview" to line.take(80)
                        )
                        continue
                    }

                    // Step 2: Extract content — need at least one meaningful piece
                    val prompt = json.stringAt("request", "input")
                    val rawResponseJson = json.stringAt("rawResponse", "json")
                    val oldResponseContent = json.stringAt("response", "content")
                    val noteText = json.stringAt("text")

                    val hasContent = !prompt.isNullOrBlank() ||
                            !rawResponseJson.isNullOrBlank() ||
                            !oldResponseContent.isNullOrBlank() ||
                            !noteText.isNullOrBlank()

                    if (!hasContent) {
                        skippedNoContent++
                        continue
                    }

                    // Step 3: Extract all other fields best-effort
                    val id = json.stringAt("id") ?: UUID.randomUUID().toString()

                    // Skip duplicates (same exchange appearing in multiple files)
                    if (id in importedIds) {
                        skippedDuplicate++
                        continue
                    }
                    importedIds.add(id)

                    val timestamp = json.stringAt("timestamp")?.let {
                        try { Instant.parse(it) } catch (_: Exception) { null }
                    } ?: fileModifiedTime

                    val projectId = json.stringAt("projectId") ?: inferredProjectId
                    val projectName = json.stringAt("projectName")
                    val providerId = json.stringAt("providerId")
                    val modelId = json.stringAt("modelId")
                    val purposeStr = json.stringAt("purpose") ?: if (noteText != null) "NOTE" else "CHAT"
                    val httpStatus = json.intAt("rawResponse", "httpStatus")

                    // Token usage — try multiple paths
                    val promptTokens = json.intAt("tokenUsage", "promptTokens")
                        ?: json.intAt("response", "tokensUsed")
                    val completionTokens = json.intAt("tokenUsage", "completionTokens")
                    val totalTokens = json.intAt("tokenUsage", "totalTokens")

                    // Step 4: Determine the assistant text from whatever source is available
                    // Priority: old format (already parsed) > note text > parse from raw response
                    var assistantText: String? = oldResponseContent ?: noteText

                    if (assistantText.isNullOrBlank() && !rawResponseJson.isNullOrBlank()) {
                        assistantText = try {
                            val parsed = ResponseParser.parse(rawResponseJson, httpStatus, id)
                            parsed.rawText
                        } catch (_: Exception) {
                            null
                        }
                    }

                    // Step 5: Register the project in the projects table (FK constraint).
                    // Must happen before INSERT into chat_exchanges.
                    // Uses projectName from the JSONL if available (persisted since Phase 4A),
                    // falls back to "Imported ({id})" for old files or imports from other machines.
                    if (projectId != null && projectId !in registeredProjects) {
                        try {
                            val displayName = projectName ?: "Imported ($projectId)"
                            db.execute(
                                """INSERT OR IGNORE INTO projects
                                    (id, name, path, created_at, last_opened_at, is_active)
                                   VALUES (?, ?, ?, ?, ?, 1)""".trimIndent(),
                                projectId,
                                displayName,
                                "unknown",
                                timestamp.toString(),
                                timestamp.toString()
                            )
                            registeredProjects.add(projectId)
                        } catch (e: Exception) {
                            Dev.warn(log, "rebuild.project_register_failed", e,
                                "projectId" to projectId)
                        }
                    }

                    // Step 6: INSERT into SQLite
                    try {
                        db.execute(
                            """
                            INSERT OR IGNORE INTO chat_exchanges
                                (id, project_id, provider_id, model_id, purpose, timestamp,
                                 prompt_tokens, completion_tokens, total_tokens,
                                 assistant_text, raw_file, raw_available)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1)
                            """.trimIndent(),
                            id,
                            projectId,
                            providerId,
                            modelId,
                            purposeStr,
                            timestamp.toString(),
                            promptTokens,
                            completionTokens,
                            totalTokens,
                            assistantText,
                            file.name
                        )
                        imported++
                    } catch (e: Exception) {
                        Dev.warn(log, "rebuild.insert_failed", e, "id" to id)
                        continue
                    }

                    // Step 7: Derive metadata (non-fatal enrichment)
                    if (!assistantText.isNullOrBlank()) {
                        try {
                            val derived = DerivedMetadata.extract(assistantText, prompt)
                            db.execute(
                                """
                                UPDATE chat_exchanges SET
                                    has_code_block = ?, code_languages = ?, has_command = ?,
                                    has_stacktrace = ?, detected_topics = ?, file_paths = ?,
                                    duplicate_hash = ?
                                WHERE id = ?
                                """.trimIndent(),
                                if (derived.hasCodeBlock) 1 else 0,
                                derived.codeLanguages.joinToString(",").ifBlank { null },
                                if (derived.hasCommand) 1 else 0,
                                if (derived.hasStacktrace) 1 else 0,
                                derived.detectedTopics.joinToString(",").ifBlank { null },
                                derived.filePaths.joinToString(",").ifBlank { null },
                                derived.duplicateHash,
                                id
                            )
                            enriched++
                        } catch (e: Exception) {
                            enrichmentErrors++
                            Dev.warn(log, "rebuild.enrich_failed", e, "id" to id)
                        }
                    }

                    // Step 8: Extract token usage from raw response if not in JSONL
                    if (totalTokens == null && !rawResponseJson.isNullOrBlank()) {
                        try {
                            val parsed = ResponseParser.parse(rawResponseJson, httpStatus, id)
                            val usage = parsed.metadata.tokenUsage
                            if (usage != null) {
                                db.execute(
                                    """
                                    UPDATE chat_exchanges SET
                                        prompt_tokens = ?, completion_tokens = ?, total_tokens = ?
                                    WHERE id = ?
                                    """.trimIndent(),
                                    usage.promptTokens,
                                    usage.completionTokens,
                                    usage.totalTokens,
                                    id
                                )
                            }
                        } catch (_: Exception) {
                            // Non-fatal — tokens are nice-to-have
                        }
                    }
                }
            }
        }

        val stats = RebuildStats(
            filesScanned = jsonlFiles.size,
            linesRead = linesRead,
            imported = imported,
            skippedNoContent = skippedNoContent,
            skippedNotJson = skippedNotJson,
            skippedDuplicate = skippedDuplicate,
            enriched = enriched,
            enrichmentErrors = enrichmentErrors
        )

        Dev.info(log, "rebuild.complete",
            "files" to stats.filesScanned,
            "lines" to stats.linesRead,
            "imported" to stats.imported,
            "enriched" to stats.enriched,
            "skippedNoContent" to stats.skippedNoContent,
            "skippedNotJson" to stats.skippedNotJson,
            "skippedDuplicate" to stats.skippedDuplicate,
            "enrichmentErrors" to stats.enrichmentErrors
        )

        return stats
    }

    // ══════════════════════════════════════════════════════════════════════
    // JSON FIELD EXTRACTION HELPERS
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Safely extract a string value from a nested JSON path.
     *
     * Each path segment navigates into a child object. The last segment
     * is the field to read as a string. Returns null if any segment
     * is missing, wrong type, or the value is JSON null.
     *
     * Examples:
     *   json.stringAt("text")                    → top-level "text" field
     *   json.stringAt("request", "input")        → nested request.input
     *   json.stringAt("rawResponse", "json")     → nested rawResponse.json
     */
    private fun JsonObject.stringAt(vararg path: String): String? {
        return try {
            var current: JsonObject = this
            for (i in 0 until path.size - 1) {
                val child = current.get(path[i]) ?: return null
                if (!child.isJsonObject) return null
                current = child.asJsonObject
            }
            val leaf = current.get(path.last()) ?: return null
            if (leaf.isJsonNull) return null
            if (leaf.isJsonPrimitive) leaf.asString else leaf.toString()
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Safely extract an integer value from a nested JSON path.
     * Same navigation logic as [stringAt] but returns Int?.
     */
    private fun JsonObject.intAt(vararg path: String): Int? {
        return try {
            var current: JsonObject = this
            for (i in 0 until path.size - 1) {
                val child = current.get(path[i]) ?: return null
                if (!child.isJsonObject) return null
                current = child.asJsonObject
            }
            val leaf = current.get(path.last()) ?: return null
            if (leaf.isJsonNull) return null
            if (leaf.isJsonPrimitive && leaf.asJsonPrimitive.isNumber) leaf.asInt else null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Infer the project ID from the file's directory structure.
     *
     * Expected path: {storageRoot}/chat/{projectId}/exchanges-*.jsonl
     * If the path matches this pattern, extract the projectId directory name.
     * Otherwise return null (the rebuild will use the current project ID later).
     */
    private fun inferProjectId(file: File, storageRoot: File): String? {
        return try {
            val relative = file.relativeTo(storageRoot).path
            // Expected: chat/{projectId}/filename.jsonl
            val parts = relative.split(File.separator)
            if (parts.size >= 3 && parts[0] == "chat") {
                parts[1]
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }
}