package com.youmeandmyself.ai.library

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefJSQuery
import com.youmeandmyself.dev.Dev
import com.youmeandmyself.storage.BookmarkService
import com.youmeandmyself.storage.LocalStorageFacade
import com.youmeandmyself.storage.search.SearchCriteria
import com.youmeandmyself.storage.search.SearchResults
import com.youmeandmyself.storage.search.SearchService
import java.awt.BorderLayout
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.JPanel

/**
 * Library tab panel — JCEF-based exchange browser with search, filters, and bookmarks.
 *
 * Uses JBCefJSQuery for JS↔Kotlin communication, matching the same pattern
 * as ChatBrowserComponent. No raw CEF APIs.
 *
 * ## Communication flow
 *
 *   JS: window.sendToLibrary("library:command:jsonPayload")
 *   →  JBCefJSQuery handler routes to the appropriate method
 *   →  Returns JSON response string
 *
 * ## Bookmark model
 *
 * The JS frontend thinks in terms of exchangeIds for simplicity. The Kotlin
 * bridge translates between that and the 3-table bookmark model:
 *
 * - **Quick bookmark (star toggle):** Uses a default collection per project.
 *   When the user clicks the star, the exchange gets added to/removed from
 *   a "Starred" collection. This keeps the star toggle simple.
 * - **Explicit collection management:** The user can create named collections
 *   and add exchanges to specific collections via the detail panel.
 * - **Tags:** Applied to the bookmark (the link), not the exchange. The JS
 *   passes exchangeId + collectionId, and the bridge resolves the bookmarkId.
 *
 * ## Why the bridge does this translation
 *
 * The 3-table model (collections → bookmarks → bookmark_tags) is more powerful
 * than the JS UI currently needs. Same exchange can be in multiple collections
 * with different notes and tags. The bridge hides this complexity from the JS
 * while preserving the full model for future UI expansion.
 */
