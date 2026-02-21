package com.youmeandmyself.storage

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.youmeandmyself.dev.Dev
import java.time.Instant
import java.util.UUID

/**
 * Manages collections, bookmarks, and tags using the dedicated 3-table schema:
 *
 * ```
 * collections (named containers — can exist empty, can be cross-project)
 *   └── bookmarks (links an exchange or summary INTO a collection)
 *         └── bookmark_tags (user-defined labels on individual bookmarks)
 * ```
 *
 * ## Design Decisions (agreed in Phase 4A design)
 *
 * - **Collections are real entities** — stored in their own table, not hacked
 *   into flags/labels on chat_exchanges. A collection can exist with zero items.
 * - **Collections can be global** — project_id is nullable. Null = visible
 *   across all projects. Non-null = scoped to that project.
 * - **One exchange can be in multiple collections** — each link is a separate
 *   bookmark row with its own note and tags.
 * - **Tags live on bookmarks, not on exchanges** — the same exchange can have
 *   different tags in different collections ("jwt" in "Auth Patterns",
 *   "urgent" in "Ask Tech Lead").
 * - **Cached content** — bookmarks store a content preview so the Library
 *   can display bookmarked items without touching JSONL files.
 * - **Source types** — both CHAT and SUMMARY exchanges can be bookmarked.
 *   The source_type column distinguishes them for queries.
 *
 * ## Relationship to Other Services
 *
 * - [LocalStorageFacade] — provides database access via withDatabase/withReadableDatabase
 * - [SearchService] — can filter by "is bookmarked" by joining against these tables
 * - [LibraryPanel] — calls this service via JS↔Kotlin bridge commands
 *
 * ## Why This Replaced the Old BookmarkService
 *
 * The previous version stored bookmarks as a "BOOKMARKED" flag and "col:", "tag:",
 * "note:" prefixed labels in chat_exchanges.flags/labels. That was a shortcut that
 * broke the original architecture: collections couldn't be empty, couldn't be
 * cross-project, and the same exchange couldn't have different notes in different
 * collections. This version uses the 3-table schema that was designed from the
 * start and already exists in DatabaseHelper.createSchema().
 *
 * @param project The IntelliJ project context — used to get LocalStorageFacade
 */
@Service(Service.Level.PROJECT)
class BookmarkService(private val project: Project) {

    private val log = Dev.logger(BookmarkService::class.java)

    private val facade: LocalStorageFacade
        get() = LocalStorageFacade.getInstance(project)

    // ==================== Collections ====================

    /**
     * Data class representing a collection — a named container for bookmarks.
     *
     * @param id Unique collection identifier (UUID)
     * @param projectId Project scope — null means global (visible in all projects)
     * @param name Display name (e.g., "Auth Patterns", "Quick Fixes")
     * @param icon Optional emoji or icon identifier
     * @param createdAt When the collection was created
     * @param sortOrder User-defined ordering for sidebar display
     * @param bookmarkCount Number of bookmarks in this collection (populated by queries)
     */
    data class CollectionInfo(
        val id: String,
        val projectId: String?,
        val name: String,
        val icon: String?,
        val createdAt: Instant,
        val sortOrder: Int,
        val bookmarkCount: Int = 0
    )

