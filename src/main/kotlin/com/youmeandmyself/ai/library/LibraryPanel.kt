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
import com.youmeandmyself.storage.StorageReadyListener
import com.youmeandmyself.storage.search.SearchCriteria
import com.youmeandmyself.storage.search.SearchResults
import com.youmeandmyself.storage.search.SearchService
import com.youmeandmyself.ai.bridge.CrossPanelBridge
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
 * ## Storage readiness
 *
 * The Library subscribes to [StorageReadyListener.TOPIC] to handle the race
 * condition where JCEF loads before storage initialization completes. Two paths:
 *
 * 1. **Storage already ready** (normal startup): isInitialized check passes
 *    immediately after HTML loads, refresh fires right away.
 * 2. **Storage still initializing** (dev-mode wipe, slow rebuild): the
 *    StorageReadyListener callback fires when init completes and triggers refresh.
 *
 * This replaces the previous Thread.sleep polling loop which was fragile and
 * could leave the Library permanently empty if init took > 10 seconds.
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

        // Subscribe to storage readiness notification BEFORE loading HTML.
        // This ensures we don't miss the event if init completes between
        // HTML load and subscription.
        project.messageBus.connect(this).subscribe(StorageReadyListener.TOPIC, StorageReadyListener {
            Dev.info(log, "library.storage_ready_received")
            isLoaded = true
            refresh()
        })

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

            // If storage is already initialized (normal startup order),
            // mark ready immediately. The StorageReadyListener subscription
            // above handles the race case where init hasn't completed yet.
            if (storageFacade.isInitialized) {
                Dev.info(log, "library.storage_already_ready")
                isLoaded = true
                refresh()
            }
            // Otherwise: isLoaded stays false, queries return empty gracefully,
            // and the StorageReadyListener callback will fire refresh() when
            // init completes. No polling, no Thread.sleep, no retry loops.
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

        // StorageReadyListener will call refresh() when init completes,
        // which re-triggers all queries from the JS side.
        if (!isLoaded) {
            Dev.info(log, "library.bridge.storage_not_ready", "command" to command)
            return """{"error": "Storage not ready yet", "retry": true}"""
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
                "addToCollection"          -> handleAddToCollection(payload)
                "removeFromCollection"     -> handleRemoveFromCollection(payload)
                // Returns all collections an item currently belongs to (for checkmarks in dropdown)
                "getBookmarksForItem"      -> handleGetBookmarksForItem(payload)

                // Tags (on bookmarks, not exchanges)
                "setTag"              -> handleSetTag(payload)
                "removeTag"           -> handleRemoveTag(payload)

                // Notes
                "updateNote"          -> handleUpdateNote(payload)

                // Stats & detail
                "getStats"            -> handleGetStats()
                "getExchange"         -> handleGetExchange(payload)

                // Code snippet bookmarks — Library "Bookmarks" section
                // TODO (Phase 4): implement getCodeBookmarks with smart grouping
                //   - group by conversation → then by day/week (threshold: 15 day-groups → week)
                //   - within group: >10 items → sub-group by hour/session
                //   - threshold is Pro-configurable (default: 15) — stub the settings key
                //   - items show snippet only; "Load full exchange" and "Go to chat" are Pro stubs
                "getCodeBookmarks"    -> """{"error": "Not yet implemented", "stub": true}"""

                // Cascading filter data for the Bookmarks section filter panel
                // TODO (Phase 4): implement getBookmarkFilterOptions
                //   - returns available languages (from code_bookmarks.language)
                //   - returns tags filtered by currently-selected language (from code_bookmark_tags)
                //   - cascading: selecting a language narrows tag options to PHP → Symfony/Laravel/etc
                "getBookmarkFilters"  -> """{"error": "Not yet implemented", "stub": true}"""

                // Soft delete — for Phase 6 deletion flow UI
                // TODO (Phase 6): implement softDeleteExchange, softDeleteConversation,
                //   softDeleteCodeBookmark — each must call countExchangeMarks first
                //   and return the mark count so the JS layer can show the warning dialog
                "softDeleteExchange"      -> """{"error": "Not yet implemented", "stub": true}"""
                "softDeleteConversation"  -> """{"error": "Not yet implemented", "stub": true}"""
                "softDeleteCodeBookmark"  -> """{"error": "Not yet implemented", "stub": true}"""

                // Chat sessions for sidebar
                "getChatSessions"     -> handleGetChatSessions()

                // Cross-panel navigation (TEMPORARY — remove when Library migrates to React)
                "continueChat"        -> handleContinueChat(payload)
                "switchToChat"        -> handleSwitchToChat()

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
            offset = req.offset ?: 0,
            isStarred = req.isStarred,
            conversationId = req.conversationId,
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
     * Add any bookmarkable item to a specific named collection.
     *
     * JS sends { sourceType, sourceId, collectionId }.
     *
     * sourceType must be one of: 'CHAT', 'SUMMARY', 'CONVERSATION', 'CODE_SNIPPET'
     * sourceId is the ID of the item in its respective table.
     *
     * This replaces the old { exchangeId, collectionId } shape which was
     * hardcoded to CHAT exchanges only. All item types now go through
     * the same polymorphic path.
     */
    private fun handleAddToCollection(payload: String): String {
        val req = gson.fromJson(payload, CollectionItemRequest::class.java)

        // Fetch a content preview for fast display in the collection browser.
        // Only CHAT/SUMMARY exchanges have a getFullExchange equivalent for now.
        // CODE_SNIPPET content lives directly in code_bookmarks.content.
        // CONVERSATION previews are a future enhancement — null is safe here.
        val cachedContent = try {
            when (req.sourceType) {
                "CHAT", "SUMMARY" -> storageFacade.getFullExchange(req.sourceId)?.assistantText?.take(500)
                else -> null  // TODO: add preview fetching for CONVERSATION and CODE_SNIPPET
            }
        } catch (_: Exception) { null }

        val bookmark = bookmarkService.addBookmark(
            collectionId = req.collectionId,
            sourceType = req.sourceType,
            sourceId = req.sourceId,
            cachedContent = cachedContent
        ) ?: return """{"error": "Failed to add to collection"}"""

        return gson.toJson(mapOf(
            "success" to true,
            "bookmarkId" to bookmark.id
        ))
    }

    /**
     * Remove any bookmarkable item from a specific collection.
     *
     * JS sends { sourceType, sourceId, collectionId }.
     * We find the bookmark linking them and delete it.
     * The item may still be in other collections — this only removes
     * the membership reference, not the item itself.
     */
    private fun handleRemoveFromCollection(payload: String): String {
        val req = gson.fromJson(payload, CollectionItemRequest::class.java)

        // Find the bookmark for this item in this collection
        val bookmarks = bookmarkService.getBookmarksForSource(req.sourceType, req.sourceId)
        val target = bookmarks.find { it.collectionId == req.collectionId }
            ?: return """{"error": "Item not in this collection"}"""

        val removed = bookmarkService.removeBookmark(target.id)
        return gson.toJson(mapOf("success" to removed))
    }

    /**
     * Return all collection memberships for a given item.
     *
     * JS sends { sourceType, sourceId }.
     * Used by the collection picker dropdown to show checkmarks on collections
     * the item is already in, before the user opens the dropdown.
     *
     * Returns a list of collectionIds the item belongs to — the dropdown
     * maps these to checkmarks client-side using the full collections list
     * it already has from getCollections.
     */
    private fun handleGetBookmarksForItem(payload: String): String {
        val req = gson.fromJson(payload, BookmarksForItemRequest::class.java)
        val bookmarks = bookmarkService.getBookmarksForSource(req.sourceType, req.sourceId)
        return gson.toJson(mapOf(
            "collectionIds" to bookmarks.map { it.collectionId }
        ))
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

        val starredCount = try {
            storageFacade.withReadableDatabase { db ->
                db.queryScalar(
                    "SELECT COUNT(*) FROM chat_exchanges WHERE is_starred = 1 AND project_id = ? AND deleted = 0",
                    storageFacade.resolveProjectId()
                )
            }
        } catch (e: Exception) { 0 }

        return gson.toJson(mapOf(
            "totalExchanges" to stats.totalExchanges,
            "totalStarred" to starredCount,
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
    // CHAT SESSIONS & CROSS-PANEL NAVIGATION
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Return chat sessions for the Library sidebar "Chats" section.
     * Queries the conversations table directly.
     */
    private fun handleGetChatSessions(): String {
        return try {
            val projectId = storageFacade.resolveProjectId()
            val sessions = storageFacade.withReadableDatabase { db ->
                db.query(
                    """SELECT id, title, updated_at, turn_count
                   FROM conversations
                   WHERE project_id = ? AND is_active = 1 AND deleted = 0
                   ORDER BY updated_at DESC""",
                    projectId
                ) { rs ->
                    mapOf(
                        "conversationId" to rs.getString("id"),
                        "title" to rs.getString("title"),
                        "updatedAt" to rs.getString("updated_at"),
                        "turnCount" to rs.getInt("turn_count")
                    )
                }
            }
            gson.toJson(mapOf("sessions" to sessions))
        } catch (e: Exception) {
            Dev.warn(log, "library.get_chat_sessions_failed", e)
            gson.toJson(mapOf("sessions" to emptyList<Any>()))
        }
    }

    /**
     * Open a conversation in the Chat tab via CrossPanelBridge.
     *
     * TEMPORARY: Remove when Library migrates to React.
     */
    private fun handleContinueChat(payload: String): String {
        val req = gson.fromJson(payload, ContinueChatRequest::class.java)
        val bridge = CrossPanelBridge.getInstance(project)
        val dispatched = bridge.openConversation(req.conversationId)
        return gson.toJson(mapOf("success" to dispatched))
    }

    /**
     * Switch the tool window to show the Chat tab.
     *
     * TEMPORARY: Remove when Library migrates to React.
     */
    private fun handleSwitchToChat(): String {
        return try {
            com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                val toolWindow = com.intellij.openapi.wm.ToolWindowManager
                    .getInstance(project)
                    .getToolWindow("YMM Assistant")
                toolWindow?.contentManager?.let { cm ->
                    val chatContent = cm.contents.find { it.displayName == "Chat" }
                    if (chatContent != null) cm.setSelectedContent(chatContent)
                }
            }
            gson.toJson(mapOf("success" to true))
        } catch (e: Exception) {
            Dev.warn(log, "library.switch_to_chat_failed", e)
            gson.toJson(mapOf("success" to false, "error" to e.message))
        }
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
        val offset: Int? = null,
        val isStarred: Boolean? = null,
        val conversationId: String? = null
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
    // sourceType: 'CHAT' | 'SUMMARY' | 'CONVERSATION' | 'CODE_SNIPPET'
    // sourceId: ID of the item in its respective table
    private data class CollectionItemRequest(
        val sourceType: String = "CHAT",
        val sourceId: String = "",
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

    // Cross-panel navigation (TEMPORARY)
    private data class ContinueChatRequest(
        val conversationId: String = ""
    )

    // Collection picker — fetch memberships for checkmarks
    private data class BookmarksForItemRequest(
        val sourceType: String = "CHAT",
        val sourceId: String = ""
    )
}