class LibraryPanel(
    private val project: Project,
    parentDisposable: Disposable
) : JPanel(BorderLayout()), Disposable {

    private val log = Dev.logger(LibraryPanel::class.java)
    private val browser: JBCefBrowser = JBCefBrowser()
    private var isLoaded = false

    private val storageFacade: LocalStorageFacade = LocalStorageFacade.getInstance(project)
    private val searchService: SearchService = SearchService.getInstance(project)
    private val bookmarkService: BookmarkService = BookmarkService.getInstance(project)

    private val gson: Gson = GsonBuilder().serializeNulls().create()
    private val dateFormatter: DateTimeFormatter = DateTimeFormatter
        .ofPattern("MMM d, yyyy h:mm a")
        .withZone(ZoneId.systemDefault())

    /**
     * The ID of the default "Starred" collection for quick bookmarking.
     *
     * Lazily resolved: on first bookmark action, we look for a collection
     * named "Starred" in this project (or global). If none exists, we create one.
     * Cached after first resolution to avoid repeated queries.
     */
    private var starredCollectionId: String? = null

    @Suppress("DEPRECATION", "removal")
    private val jsQuery = JBCefJSQuery.create(browser)

    init {
        Disposer.register(parentDisposable, this)
        Disposer.register(this, browser)

        jsQuery.addHandler { message ->
            if (message.startsWith("library:")) {
                val result = handleLibraryMessage(message)
                if (result != null) JBCefJSQuery.Response(result) else null
            } else {
                Dev.info(log, "library.unknown_message", "message" to message)
                null
            }
        }

        add(browser.component, BorderLayout.CENTER)
        loadLibraryHtml()
        Dev.info(log, "library.panel.init", "project" to project.name)
    }

    private fun loadLibraryHtml() {
        val htmlUrl = javaClass.getResource("/chat-window/library.html")
        if (htmlUrl != null) {
            val htmlContent = htmlUrl.readText()
            val bridgeScript = """
                <script>
                window.sendToLibrary = function(message, onSuccess, onFailure) {
                    ${jsQuery.inject("message", "onSuccess", "onFailure")}
                };
                window.__libraryBridgeReady = true;
                if (window.__onBridgeReady) window.__onBridgeReady();
                </script>
            """.trimIndent()

            // Insert bridge script right before </head>
            val injectedHtml = if (htmlContent.contains("</head>")) {
                htmlContent.replace("</head>", "$bridgeScript\n</head>")
            } else {
                "$bridgeScript\n$htmlContent"
            }

            browser.loadHTML(injectedHtml)

            // Wait for storage before signaling readiness
            Thread {
                val facade = LocalStorageFacade.getInstance(project)
                var attempts = 0
                while (!facade.isInitialized && attempts < 100) {
                    Thread.sleep(100)
                    attempts++
                }
                isLoaded = true
                refresh()
            }.start()
        } else {
            browser.loadHTML("""
                <!DOCTYPE html>
                <html><body style="font-family:system-ui;padding:20px;color:#ccc;background:#1e1e1e;">
                <p>⚠️ Library HTML not found at <code>/chat-window/library.html</code></p>
                </body></html>
            """.trimIndent())
            Dev.warn(log, "library.panel.no_html", null, "path" to "/chat-window/library.html")
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // MESSAGE ROUTING
    // ══════════════════════════════════════════════════════════════════════

    private fun handleLibraryMessage(raw: String): String? {
        val afterPrefix = raw.removePrefix("library:")
        val colonIdx = afterPrefix.indexOf(':')
        val command: String
        val payload: String
        if (colonIdx >= 0) {
            command = afterPrefix.substring(0, colonIdx)
            payload = afterPrefix.substring(colonIdx + 1)
        } else {
            command = afterPrefix
            payload = ""
        }

        return try {
            when (command) {
                // Search
                "search"              -> handleSearch(payload)
                "getRecent"           -> handleGetRecent(payload)
                "getBookmarked"       -> handleGetBookmarked(payload)

                // Collections
                "getCollections"      -> handleGetCollections()
                "createCollection"    -> handleCreateCollection(payload)
                "deleteCollection"    -> handleDeleteCollection(payload)
                "renameCollection"    -> handleRenameCollection(payload)
                "getCollectionItems"  -> handleGetCollectionItems(payload)

                // Bookmarks (quick star toggle — uses default "Starred" collection)
                "bookmark"            -> handleBookmark(payload)
                "unbookmark"          -> handleUnbookmark(payload)
                "clearAllBookmarks"   -> handleClearAllBookmarks(payload)

                // Bookmarks (explicit collection management)
                "addToCollection"     -> handleAddToCollection(payload)
                "removeFromCollection"-> handleRemoveFromCollection(payload)

                // Tags (on bookmarks, not exchanges)
                "setTag"              -> handleSetTag(payload)
                "removeTag"           -> handleRemoveTag(payload)

                // Notes
                "updateNote"          -> handleUpdateNote(payload)

                // Stats & detail
                "getStats"            -> handleGetStats()
                "getExchange"         -> handleGetExchange(payload)

                else -> """{"error": "Unknown command: $command"}"""
            }
        } catch (e: Exception) {
            Dev.warn(log, "library.bridge.error", e, "command" to command)
            """{"error": "${e.message?.replace("\"", "\\\"") ?: "Unknown error"}"}"""
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // SEARCH HANDLERS
    // ══════════════════════════════════════════════════════════════════════

    private fun handleSearch(payload: String): String {
        val req = gson.fromJson(payload, SearchRequest::class.java)
        val criteria = SearchCriteria(
            query = req.query ?: "",
            languages = req.languages,
            hasCode = req.hasCode,
            isBookmarked = req.isBookmarked,
            collectionId = req.collectionId,
            dateFrom = req.dateFrom?.let { Instant.parse(it) },
            dateTo = req.dateTo?.let { Instant.parse(it) },
            limit = req.limit ?: 50,
            offset = req.offset ?: 0
        )
        val results = searchService.search(criteria)
        return gson.toJson(enrichWithBookmarkStatus(results).toTransport())
    }

    private fun handleGetRecent(payload: String): String {
        val req = if (payload.isNotBlank()) gson.fromJson(payload, RecentRequest::class.java) else RecentRequest()
        val criteria = SearchCriteria(query = "", limit = req.limit ?: 50, offset = req.offset ?: 0)
        val results = searchService.search(criteria)
        return gson.toJson(enrichWithBookmarkStatus(results).toTransport())
    }

    private fun handleGetBookmarked(payload: String): String {
        val req = if (payload.isNotBlank()) gson.fromJson(payload, RecentRequest::class.java) else RecentRequest()
        val criteria = SearchCriteria(query = "", isBookmarked = true, limit = req.limit ?: 50, offset = req.offset ?: 0)
        val results = searchService.search(criteria)
        return gson.toJson(enrichWithBookmarkStatus(results).toTransport())
    }

    /**
     * Enrich search results with accurate bookmark status from the bookmarks table.
     *
     * The SearchService may use the old flags column for "isBookmarked" filtering,
     * but we override the display status using the real bookmarks table so the
     * star icons reflect the actual 3-table state.
     */
    private fun enrichWithBookmarkStatus(results: SearchResults): SearchResults {
        return try {
            val bookmarkedIds = bookmarkService.getBookmarkedSourceIds()
            val enrichedResults = results.results.map { scored ->
                scored.copy(isBookmarked = scored.exchangeId in bookmarkedIds)
            }
            results.copy(results = enrichedResults)
        } catch (e: Exception) {
            Dev.warn(log, "library.enrich_bookmarks_failed", e)
            results
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // COLLECTION HANDLERS
    // ══════════════════════════════════════════════════════════════════════

    private fun handleGetCollections(): String {
        val collections = bookmarkService.getCollections()
        return gson.toJson(mapOf(
            "collections" to collections.map { col ->
                mapOf(
                    "id" to col.id,
                    "name" to col.name,
                    "icon" to col.icon,
                    "projectId" to col.projectId,
                    "count" to col.bookmarkCount,
                    "createdAt" to col.createdAt.toString()
                )
            }
        ))
    }

    private fun handleCreateCollection(payload: String): String {
        val req = gson.fromJson(payload, CreateCollectionRequest::class.java)
        val collection = bookmarkService.createCollection(req.name, req.icon)
            ?: return """{"error": "Failed to create collection"}"""

        return gson.toJson(mapOf(
            "success" to true,
            "collection" to mapOf(
                "id" to collection.id,
                "name" to collection.name,
                "icon" to collection.icon,
                "count" to 0
            )
        ))
    }

    private fun handleDeleteCollection(payload: String): String {
        val req = gson.fromJson(payload, CollectionIdRequest::class.java)
        val deleted = bookmarkService.deleteCollection(req.collectionId)
        return gson.toJson(mapOf("success" to deleted))
    }

    private fun handleRenameCollection(payload: String): String {
        val req = gson.fromJson(payload, RenameCollectionRequest::class.java)
        val renamed = bookmarkService.renameCollection(req.collectionId, req.name)
        return gson.toJson(mapOf("success" to renamed))
    }

    private fun handleGetCollectionItems(payload: String): String {
        val req = gson.fromJson(payload, CollectionIdRequest::class.java)
        val bookmarks = bookmarkService.getBookmarksInCollection(req.collectionId)
        return gson.toJson(mapOf(
            "bookmarks" to bookmarks.map { bm ->
                mapOf(
                    "bookmarkId" to bm.id,
                    "sourceType" to bm.sourceType,
                    "sourceId" to bm.sourceId,
                    "cachedContent" to bm.cachedContent,
                    "note" to bm.note,
                    "tags" to bm.tags,
                    "addedAt" to bm.addedAt.toString()
                )
            }
        ))
    }

    // ══════════════════════════════════════════════════════════════════════
    // BOOKMARK HANDLERS — Quick star toggle
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Quick bookmark — add exchange to the default "Starred" collection.
     *
     * The JS sends just { exchangeId } and optionally { note }.
     * We resolve the default collection and create the bookmark there.
     * This keeps the star toggle in the exchange list simple.
     */
    private fun handleBookmark(payload: String): String {
        val req = gson.fromJson(payload, BookmarkRequest::class.java)
        val collectionId = resolveStarredCollection()
            ?: return """{"error": "Could not resolve default collection"}"""

        // Get cached content for the bookmark (assistant_text preview)
        val cachedContent = try {
            storageFacade.getFullExchange(req.exchangeId)?.assistantText?.take(500)
        } catch (_: Exception) { null }

        val bookmark = bookmarkService.addBookmark(
            collectionId = collectionId,
            sourceType = "CHAT",
            sourceId = req.exchangeId,
            cachedContent = cachedContent,
            note = req.note
        ) ?: return """{"error": "Failed to bookmark exchange"}"""

        return gson.toJson(mapOf(
            "success" to true,
            "exchangeId" to req.exchangeId,
            "bookmarkId" to bookmark.id
        ))
    }

    /**
     * Quick un-star — remove exchange from the "Starred" collection ONLY.
     *
     * If the user already moved this exchange to other collections ("Auth Patterns",
     * "Ask Tech Lead", etc.), those memberships are untouched. The star is just
     * the quick-save; organised bookmarks are independent.
     *
     * For removing from ALL collections, use "clearAllBookmarks".
     */
    private fun handleUnbookmark(payload: String): String {
        val req = gson.fromJson(payload, BookmarkRequest::class.java)
        val starredId = resolveStarredCollection()
            ?: return """{"error": "Could not resolve Starred collection"}"""

        // Find the specific bookmark linking this exchange to Starred
        val bookmarks = bookmarkService.getBookmarksForSource("CHAT", req.exchangeId)
        val starredBookmark = bookmarks.find { it.collectionId == starredId }

        if (starredBookmark != null) {
            bookmarkService.removeBookmark(starredBookmark.id)
        }

        return gson.toJson(mapOf(
            "success" to true,
            "exchangeId" to req.exchangeId,
            // Let JS know if the exchange is still bookmarked in other collections
            "stillBookmarked" to bookmarks.any { it.collectionId != starredId }
        ))
    }

    /**
     * Nuclear option — remove exchange from ALL collections.
     *
     * Cascades away all bookmarks, tags, and notes for this exchange.
     * The JS should confirm with the user before calling this.
     */
    private fun handleClearAllBookmarks(payload: String): String {
        val req = gson.fromJson(payload, BookmarkRequest::class.java)
        val removed = bookmarkService.removeAllBookmarks("CHAT", req.exchangeId)
        return gson.toJson(mapOf(
            "success" to true,
            "exchangeId" to req.exchangeId,
            "removedCount" to removed
        ))
    }

    // ══════════════════════════════════════════════════════════════════════
    // BOOKMARK HANDLERS — Explicit collection management
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Add an exchange to a specific named collection.
     *
     * JS sends { exchangeId, collectionId }.
     */
    private fun handleAddToCollection(payload: String): String {
        val req = gson.fromJson(payload, CollectionItemRequest::class.java)

        val cachedContent = try {
            storageFacade.getFullExchange(req.exchangeId)?.assistantText?.take(500)
        } catch (_: Exception) { null }

        val bookmark = bookmarkService.addBookmark(
            collectionId = req.collectionId,
            sourceType = "CHAT",
            sourceId = req.exchangeId,
            cachedContent = cachedContent
        ) ?: return """{"error": "Failed to add to collection"}"""

        return gson.toJson(mapOf(
            "success" to true,
            "bookmarkId" to bookmark.id
        ))
    }

    /**
     * Remove an exchange from a specific collection.
     *
     * JS sends { exchangeId, collectionId }. We find the bookmark linking
     * them and delete it. The exchange may still be in other collections.
     */
    private fun handleRemoveFromCollection(payload: String): String {
        val req = gson.fromJson(payload, CollectionItemRequest::class.java)

        // Find the bookmark for this exchange in this collection
        val bookmarks = bookmarkService.getBookmarksForSource("CHAT", req.exchangeId)
        val target = bookmarks.find { it.collectionId == req.collectionId }
            ?: return """{"error": "Exchange not in this collection"}"""

        val removed = bookmarkService.removeBookmark(target.id)
        return gson.toJson(mapOf("success" to removed))
    }

    // ══════════════════════════════════════════════════════════════════════
    // TAG HANDLERS
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Add a tag to a bookmark.
     *
     * The JS currently sends { exchangeId, tag }. We resolve the bookmark
     * for this exchange (in the Starred collection) and add the tag there.
     *
     * When the JS is updated to be collection-aware, it can send
     * { bookmarkId, tag } directly.
     */
    private fun handleSetTag(payload: String): String {
        val req = gson.fromJson(payload, TagRequest::class.java)

        val bookmarkId = resolveBookmarkId(req.exchangeId, req.bookmarkId)
            ?: return """{"error": "Exchange is not bookmarked — bookmark it first to add tags"}"""

        val added = bookmarkService.addTag(bookmarkId, req.tag)
        return gson.toJson(mapOf("success" to added))
    }

    private fun handleRemoveTag(payload: String): String {
        val req = gson.fromJson(payload, TagRequest::class.java)

        val bookmarkId = resolveBookmarkId(req.exchangeId, req.bookmarkId)
            ?: return """{"error": "Bookmark not found for this exchange"}"""

        val removed = bookmarkService.removeTag(bookmarkId, req.tag)
        return gson.toJson(mapOf("success" to removed))
    }

    // ══════════════════════════════════════════════════════════════════════
    // NOTE HANDLER
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Update the note on a bookmark.
     *
     * JS sends { exchangeId, note } or { bookmarkId, note }.
     * If only exchangeId is given, we find the first bookmark for it.
     */
    private fun handleUpdateNote(payload: String): String {
        val req = gson.fromJson(payload, NoteRequest::class.java)

        val bookmarkId = resolveBookmarkId(req.exchangeId, req.bookmarkId)
            ?: return """{"error": "Exchange is not bookmarked — bookmark it first to add notes"}"""

        val updated = bookmarkService.updateNote(bookmarkId, req.note)
        return gson.toJson(mapOf("success" to updated))
    }

    // ══════════════════════════════════════════════════════════════════════
    // STATS & DETAIL
    // ══════════════════════════════════════════════════════════════════════

    private fun handleGetStats(): String {
        val stats = storageFacade.getLibraryStats()

        // Override bookmark count with the real count from the bookmarks table
        val bookmarkCount = bookmarkService.getTotalBookmarkCount()

        return gson.toJson(mapOf(
            "totalExchanges" to stats.totalExchanges,
            "totalBookmarks" to bookmarkCount,
            "totalWithCode" to stats.totalWithCode,
            "totalTokens" to stats.totalTokens
        ))
    }

    private fun handleGetExchange(payload: String): String {
        val req = gson.fromJson(payload, ExchangeRequest::class.java)
        val exchange = storageFacade.getFullExchange(req.exchangeId)
            ?: return """{"error": "Exchange not found: ${req.exchangeId}"}"""

        // Get all bookmarks for this exchange (across all collections)
        val bookmarks = bookmarkService.getBookmarksForSource("CHAT", req.exchangeId)
        val isBookmarked = bookmarks.isNotEmpty()

        // Aggregate tags and notes from all bookmarks for display
        // (The detail panel shows the combined view; future UI can show per-collection)
        val allTags = bookmarks.flatMap { it.tags }.distinct()
        val firstNote = bookmarks.firstOrNull { !it.note.isNullOrBlank() }?.note
        val collectionNames = bookmarks.mapNotNull { it.collectionName }

        // Include bookmark IDs so the JS can target specific bookmarks for tag/note ops
        val bookmarkDetails = bookmarks.map { bm ->
            mapOf(
                "bookmarkId" to bm.id,
                "collectionId" to bm.collectionId,
                "collectionName" to bm.collectionName,
                "note" to bm.note,
                "tags" to bm.tags
            )
        }

        return gson.toJson(mapOf(
            "exchangeId" to exchange.id,
            "profileName" to exchange.profileName,
            "prompt" to exchange.userPrompt,
            "response" to exchange.assistantText,
            "timestamp" to dateFormatter.format(exchange.timestamp),
            "timestampIso" to exchange.timestamp.toString(),
            "tokensUsed" to exchange.tokensUsed,
            "hasCode" to exchange.hasCode,
            "languages" to exchange.languages,
            "topics" to exchange.topics,
            "filePaths" to exchange.filePaths,
            "ideContext" to if (exchange.ideContextFile != null) {
                mapOf("activeFile" to exchange.ideContextFile, "branch" to exchange.ideContextBranch)
            } else null,
            // Bookmark info — aggregated across all collections
            "isBookmarked" to isBookmarked,
            "bookmarkNote" to firstNote,
            "bookmarkCollections" to collectionNames,
            "bookmarkTags" to allTags,
            // Per-bookmark detail for future collection-aware UI
            "bookmarks" to bookmarkDetails
        ))
    }

    // ══════════════════════════════════════════════════════════════════════
    // TRANSPORT — SearchResults to JSON
    // ══════════════════════════════════════════════════════════════════════

    private fun SearchResults.toTransport(): Map<String, Any?> = mapOf(
        "totalCount" to totalCount,
        "query" to query,
        "appliedFilters" to appliedFilters,
        "results" to results.map { s ->
            mapOf(
                "exchangeId" to s.exchangeId,
                "profileName" to s.profileName,
                "promptPreview" to s.promptPreview,
                "responsePreview" to s.responsePreview,
                "timestamp" to dateFormatter.format(s.timestamp),
                "timestampIso" to s.timestamp.toString(),
                "score" to s.score,
                "matchReasons" to s.matchReasons.map { it.name },
                "hasCode" to s.hasCode,
                "languages" to s.languages,
                "topics" to s.topics,
                "tokensUsed" to s.tokensUsed,
                "isBookmarked" to s.isBookmarked
            )
        }
    )

    // ══════════════════════════════════════════════════════════════════════
    // HELPERS
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Resolve or create the default "Starred" collection for quick bookmarking.
     *
     * The "Starred" collection is scoped to the current project. It's created
     * lazily on first bookmark action and cached for the session.
     *
     * @return The collection ID, or null if creation failed
     */
    private fun resolveStarredCollection(): String? {
        // Return cached ID if we already resolved it
        starredCollectionId?.let { return it }

        // Look for existing "Starred" collection in this project or global
        val collections = bookmarkService.getCollections()
        val existing = collections.find { it.name == "Starred" }
        if (existing != null) {
            starredCollectionId = existing.id
            return existing.id
        }

        // Create it
        val created = bookmarkService.createCollection("Starred", "⭐")
        if (created != null) {
            starredCollectionId = created.id
            return created.id
        }

        Dev.warn(log, "library.starred_collection_failed", null)
        return null
    }

    /**
     * Resolve a bookmark ID from either a direct bookmarkId or an exchangeId.
     *
     * This is the bridge between the JS world (which thinks in exchangeIds)
     * and the 3-table model (which needs bookmarkIds for tag/note operations).
     *
     * Priority:
     * 1. If bookmarkId is provided, use it directly
     * 2. If only exchangeId, find the first bookmark for this exchange
     *
     * @param exchangeId The exchange ID (from JS)
     * @param bookmarkId Optional direct bookmark ID (from collection-aware JS)
     * @return The resolved bookmark ID, or null if not bookmarked
     */
    private fun resolveBookmarkId(exchangeId: String?, bookmarkId: String?): String? {
        // Direct bookmark ID takes priority
        if (!bookmarkId.isNullOrBlank()) return bookmarkId

        // Fall back to finding a bookmark by exchange ID
        if (exchangeId.isNullOrBlank()) return null
        val bookmarks = bookmarkService.getBookmarksForSource("CHAT", exchangeId)
        return bookmarks.firstOrNull()?.id
    }

    // ══════════════════════════════════════════════════════════════════════
    // PUBLIC API — Called from Kotlin (e.g., ChatPanel after saving)
    // ══════════════════════════════════════════════════════════════════════

    fun refresh() {
        if (isLoaded) {
            browser.cefBrowser.executeJavaScript(
                "if (window.libraryRefresh) window.libraryRefresh();",
                "", 0
            )
        }
    }

    fun showExchange(exchangeId: String) {
        if (isLoaded) {
            browser.cefBrowser.executeJavaScript(
                "if (window.libraryShowExchange) window.libraryShowExchange('$exchangeId');",
                "", 0
            )
        }
    }

    override fun dispose() {
        Dev.info(log, "library.panel.disposed")
    }

    // ══════════════════════════════════════════════════════════════════════
    // DTOs — JSON payloads from the JS bridge
    // ══════════════════════════════════════════════════════════════════════

    // Search
    private data class SearchRequest(
        val query: String? = null,
        val languages: List<String>? = null,
        val hasCode: Boolean? = null,
        val isBookmarked: Boolean? = null,
        val collectionId: String? = null,
        val dateFrom: String? = null,
        val dateTo: String? = null,
        val limit: Int? = null,
        val offset: Int? = null
    )
    private data class RecentRequest(
        val limit: Int? = null,
        val offset: Int? = null
    )

    // Collections
    private data class CreateCollectionRequest(
        val name: String = "",
        val icon: String? = null
    )
    private data class CollectionIdRequest(
        val collectionId: String = ""
    )
    private data class RenameCollectionRequest(
        val collectionId: String = "",
        val name: String = ""
    )

    // Bookmarks — quick star toggle (exchangeId-based)
    private data class BookmarkRequest(
        val exchangeId: String = "",
        val note: String? = null
    )

    // Bookmarks — explicit collection management
    private data class CollectionItemRequest(
        val exchangeId: String = "",
        val collectionId: String = ""
    )

    // Tags — supports both exchangeId (legacy) and bookmarkId (new)
    private data class TagRequest(
        val exchangeId: String? = null,
        val bookmarkId: String? = null,
        val tag: String = ""
    )

    // Notes — supports both exchangeId (legacy) and bookmarkId (new)
    private data class NoteRequest(
        val exchangeId: String? = null,
        val bookmarkId: String? = null,
        val note: String? = null
    )

    // Detail
    private data class ExchangeRequest(
        val exchangeId: String = ""
    )
}