    /**
     * Create a new collection.
     *
     * Collections can be global (projectId = null) or scoped to a specific project.
     * Empty collections are valid — they appear in the sidebar immediately.
     *
     * @param name Display name for the collection
     * @param icon Optional emoji/icon (e.g., "📁", "🔒")
     * @param projectId Optional project scope. Null = global, non-null = project-specific.
     *                   Defaults to the current project's ID.
     * @return The created collection, or null if creation failed
     */
    fun createCollection(
        name: String,
        icon: String? = null,
        projectId: String? = facade.resolveProjectId()
    ): CollectionInfo? {
        val id = UUID.randomUUID().toString()
        val now = Instant.now()

        return try {
            facade.withDatabase { db ->
                // Check for existing collection with the same name in the same scope.
                // Two collections called "Auth Patterns" makes no sense — if the name
                // exists, return the existing one so items end up in the same place.
                val existing = if (projectId != null) {
                    db.queryOne(
                        """SELECT id, project_id, name, icon, created_at, sort_order
                           FROM collections
                           WHERE name = ? AND (project_id = ? OR project_id IS NULL)""",
                        name, projectId
                    ) { rs ->
                        CollectionInfo(
                            id = rs.getString("id"),
                            projectId = rs.getString("project_id"),
                            name = rs.getString("name"),
                            icon = rs.getString("icon"),
                            createdAt = Instant.parse(rs.getString("created_at")),
                            sortOrder = rs.getInt("sort_order")
                        )
                    }
                } else {
                    db.queryOne(
                        """SELECT id, project_id, name, icon, created_at, sort_order
                           FROM collections
                           WHERE name = ? AND project_id IS NULL""",
                        name
                    ) { rs ->
                        CollectionInfo(
                            id = rs.getString("id"),
                            projectId = rs.getString("project_id"),
                            name = rs.getString("name"),
                            icon = rs.getString("icon"),
                            createdAt = Instant.parse(rs.getString("created_at")),
                            sortOrder = rs.getInt("sort_order")
                        )
                    }
                }

                if (existing != null) {
                    Dev.info(log, "collection.already_exists",
                        "id" to existing.id, "name" to name)
                    return@withDatabase existing
                }

                // Determine sort order — place new collections at the end
                val maxSort = db.queryOne(
                    "SELECT MAX(sort_order) as max_sort FROM collections"
                ) { rs -> rs.getInt("max_sort") } ?: 0

                db.execute(
                    """INSERT INTO collections (id, project_id, name, icon, created_at, sort_order)
                       VALUES (?, ?, ?, ?, ?, ?)""",
                    id, projectId, name, icon, now.toString(), maxSort + 1
                )

                Dev.info(log, "collection.created",
                    "id" to id, "name" to name, "projectId" to (projectId ?: "global"))

                CollectionInfo(id, projectId, name, icon, now, maxSort + 1, 0)
            }
        } catch (e: Exception) {
            Dev.warn(log, "collection.create_failed", e, "name" to name)
            null
        }
    }

    /**
     * Get all collections visible to the current project.
     *
     * Returns collections that are either:
     * - Global (project_id IS NULL) — visible everywhere
     * - Scoped to the current project (project_id = current project)
     *
     * Each collection includes its bookmark count so the sidebar can display it.
     * Results are ordered by sort_order for consistent sidebar display.
     *
     * @return List of collections with bookmark counts, ordered by sort_order
     */
    fun getCollections(): List<CollectionInfo> {
        val projectId = facade.resolveProjectId()

        return try {
            facade.withReadableDatabase { db ->
                db.query(
                    """SELECT c.id, c.project_id, c.name, c.icon, c.created_at, c.sort_order,
                              COUNT(b.id) as bookmark_count
                       FROM collections c
                       LEFT JOIN bookmarks b ON b.collection_id = c.id
                       WHERE c.project_id IS NULL OR c.project_id = ?
                       GROUP BY c.id
                       ORDER BY c.sort_order""",
                    projectId
                ) { rs ->
                    CollectionInfo(
                        id = rs.getString("id"),
                        projectId = rs.getString("project_id"),
                        name = rs.getString("name"),
                        icon = rs.getString("icon"),
                        createdAt = Instant.parse(rs.getString("created_at")),
                        sortOrder = rs.getInt("sort_order"),
                        bookmarkCount = rs.getInt("bookmark_count")
                    )
                }
            }
        } catch (e: Exception) {
            Dev.warn(log, "collections.get_failed", e)
            emptyList()
        }
    }

    /**
     * Delete a collection and all its bookmarks (CASCADE).
     *
     * The ON DELETE CASCADE in the bookmarks table foreign key means all bookmarks
     * in the collection are automatically removed. Tags on those bookmarks are also
     * cascaded away via bookmark_tags FK.
     *
     * @param collectionId The collection to delete
     * @return True if the collection was deleted, false if it didn't exist or failed
     */
    fun deleteCollection(collectionId: String): Boolean {
        return try {
            facade.withDatabase { db ->
                val rows = db.execute("DELETE FROM collections WHERE id = ?", collectionId)
                Dev.info(log, "collection.deleted", "id" to collectionId, "existed" to (rows > 0))
                rows > 0
            }
        } catch (e: Exception) {
            Dev.warn(log, "collection.delete_failed", e, "id" to collectionId)
            false
        }
    }

    /**
     * Rename a collection.
     *
     * @param collectionId The collection to rename
     * @param newName The new display name
     * @return True if renamed, false if collection not found or failed
     */
    fun renameCollection(collectionId: String, newName: String): Boolean {
        return try {
            facade.withDatabase { db ->
                val rows = db.execute(
                    "UPDATE collections SET name = ? WHERE id = ?",
                    newName, collectionId
                )
                rows > 0
            }
        } catch (e: Exception) {
            Dev.warn(log, "collection.rename_failed", e, "id" to collectionId)
            false
        }
    }

    // ==================== Bookmarks ====================

    /**
     * Data class representing a bookmark — a link between an exchange/summary
     * and a collection, with optional cached content and a personal note.
     *
     * @param id Unique bookmark identifier (UUID)
     * @param collectionId Which collection this bookmark belongs to
     * @param collectionName The collection's display name (populated by joins)
     * @param sourceType "CHAT" or "SUMMARY"
     * @param sourceId The exchange or summary ID being bookmarked
     * @param cachedContent Preview of the bookmarked content for fast display
     * @param note User's personal annotation
     * @param addedAt When the bookmark was created
     * @param sortOrder Position within the collection
     * @param tags List of tags on this specific bookmark
     */
    data class BookmarkInfo(
        val id: String,
        val collectionId: String,
        val collectionName: String?,
        val sourceType: String,
        val sourceId: String,
        val cachedContent: String?,
        val note: String?,
        val addedAt: Instant,
        val sortOrder: Int,
        val tags: List<String> = emptyList()
    )

    /**
     * Add an exchange or summary to a collection.
     *
     * If the same source is already in the same collection, this is a no-op
     * (returns the existing bookmark). The same source CAN be in multiple
     * different collections — each gets its own bookmark with independent
     * note and tags.
     *
     * @param collectionId Target collection
     * @param sourceType "CHAT" or "SUMMARY"
     * @param sourceId The exchange or summary ID
     * @param cachedContent Optional content preview for fast display
     * @param note Optional personal annotation
     * @return The bookmark (new or existing), or null on failure
     */
    fun addBookmark(
        collectionId: String,
        sourceType: String,
        sourceId: String,
        cachedContent: String? = null,
        note: String? = null
    ): BookmarkInfo? {
        return try {
            facade.withDatabase { db ->
                // Check if already bookmarked in this collection
                val existing = db.queryOne(
                    """SELECT id, cached_content, note, added_at, sort_order
                       FROM bookmarks
                       WHERE collection_id = ? AND source_type = ? AND source_id = ?""",
                    collectionId, sourceType, sourceId
                ) { rs ->
                    BookmarkInfo(
                        id = rs.getString("id"),
                        collectionId = collectionId,
                        collectionName = null,
                        sourceType = sourceType,
                        sourceId = sourceId,
                        cachedContent = rs.getString("cached_content"),
                        note = rs.getString("note"),
                        addedAt = Instant.parse(rs.getString("added_at")),
                        sortOrder = rs.getInt("sort_order")
                    )
                }

                if (existing != null) {
                    Dev.info(log, "bookmark.already_exists",
                        "bookmarkId" to existing.id, "sourceId" to sourceId)
                    return@withDatabase existing
                }

                // Create new bookmark
                val id = UUID.randomUUID().toString()
                val now = Instant.now()

                val maxSort = db.queryOne(
                    "SELECT MAX(sort_order) as max_sort FROM bookmarks WHERE collection_id = ?",
                    collectionId
                ) { rs -> rs.getInt("max_sort") } ?: 0

                db.execute(
                    """INSERT INTO bookmarks (id, collection_id, source_type, source_id,
                                              cached_content, note, added_at, sort_order)
                       VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
                    id, collectionId, sourceType, sourceId,
                    cachedContent, note, now.toString(), maxSort + 1
                )

                Dev.info(log, "bookmark.added",
                    "id" to id, "collectionId" to collectionId,
                    "sourceType" to sourceType, "sourceId" to sourceId)

                BookmarkInfo(id, collectionId, null, sourceType, sourceId,
                    cachedContent, note, now, maxSort + 1)
            }
        } catch (e: Exception) {
            Dev.warn(log, "bookmark.add_failed", e,
                "collectionId" to collectionId, "sourceId" to sourceId)
            null
        }
    }

    /**
     * Remove a bookmark by its ID.
     *
     * Tags on the bookmark are automatically removed via ON DELETE CASCADE.
     *
     * @param bookmarkId The bookmark to remove
     * @return True if removed, false if not found or failed
     */
    fun removeBookmark(bookmarkId: String): Boolean {
        return try {
            facade.withDatabase { db ->
                val rows = db.execute("DELETE FROM bookmarks WHERE id = ?", bookmarkId)
                Dev.info(log, "bookmark.removed", "id" to bookmarkId, "existed" to (rows > 0))
                rows > 0
            }
        } catch (e: Exception) {
            Dev.warn(log, "bookmark.remove_failed", e, "id" to bookmarkId)
            false
        }
    }

    /**
     * Remove all bookmarks for a given source across ALL collections.
     *
     * This is the "un-star" action — removes the exchange from every collection.
     * Use [removeBookmark] to remove from a single collection.
     *
     * @param sourceType "CHAT" or "SUMMARY"
     * @param sourceId The exchange or summary ID to fully unbookmark
     * @return Number of bookmarks removed
     */
    fun removeAllBookmarks(sourceType: String, sourceId: String): Int {
        return try {
            facade.withDatabase { db ->
                val rows = db.execute(
                    "DELETE FROM bookmarks WHERE source_type = ? AND source_id = ?",
                    sourceType, sourceId
                )
                Dev.info(log, "bookmark.removed_all",
                    "sourceId" to sourceId, "count" to rows)
                rows
            }
        } catch (e: Exception) {
            Dev.warn(log, "bookmark.remove_all_failed", e, "sourceId" to sourceId)
            0
        }
    }

    /**
     * Check if a source (exchange or summary) is bookmarked in ANY collection.
     *
     * This is the quick check for displaying the star icon in the exchange list.
     *
     * @param sourceType "CHAT" or "SUMMARY"
     * @param sourceId The exchange or summary ID
     * @return True if bookmarked in at least one collection
     */
    fun isBookmarked(sourceType: String, sourceId: String): Boolean {
        return try {
            facade.withReadableDatabase { db ->
                val count = db.queryScalar(
                    "SELECT COUNT(*) FROM bookmarks WHERE source_type = ? AND source_id = ?",
                    sourceType, sourceId
                )
                count > 0
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Get all bookmarks for a given source across all collections.
     *
     * Used by the detail panel to show which collections an exchange belongs to,
     * along with the note and tags for each.
     *
     * @param sourceType "CHAT" or "SUMMARY"
     * @param sourceId The exchange or summary ID
     * @return List of bookmarks with collection names and tags
     */
    fun getBookmarksForSource(sourceType: String, sourceId: String): List<BookmarkInfo> {
        return try {
            facade.withReadableDatabase { db ->
                val bookmarks = db.query(
                    """SELECT b.id, b.collection_id, c.name as collection_name,
                              b.source_type, b.source_id, b.cached_content,
                              b.note, b.added_at, b.sort_order
                       FROM bookmarks b
                       JOIN collections c ON c.id = b.collection_id
                       WHERE b.source_type = ? AND b.source_id = ?
                       ORDER BY b.added_at""",
                    sourceType, sourceId
                ) { rs ->
                    BookmarkInfo(
                        id = rs.getString("id"),
                        collectionId = rs.getString("collection_id"),
                        collectionName = rs.getString("collection_name"),
                        sourceType = rs.getString("source_type"),
                        sourceId = rs.getString("source_id"),
                        cachedContent = rs.getString("cached_content"),
                        note = rs.getString("note"),
                        addedAt = Instant.parse(rs.getString("added_at")),
                        sortOrder = rs.getInt("sort_order")
                    )
                }

                // Attach tags to each bookmark
                bookmarks.map { bm ->
                    val tags = db.query(
                        "SELECT tag FROM bookmark_tags WHERE bookmark_id = ?",
                        bm.id
                    ) { rs -> rs.getString("tag") }
                    bm.copy(tags = tags)
                }
            }
        } catch (e: Exception) {
            Dev.warn(log, "bookmarks.get_for_source_failed", e, "sourceId" to sourceId)
            emptyList()
        }
    }

    /**
     * Get all bookmarks in a specific collection.
     *
     * Used when the user clicks a collection in the sidebar to browse its contents.
     *
     * @param collectionId The collection to browse
     * @return List of bookmarks with tags, ordered by sort_order
     */
    fun getBookmarksInCollection(collectionId: String): List<BookmarkInfo> {
        return try {
            facade.withReadableDatabase { db ->
                val bookmarks = db.query(
                    """SELECT b.id, b.collection_id, c.name as collection_name,
                              b.source_type, b.source_id, b.cached_content,
                              b.note, b.added_at, b.sort_order
                       FROM bookmarks b
                       JOIN collections c ON c.id = b.collection_id
                       WHERE b.collection_id = ?
                       ORDER BY b.sort_order""",
                    collectionId
                ) { rs ->
                    BookmarkInfo(
                        id = rs.getString("id"),
                        collectionId = rs.getString("collection_id"),
                        collectionName = rs.getString("collection_name"),
                        sourceType = rs.getString("source_type"),
                        sourceId = rs.getString("source_id"),
                        cachedContent = rs.getString("cached_content"),
                        note = rs.getString("note"),
                        addedAt = Instant.parse(rs.getString("added_at")),
                        sortOrder = rs.getInt("sort_order")
                    )
                }

                bookmarks.map { bm ->
                    val tags = db.query(
                        "SELECT tag FROM bookmark_tags WHERE bookmark_id = ?",
                        bm.id
                    ) { rs -> rs.getString("tag") }
                    bm.copy(tags = tags)
                }
            }
        } catch (e: Exception) {
            Dev.warn(log, "bookmarks.get_in_collection_failed", e, "collectionId" to collectionId)
            emptyList()
        }
    }

    /**
     * Update the note on a bookmark.
     *
     * @param bookmarkId The bookmark to update
     * @param note The new note text (null to clear)
     * @return True if updated, false if not found or failed
     */
    fun updateNote(bookmarkId: String, note: String?): Boolean {
        return try {
            facade.withDatabase { db ->
                val rows = db.execute(
                    "UPDATE bookmarks SET note = ? WHERE id = ?",
                    note, bookmarkId
                )
                rows > 0
            }
        } catch (e: Exception) {
            Dev.warn(log, "bookmark.update_note_failed", e, "id" to bookmarkId)
            false
        }
    }

    // ==================== Tags ====================

    /**
     * Add a tag to a bookmark.
     *
     * Tags are unique per bookmark — adding the same tag twice is a no-op
     * (UNIQUE constraint on bookmark_id + tag).
     *
     * @param bookmarkId The bookmark to tag
     * @param tag The tag value (e.g., "jwt", "security", "urgent")
     * @return True if added (or already existed), false on failure
     */
    fun addTag(bookmarkId: String, tag: String): Boolean {
        return try {
            facade.withDatabase { db ->
                // INSERT OR IGNORE handles the UNIQUE(bookmark_id, tag) constraint
                db.execute(
                    "INSERT OR IGNORE INTO bookmark_tags (bookmark_id, tag) VALUES (?, ?)",
                    bookmarkId, tag
                )
                Dev.info(log, "tag.added", "bookmarkId" to bookmarkId, "tag" to tag)
                true
            }
        } catch (e: Exception) {
            Dev.warn(log, "tag.add_failed", e, "bookmarkId" to bookmarkId, "tag" to tag)
            false
        }
    }

    /**
     * Remove a tag from a bookmark.
     *
     * @param bookmarkId The bookmark to untag
     * @param tag The tag to remove
     * @return True if removed, false if not found or failed
     */
    fun removeTag(bookmarkId: String, tag: String): Boolean {
        return try {
            facade.withDatabase { db ->
                val rows = db.execute(
                    "DELETE FROM bookmark_tags WHERE bookmark_id = ? AND tag = ?",
                    bookmarkId, tag
                )
                Dev.info(log, "tag.removed", "bookmarkId" to bookmarkId, "tag" to tag)
                rows > 0
            }
        } catch (e: Exception) {
            Dev.warn(log, "tag.remove_failed", e, "bookmarkId" to bookmarkId, "tag" to tag)
            false
        }
    }

    /**
     * Get all tags on a bookmark.
     *
     * @param bookmarkId The bookmark to query
     * @return List of tag strings
     */
    fun getTags(bookmarkId: String): List<String> {
        return try {
            facade.withReadableDatabase { db ->
                db.query(
                    "SELECT tag FROM bookmark_tags WHERE bookmark_id = ? ORDER BY tag",
                    bookmarkId
                ) { rs -> rs.getString("tag") }
            }
        } catch (e: Exception) {
            Dev.warn(log, "tags.get_failed", e, "bookmarkId" to bookmarkId)
            emptyList()
        }
    }

    /**
     * Get all unique tags across all bookmarks visible to this project.
     *
     * Useful for tag autocomplete and the tag filter dropdown in the Library UI.
     *
     * @return List of unique tag strings, alphabetically sorted
     */
    fun getAllTags(): List<String> {
        val projectId = facade.resolveProjectId()

        return try {
            facade.withReadableDatabase { db ->
                db.query(
                    """SELECT DISTINCT bt.tag
                       FROM bookmark_tags bt
                       JOIN bookmarks b ON b.id = bt.bookmark_id
                       JOIN collections c ON c.id = b.collection_id
                       WHERE c.project_id IS NULL OR c.project_id = ?
                       ORDER BY bt.tag""",
                    projectId
                ) { rs -> rs.getString("tag") }
            }
        } catch (e: Exception) {
            Dev.warn(log, "tags.get_all_failed", e)
            emptyList()
        }
    }

    // ==================== Queries ====================

    /**
     * Get all exchange IDs that are bookmarked in any collection visible to this project.
     *
     * Used by SearchService to populate the "isBookmarked" field on search results
     * without doing N+1 queries per exchange.
     *
     * @return Set of source IDs that have at least one bookmark
     */
    fun getBookmarkedSourceIds(): Set<String> {
        val projectId = facade.resolveProjectId()

        return try {
            facade.withReadableDatabase { db ->
                db.query(
                    """SELECT DISTINCT b.source_id
                       FROM bookmarks b
                       JOIN collections c ON c.id = b.collection_id
                       WHERE c.project_id IS NULL OR c.project_id = ?""",
                    projectId
                ) { rs -> rs.getString("source_id") }.toSet()
            }
        } catch (e: Exception) {
            Dev.warn(log, "bookmarked_ids.get_failed", e)
            emptySet()
        }
    }

    /**
     * Count total bookmarks across all collections visible to this project.
     *
     * Used for the "Bookmarked" count in the Library sidebar.
     *
     * @return Total bookmark count
     */
    fun getTotalBookmarkCount(): Int {
        val projectId = facade.resolveProjectId()

        return try {
            facade.withReadableDatabase { db ->
                db.queryScalar(
                    """SELECT COUNT(DISTINCT b.source_id)
                       FROM bookmarks b
                       JOIN collections c ON c.id = b.collection_id
                       WHERE c.project_id IS NULL OR c.project_id = ?""",
                    projectId
                )
            }
        } catch (e: Exception) {
            Dev.warn(log, "bookmark_count.failed", e)
            0
        }
    }

    companion object {
        fun getInstance(project: Project): BookmarkService =
            project.getService(BookmarkService::class.java)
    }
}