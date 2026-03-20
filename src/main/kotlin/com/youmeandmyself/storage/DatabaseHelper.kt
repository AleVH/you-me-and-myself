package com.youmeandmyself.storage

import com.intellij.openapi.diagnostic.Logger
import com.youmeandmyself.dev.Dev
import com.youmeandmyself.dev.DevMode
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet

/**
 * Manages the SQLite database for the plugin's metadata layer.
 *
 * ## Role in the Architecture
 *
 * JSONL files are the source of truth — they hold full exchange and summary content.
 * This database is the "brain" that knows where everything is, how things relate,
 * and enables fast queries without scanning JSONL files.
 *
 * If the database is ever lost or corrupted, it can be fully rebuilt from JSONL.
 *
 * ## Schema: 15 Tables
 *
 * Created on first run, all tables exist from day one even if some features
 * (summaries, bookmarks) haven't been built yet. This avoids future migrations.
 *
 * 1.  projects                     — registry of known projects
 * 2.  chat_exchanges               — metadata about AI conversations (points to JSONL)
 * 3.  code_elements                — map of codebase structure (files, classes, methods)
 * 4.  summaries                    — metadata about generated summaries (points to JSONL)
 * 5.  summary_hierarchy            — parent/child relationships between summaries
 * 6.  summary_config               — per-project summarization settings
 * 7.  collections                  — user-created groups for bookmarks
 * 8.  bookmarks                    — saved items (chat, summary, conversation, or snippet) with cached content
 * 9.  bookmark_tags                — user-created tags on exchange/summary bookmarks
 * 10. code_bookmark_tags           — user-created tags on code snippet bookmarks (same pattern as bookmark_tags)
 * 11. storage_config               — global plugin settings (retention, cleanup thresholds)
 * 12. open_tabs                    — persisted chat tab state for restore on IDE restart
 * 13. code_bookmarks               — saved code blocks from chat responses
 * 14. metrics                      — per-exchange token usage metrics for the Metrics Module
 * 15. assistant_profile_summary    — stores the summarized version of the user's assistant profile
 *
 * ## Thread Safety
 *
 * SQLite in WAL mode supports concurrent reads with a single writer.
 * The caller (LocalStorageFacade) is responsible for ensuring write serialization
 * via its Mutex. This class does not manage its own locking.
 *
 * ## Connection Management
 *
 * A single connection is held open for the plugin's lifetime. SQLite performs
 * best with a single connection in WAL mode. The connection is closed explicitly
 * via [close] when the project is disposed.
 *
 * @param dbFile The SQLite database file path
 */
class DatabaseHelper(private val dbFile: File) {

    private val log = Logger.getInstance(DatabaseHelper::class.java)

    /**
     * The JDBC connection. Lazy-initialized on first access.
     *
     * Using a single long-lived connection because:
     * - SQLite performs best with one connection in WAL mode
     * - Avoids connection pool overhead for a local database
     * - WAL mode allows concurrent reads alongside this single writer
     */
    private var connection: Connection? = null

    // ==================== Lifecycle ====================

    /**
     * Open the database connection and ensure the schema exists.
     *
     * This is idempotent — safe to call multiple times. On first run, creates
     * all 10 tables. On subsequent runs, CREATE IF NOT EXISTS is a no-op.
     *
     * Also configures SQLite pragmas for optimal performance:
     * - WAL mode: concurrent reads, better write performance
     * - Normal synchronous: good balance of safety and speed
     * - Foreign keys: enforce referential integrity
     *
     * @throws RuntimeException if the database cannot be opened or schema creation fails
     */
    fun open() {
        try {
            dbFile.parentFile?.mkdirs()

            // Load the SQLite JDBC driver explicitly.
            // IntelliJ bundles org.xerial:sqlite-jdbc, so this should always succeed.
            Class.forName("org.sqlite.JDBC")

            val conn = DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}")
            connection = conn

            // Configure pragmas for optimal performance
            conn.createStatement().use { stmt ->
                // WAL mode: allows concurrent reads while writing, better performance
                stmt.execute("PRAGMA journal_mode=WAL")
                // Normal sync: fsync at critical moments only (not every transaction)
                stmt.execute("PRAGMA synchronous=NORMAL")
                // Enforce foreign key constraints (SQLite has them off by default!)
                stmt.execute("PRAGMA foreign_keys=ON")
            }

            // Create all tables
            createSchema(conn)

            Dev.info(log, "db.open", "path" to dbFile.absolutePath)
        } catch (e: Exception) {
            Dev.error(log, "db.open.failed", e, "path" to dbFile.absolutePath)
            throw RuntimeException("Failed to open storage database: ${dbFile.absolutePath}", e)
        }
    }

    /**
     * Close the database connection.
     *
     * Call this when the project is disposed. After closing, no further
     * operations are possible without calling [open] again.
     */
    fun close() {
        try {
            connection?.close()
            connection = null
            Dev.info(log, "db.close")
        } catch (e: Exception) {
            Dev.error(log, "db.close.failed", e)
        }
    }

    /**
     * Check whether the database connection is open and usable.
     */
    fun isOpen(): Boolean = connection?.isClosed == false

    // ==================== Query Execution ====================

    /**
     * Execute a write statement (INSERT, UPDATE, DELETE) with parameters.
     *
     * Uses prepared statements to prevent SQL injection and improve performance.
     *
     * @param sql The SQL statement with ? placeholders
     * @param params Values to bind to the placeholders, in order
     * @return Number of rows affected
     */
    fun execute(sql: String, vararg params: Any?): Int {
        val conn = requireConnection()
        return conn.prepareStatement(sql).use { stmt ->
            bindParams(stmt, params)
            stmt.executeUpdate()
        }
    }

    /**
     * Execute a query and map each row to a domain object.
     *
     * The mapper function receives a ResultSet positioned at each row.
     * It should extract values and return a domain object.
     *
     * Example:
     * ```kotlin
     * val exchanges = db.query(
     *     "SELECT * FROM chat_exchanges WHERE project_id = ?",
     *     "abc123"
     * ) { rs ->
     *     ChatExchangeRow(
     *         id = rs.getString("id"),
     *         projectId = rs.getString("project_id"),
     *         // ...
     *     )
     * }
     * ```
     *
     * @param sql The SELECT statement with ? placeholders
     * @param params Values to bind to the placeholders
     * @param mapper Function to convert each ResultSet row to T
     * @return List of mapped objects
     */
    fun <T> query(sql: String, vararg params: Any?, mapper: (ResultSet) -> T): List<T> {
        val conn = requireConnection()
        return conn.prepareStatement(sql).use { stmt ->
            bindParams(stmt, params)
            val rs = stmt.executeQuery()
            val results = mutableListOf<T>()
            while (rs.next()) {
                results.add(mapper(rs))
            }
            results
        }
    }

    /**
     * Execute a query expecting exactly zero or one result.
     *
     * @param sql The SELECT statement
     * @param params Values to bind
     * @param mapper Function to convert the row to T
     * @return The mapped object, or null if no rows
     */
    fun <T> queryOne(sql: String, vararg params: Any?, mapper: (ResultSet) -> T): T? {
        val conn = requireConnection()
        return conn.prepareStatement(sql).use { stmt ->
            bindParams(stmt, params)
            val rs = stmt.executeQuery()
            if (rs.next()) mapper(rs) else null
        }
    }

    /**
     * Execute a query that returns a single scalar value (e.g., COUNT, MAX).
     *
     * @param sql The SELECT statement
     * @param params Values to bind
     * @return The integer result, or 0 if no rows
     */
    fun queryScalar(sql: String, vararg params: Any?): Int {
        val conn = requireConnection()
        return conn.prepareStatement(sql).use { stmt ->
            bindParams(stmt, params)
            val rs = stmt.executeQuery()
            if (rs.next()) rs.getInt(1) else 0
        }
    }

    /**
     * Execute multiple statements within a single transaction.
     *
     * If any statement fails, the entire transaction is rolled back.
     * This is important for operations that must be atomic (e.g., saving
     * an exchange and updating its project's last_opened_at).
     *
     * @param block The operations to execute within the transaction
     */
    fun <T> inTransaction(block: () -> T): T {
        val conn = requireConnection()
        val prevAutoCommit = conn.autoCommit
        conn.autoCommit = false
        try {
            val result = block()
            conn.commit()
            return result
        } catch (e: Exception) {
            conn.rollback()
            throw e
        } finally {
            conn.autoCommit = prevAutoCommit
        }
    }

    // ==================== Schema Creation ====================

    /**
     * Create all 15 tables and their indexes.
     *
     * Uses CREATE TABLE IF NOT EXISTS so this is safe to call on every startup.
     * All tables are created upfront — even those for features not yet built
     * (summaries, bookmarks). This avoids schema migrations later.
     *
     * The schema matches Storage_schema_complete.md v3 exactly.
     */
    private fun createSchema(conn: Connection) {
        try {
            conn.createStatement().use { stmt ->

                // DEV MODE: Wipe and recreate tables when schema is evolving.
                // This is safe because JSONL is the source of truth — the database
                // can always be rebuilt. In production, Dev.isEnabled is false and
                // this block is skipped entirely.
                if (DevMode.isEnabled()) {
                    Dev.info(log, "db.schema.dev_wipe", "reason" to "dev mode active, ensuring clean schema")
                    stmt.execute("PRAGMA foreign_keys = OFF")
                    stmt.execute("DROP TABLE IF EXISTS code_bookmark_tags")  // must drop before code_bookmarks
                    stmt.execute("DROP TABLE IF EXISTS bookmark_tags")
                    stmt.execute("DROP TABLE IF EXISTS bookmarks")
                    stmt.execute("DROP TABLE IF EXISTS collections")
                    stmt.execute("DROP TABLE IF EXISTS summary_config")
                    stmt.execute("DROP TABLE IF EXISTS summary_hierarchy")
                    stmt.execute("DROP TABLE IF EXISTS summaries")
                    stmt.execute("DROP TABLE IF EXISTS code_bookmarks")
                    stmt.execute("DROP TABLE IF EXISTS metrics")
                    stmt.execute("DROP TABLE IF EXISTS chat_exchanges")
                    stmt.execute("DROP TABLE IF EXISTS conversations")
                    stmt.execute("DROP TABLE IF EXISTS code_elements")
                    stmt.execute("DROP TABLE IF EXISTS storage_config")
                    stmt.execute("DROP TABLE IF EXISTS projects")
                    stmt.execute("DROP TABLE IF EXISTS open_tabs")
                    stmt.execute("DROP TABLE IF EXISTS assistant_profile_summary")
                }

                // ── Table 1: projects ──
                stmt.execute("""
                CREATE TABLE IF NOT EXISTS projects (
                    id             TEXT PRIMARY KEY,
                    name           TEXT NOT NULL,
                    path           TEXT NOT NULL,
                    created_at     TEXT NOT NULL,
                    last_opened_at TEXT NOT NULL,
                    is_active      INTEGER NOT NULL DEFAULT 1
                )
            """.trimIndent())

                // ── Table: conversations ──
                // Groups exchanges into named multi-turn dialogues.
                // Enables chat tabs, Library conversation view, and history replay.
                stmt.execute("""
                CREATE TABLE IF NOT EXISTS conversations (
                    id                          TEXT PRIMARY KEY,
                    project_id                  TEXT NOT NULL REFERENCES projects(id),
                    title                       TEXT,
                    created_at                  TEXT NOT NULL,
                    updated_at                  TEXT NOT NULL,
                    provider_id                 TEXT,
                    model_id                    TEXT,
                    turn_count                  INTEGER NOT NULL DEFAULT 0,
                    is_active                   INTEGER NOT NULL DEFAULT 1,
                    max_history_tokens_override INTEGER,
                    -- Soft delete: 0 = visible, 1 = hidden from all views.
                    -- Stage 2 (JSONL purge) is a separate Settings page action.
                    deleted                     INTEGER NOT NULL DEFAULT 0
                )
            """.trimIndent())
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_conv_project  ON conversations(project_id)")
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_conv_updated  ON conversations(updated_at)")
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_conv_active   ON conversations(is_active)")
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_conv_deleted  ON conversations(deleted)")

                // ── Table 2: chat_exchanges ──
                // Phase 4: Replaced single `tokens_used INTEGER` with three columns
                // Phase 4: Token breakdown (prompt, completion, total)
                // Phase 4A: assistant_text (lazy-cached parsed response)
                //           derived metadata (code blocks, topics, file paths, duplicate hash)
                //           IDE context (active file, language, module, branch)
                //
                // This enables:
                // - Per-exchange cost visibility in the UI
                // - Chat vs summary token aggregation (via GROUP BY purpose)
                // - Token budget tracking for enterprise customers
                //
                // Note: flags and labels are stored as comma-separated strings.
                // These columns are not in the original schema doc but are needed
                // to support the existing updateMetadata(flags, labels) API from v1.
                // They're lightweight enough to keep as CSV strings rather than
                // a separate join table (unlike bookmark_tags which are user-facing).
                stmt.execute("""
                CREATE TABLE IF NOT EXISTS chat_exchanges (
                    id                TEXT PRIMARY KEY,
                    project_id        TEXT NOT NULL REFERENCES projects(id),
                    conversation_id   TEXT REFERENCES conversations(id),
                    provider_id       TEXT NOT NULL,
                    model_id          TEXT NOT NULL,
                    purpose           TEXT NOT NULL,
                    timestamp         TEXT NOT NULL,
                    prompt_tokens     INTEGER,
                    completion_tokens INTEGER,
                    total_tokens      INTEGER,
                    user_prompt       TEXT,
                    assistant_text    TEXT,
                    flags             TEXT NOT NULL DEFAULT '',
                    labels            TEXT NOT NULL DEFAULT '',
                    raw_file          TEXT NOT NULL,
                    raw_available     INTEGER NOT NULL DEFAULT 1,
                    -- Derived metadata (populated at ingest or lazily)
                    has_code_block    INTEGER,
                    code_languages    TEXT,
                    has_command        INTEGER,
                    has_stacktrace    INTEGER,
                    detected_topics   TEXT,
                    file_paths        TEXT,
                    duplicate_hash    TEXT,
                    -- IDE context (captured at chat time)
                    context_file      TEXT,
                    context_language  TEXT,
                    context_module    TEXT,
                    context_branch    TEXT,
                    open_files        TEXT,
                    -- Exchange-level starring (favourites)
                    is_starred        INTEGER NOT NULL DEFAULT 0,
                    -- Soft delete: 0 = visible, 1 = hidden from all views.
                    -- The exchange record itself is never fully removed — metrics,
                    -- token counts, and timestamps are preserved. Only the text
                    -- content (user_prompt, assistant_text) can be purged later
                    -- via the Settings page "Consolidate deleted items" action.
                    deleted           INTEGER NOT NULL DEFAULT 0
                )
            """.trimIndent())
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_chat_project ON chat_exchanges(project_id)")
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_chat_conversation ON chat_exchanges(conversation_id)")
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_chat_timestamp ON chat_exchanges(timestamp)")
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_chat_purpose ON chat_exchanges(purpose)")
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_chat_duplicate ON chat_exchanges(duplicate_hash)")
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_chat_has_code ON chat_exchanges(has_code_block)")
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_chat_starred  ON chat_exchanges(is_starred)")
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_chat_deleted  ON chat_exchanges(deleted)")

                // ── Table 3: code_elements ──
                stmt.execute("""
                CREATE TABLE IF NOT EXISTS code_elements (
                    id           TEXT PRIMARY KEY,
                    project_id   TEXT NOT NULL REFERENCES projects(id),
                    file_path    TEXT NOT NULL,
                    element_type TEXT NOT NULL,
                    element_name TEXT NOT NULL,
                    parent_id    TEXT REFERENCES code_elements(id),
                    content_hash TEXT,
                    last_seen_at TEXT NOT NULL
                )
            """.trimIndent())
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_ce_project ON code_elements(project_id)")
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_ce_file ON code_elements(file_path)")
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_ce_parent ON code_elements(parent_id)")
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_ce_hash ON code_elements(content_hash)")

                // ── Table 4: summaries ──
                stmt.execute("""
                CREATE TABLE IF NOT EXISTS summaries (
                    id                  TEXT PRIMARY KEY,
                    code_element_id     TEXT NOT NULL REFERENCES code_elements(id),
                    project_id          TEXT NOT NULL REFERENCES projects(id),
                    content_hash_at_gen TEXT NOT NULL,
                    is_stale            INTEGER NOT NULL DEFAULT 0,
                    provider_id         TEXT NOT NULL,
                    model_id            TEXT NOT NULL,
                    prompt_version      INTEGER NOT NULL DEFAULT 1,
                    tokens_used         INTEGER,
                    generated_at        TEXT NOT NULL,
                    raw_file            TEXT NOT NULL,
                    raw_available       INTEGER NOT NULL DEFAULT 1
                )
            """.trimIndent())
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_sum_project ON summaries(project_id)")
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_sum_element ON summaries(code_element_id)")
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_sum_stale ON summaries(is_stale)")
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_sum_hash ON summaries(content_hash_at_gen)")

                // ── Table 5: summary_hierarchy ──
                stmt.execute("""
                CREATE TABLE IF NOT EXISTS summary_hierarchy (
                    id                INTEGER PRIMARY KEY AUTOINCREMENT,
                    parent_summary_id TEXT NOT NULL REFERENCES summaries(id),
                    child_summary_id  TEXT NOT NULL REFERENCES summaries(id),
                    UNIQUE(parent_summary_id, child_summary_id)
                )
            """.trimIndent())
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_sh_parent ON summary_hierarchy(parent_summary_id)")
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_sh_child ON summary_hierarchy(child_summary_id)")

                // ── Table 6: summary_config ──
                stmt.execute("""
                CREATE TABLE IF NOT EXISTS summary_config (
                    id                     INTEGER PRIMARY KEY AUTOINCREMENT,
                    project_id             TEXT NOT NULL UNIQUE REFERENCES projects(id),
                    mode                   TEXT NOT NULL DEFAULT 'OFF',
                    enabled                INTEGER NOT NULL DEFAULT 0,
                    max_tokens_per_session INTEGER,
                    tokens_used_session    INTEGER NOT NULL DEFAULT 0,
                    complexity_threshold   INTEGER,
                    include_patterns       TEXT,
                    exclude_patterns       TEXT,
                    min_file_lines         INTEGER
                )
            """.trimIndent())

                // ── Table 7: collections ──
                stmt.execute("""
                CREATE TABLE IF NOT EXISTS collections (
                    id         TEXT PRIMARY KEY,
                    project_id TEXT REFERENCES projects(id),
                    name       TEXT NOT NULL,
                    icon       TEXT,
                    created_at TEXT NOT NULL,
                    sort_order INTEGER NOT NULL DEFAULT 0
                )
            """.trimIndent())
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_coll_project ON collections(project_id)")

                // ── Table 8: bookmarks ──
                // Polymorphic link between any bookmarkable item and a collection.
                // source_type discriminates between item kinds — no separate join
                // tables needed. Adding new source types is additive (no migration).
                //
                // source_type values:
                //   'CHAT'         — a single chat exchange (assistant response)
                //   'SUMMARY'      — a generated code summary
                //   'CONVERSATION' — an entire conversation (all its exchanges)
                //   'CODE_SNIPPET' — a saved code block (references code_bookmarks.id)
                //
                // Note: no FK on source_id by design. Items of different source_types
                // live in different tables, so a single FK column can't enforce all of
                // them. Referential integrity is maintained at the application layer.
                stmt.execute("""
                CREATE TABLE IF NOT EXISTS bookmarks (
                    id             TEXT PRIMARY KEY,
                    collection_id  TEXT NOT NULL REFERENCES collections(id) ON DELETE CASCADE,
                    source_type    TEXT NOT NULL CHECK(source_type IN ('CHAT', 'SUMMARY', 'CONVERSATION', 'CODE_SNIPPET')),
                    source_id      TEXT NOT NULL,
                    cached_content TEXT,
                    note           TEXT,
                    added_at       TEXT NOT NULL,
                    sort_order     INTEGER NOT NULL DEFAULT 0
                )
            """.trimIndent())
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_bm_collection ON bookmarks(collection_id)")
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_bm_source     ON bookmarks(source_type, source_id)")
                // Prevents the same item appearing twice in the same collection.
                // Allows the same item to be in multiple collections (different rows).
                stmt.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_bm_unique_membership ON bookmarks(collection_id, source_type, source_id)")

                // ── Table 9: bookmark_tags ──
                stmt.execute("""
                CREATE TABLE IF NOT EXISTS bookmark_tags (
                    id          INTEGER PRIMARY KEY AUTOINCREMENT,
                    bookmark_id TEXT NOT NULL REFERENCES bookmarks(id) ON DELETE CASCADE,
                    tag         TEXT NOT NULL,
                    UNIQUE(bookmark_id, tag)
                )
            """.trimIndent())
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_bt_bookmark ON bookmark_tags(bookmark_id)")
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_bt_tag      ON bookmark_tags(tag)")

                // ── Table 10: code_bookmark_tags ──
                // User-created tags on code snippet bookmarks (code_bookmarks rows).
                //
                // Mirrors bookmark_tags exactly — same join-table pattern, same reasons:
                // indexed tag queries, no false-match LIKE scans, consistent filtering
                // across both bookmark types in the Library filter panel.
                //
                // These are user-created marks — NOT plugin-derived metadata.
                // Plugin-detected language lives in code_bookmarks.language (rebuildable).
                // These tags are NOT rebuildable: if the DB is lost, they are lost.
                // That is intentional — they represent user intent, not derived content.
                stmt.execute("""
                CREATE TABLE IF NOT EXISTS code_bookmark_tags (
                    code_bookmark_id TEXT NOT NULL REFERENCES code_bookmarks(id) ON DELETE CASCADE,
                    tag              TEXT NOT NULL,
                    UNIQUE(code_bookmark_id, tag)
                )
            """.trimIndent())
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_cbt_bookmark ON code_bookmark_tags(code_bookmark_id)")
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_cbt_tag      ON code_bookmark_tags(tag)")

                // ── Table 12: storage_config ──
                stmt.execute("""
                CREATE TABLE IF NOT EXISTS storage_config (
                    id           INTEGER PRIMARY KEY AUTOINCREMENT,
                    config_key   TEXT NOT NULL UNIQUE,
                    config_value TEXT
                )
            """.trimIndent())

                // ── Table 13: open_tabs (R4) ──
                // Persists the frontend's open tab state for restore on IDE restart.
                // Controlled by the `keep_tabs` setting in storage_config.
                // Uses full-replace strategy: TabStateService deletes all rows for
                // the project and re-inserts on every save.
                stmt.execute("""
                CREATE TABLE IF NOT EXISTS open_tabs (
                    id              TEXT PRIMARY KEY,
                    project_id      TEXT NOT NULL REFERENCES projects(id),
                    conversation_id TEXT REFERENCES conversations(id),
                    title           TEXT NOT NULL DEFAULT 'New Chat',
                    tab_order       INTEGER NOT NULL DEFAULT 0,
                    is_active       INTEGER NOT NULL DEFAULT 0,
                    scroll_position INTEGER NOT NULL DEFAULT 0,
                    provider_id     TEXT,
                    bypass_mode     TEXT NOT NULL DEFAULT 'FULL',
                    -- Dial position: 'OFF' | 'FULL' | 'SELECTIVE' (dial perspective,
                    -- not backend bypass perspective). Default 'FULL' = context ON.
                    -- See Item 7 action plan §Phase 1 for naming semantics.
                    selective_level INTEGER NOT NULL DEFAULT 2,
                    -- Active lever position when bypass_mode = 'SELECTIVE'.
                    -- 0=Minimal, 1=Partial, 2=Full (all detectors). Default 2 = safest.
                    -- Ignored when bypass_mode != 'SELECTIVE'.
                    created_at      TEXT NOT NULL
                )
            """.trimIndent())
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_ot_project ON open_tabs(project_id)")

                // ── Table 13: code_bookmarks ──
                // Saved code blocks from chat responses. Each row represents one
                // code block the user explicitly bookmarked (the "ribbon" action).
                //
                // block_index identifies which code block within the exchange (0-based).
                // The actual content is cached here for fast display without JSONL reads.
                // The exchange record is never fully deleted, so block_index is always
                // rebuildable from the exchange's raw JSONL if needed.
                //
                // User-created tags live in code_bookmark_tags (join table) — NOT here.
                // Plugin-detected language lives in the `language` column (rebuildable).
                //
                // conversation_id: denormalized, no FK by design.
                // Reason: code_bookmarks are sacred — they survive exchange soft-deletes.
                // A hard FK would either cascade-delete the snippet (unacceptable) or
                // block conversation deletion (also unacceptable). Same pattern as
                // metrics.conversation_id — a label, not a referential constraint.
                // If the conversation is gone, the snippet still shows with a
                // "conversation no longer available" note in the UI.
                stmt.execute("""
                CREATE TABLE IF NOT EXISTS code_bookmarks (
                    id              TEXT PRIMARY KEY,
                    project_id      TEXT NOT NULL REFERENCES projects(id),
                    exchange_id     TEXT NOT NULL REFERENCES chat_exchanges(id),
                    conversation_id TEXT,
                    block_index     INTEGER NOT NULL,
                    language        TEXT,
                    content         TEXT NOT NULL,
                    title           TEXT,
                    created_at      TEXT NOT NULL,
                    -- Soft delete: 0 = visible, 1 = hidden.
                    -- Snippets are "sacred" — only deletable from the Bookmarks
                    -- section with explicit confirmation. Soft-deleted snippets
                    -- still appear in the Bookmarks section (marked) until the
                    -- user explicitly purges them via Settings > Consolidate.
                    deleted         INTEGER NOT NULL DEFAULT 0,
                    UNIQUE(exchange_id, block_index)
                )
            """.trimIndent())
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_cb_project      ON code_bookmarks(project_id)")
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_cb_exchange     ON code_bookmarks(exchange_id)")
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_cb_conversation ON code_bookmarks(conversation_id)")
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_cb_language     ON code_bookmarks(language)")
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_cb_deleted      ON code_bookmarks(deleted)")

                // ── Table 14: metrics ──
                // Per-exchange token usage metrics for the Metrics Module.
                // Separate from chat_exchanges because:
                // - Metrics have their own lifecycle (aggregation queries, cleanup policies)
                // - Summary exchanges also produce metrics — same structure, different purpose
                // - Company tier adds user/project dimensions that don't belong on chat_exchanges
                // - Keeps chat_exchanges lean for chat-specific queries
                //
                // All aggregations are computed on read from this table —
                // never stored separately. Any new dimension is immediately
                // available without migration.
                //
                // Company tier columns (user_id, project_id) are present but
                // null at Individual tier launch. Populated when Phase 6 ships.
                stmt.execute("""
                CREATE TABLE IF NOT EXISTS metrics (
                    exchange_id         TEXT PRIMARY KEY,
                    conversation_id     TEXT NOT NULL,
                    provider_id         TEXT NOT NULL,
                    provider_label      TEXT NOT NULL,
                    protocol            TEXT NOT NULL,
                    model               TEXT,
                    prompt_tokens       INTEGER,
                    completion_tokens   INTEGER,
                    total_tokens        INTEGER,
                    context_window_size INTEGER,
                    purpose             TEXT NOT NULL DEFAULT 'CHAT',
                    timestamp_ms        INTEGER NOT NULL,
                    response_time_ms    INTEGER,
                    user_id             TEXT,
                    project_id          TEXT,
                    FOREIGN KEY (exchange_id) REFERENCES chat_exchanges(id)
                )
            """.trimIndent())

                // Aggregation query indexes — one per common GROUP BY / WHERE dimension.
                // These make dashboard queries (per-provider, per-model, per-day, etc.)
                // fast without full table scans.
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_metrics_conversation ON metrics(conversation_id)")
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_metrics_provider     ON metrics(provider_id)")
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_metrics_model        ON metrics(model)")
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_metrics_timestamp    ON metrics(timestamp_ms)")
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_metrics_purpose      ON metrics(purpose)")
                // Company tier index — unused at Individual launch but exists so
                // we never need to add it later (adding indexes to large tables is slow).
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_metrics_user         ON metrics(user_id)")

                // ── Table 15: assistant_profile_summary ──
                // Stores the summarized version of the user's assistant profile.
                //
                // The assistant profile is GLOBAL (not per-project) — this is a user-level
                // personality configuration that applies across all projects.
                //
                // At launch: single row with id='active'.
                // Future (multi-profile): id becomes a real key, one row per profile.
                //
                // No project_id column — deliberately omitted because the assistant profile
                // is not project-scoped. If per-project overrides are added later, that would
                // be a separate table or a new column.
                //
                // source_hash enables change detection: compare against the hash of the
                // current profile.yaml content to skip unnecessary re-summarization.
                stmt.execute("""
                CREATE TABLE IF NOT EXISTS assistant_profile_summary (
                    id                  TEXT PRIMARY KEY,
                    source_hash         TEXT NOT NULL,
                    summary_text        TEXT NOT NULL,
                    provider_id         TEXT NOT NULL,
                    model_id            TEXT NOT NULL,
                    full_tokens         INTEGER,
                    summary_tokens      INTEGER,
                    generated_at        TEXT NOT NULL,
                    raw_file            TEXT NOT NULL,
                    raw_available       INTEGER NOT NULL DEFAULT 1
                )
            """.trimIndent())

                // Insert default config values if they don't exist yet.
                // Using INSERT OR IGNORE so this is safe on every startup.
                val defaults = listOf(
                    "retention_policy" to "FOREVER",
                    "cleanup_suggestion_threshold_mb" to "2048",
                    "last_cleanup_suggestion" to null,
                    "storage_root_path" to StorageConfig.DEFAULT_ROOT.absolutePath,
                    // keep_tabs moved to TabSettingsState XML (PersistentStateComponent)
                    // ── Assistant Profile (Block 4) ──────────────────────────
                    "assistant_profile_enabled" to "true",
                    "assistant_profile_fallback_full" to "false",
                    "assistant_profile_file_path" to null  // null = use default path ({storage-root}/profile/profile.yaml)
                )
                val insertConfig = conn.prepareStatement(
                    "INSERT OR IGNORE INTO storage_config (config_key, config_value) VALUES (?, ?)"
                )
                for ((key, value) in defaults) {
                    insertConfig.setString(1, key)
                    if (value != null) insertConfig.setString(2, value) else insertConfig.setNull(2, java.sql.Types.VARCHAR)
                    insertConfig.executeUpdate()
                }
                insertConfig.close()
            }

            Dev.info(log, "db.schema.created", "tables" to 15)
        } catch (e: org.sqlite.SQLiteException) {
            if (e.message?.contains("FOREIGN KEY") == true) {
                Dev.error(log, "db.schema.fk_error", e, "line" to "createSchema")
                diagnoseForeignKeyViolations(conn)
            }
            throw e
        }
    }

    // ==================== Internal ====================

    /**
     * Get the active connection, throwing if not open.
     */
    private fun requireConnection(): Connection {
        return connection
            ?: throw IllegalStateException("Database not open. Call open() before performing operations.")
    }

    /**
     * Bind parameters to a prepared statement.
     *
     * Handles type mapping from Kotlin types to JDBC types:
     * - String → setString
     * - Int/Long → setLong (SQLite stores all integers as 64-bit)
     * - null → setNull
     * - Boolean → setInt (0 or 1, since SQLite has no boolean type)
     *
     * @param stmt The prepared statement
     * @param params The parameter values in order
     */
    private fun bindParams(stmt: PreparedStatement, params: Array<out Any?>) {
        params.forEachIndexed { index, param ->
            val jdbcIndex = index + 1  // JDBC is 1-based
            when (param) {
                null -> stmt.setNull(jdbcIndex, java.sql.Types.VARCHAR)
                is String -> stmt.setString(jdbcIndex, param)
                is Int -> stmt.setLong(jdbcIndex, param.toLong())
                is Long -> stmt.setLong(jdbcIndex, param)
                is Boolean -> stmt.setInt(jdbcIndex, if (param) 1 else 0)
                is Double -> stmt.setDouble(jdbcIndex, param)
                else -> stmt.setString(jdbcIndex, param.toString())
            }
        }
    }

    /**
     * Diagnose foreign key violations with full detail.
     *
     * SQLite's default FK error message is useless ("FOREIGN KEY constraint failed").
     * This runs PRAGMA foreign_key_check which returns:
     *   - table: the child table with the bad reference
     *   - rowid: the offending row
     *   - parent: the parent table it tried to reference
     *   - fkid: the FK constraint index
     *
     * Call this in catch blocks when you get SQLITE_CONSTRAINT_FOREIGNKEY.
     */
    private fun diagnoseForeignKeyViolations(conn: Connection) {
        try {
            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery("PRAGMA foreign_key_check")
                val violations = mutableListOf<String>()
                while (rs.next()) {
                    val table = rs.getString(1)
                    val rowid = rs.getLong(2)
                    val parent = rs.getString(3)
                    val fkid = rs.getInt(4)
                    violations.add("table=$table rowid=$rowid parent=$parent fkid=$fkid")
                }
                rs.close()

                if (violations.isEmpty()) {
                    Dev.error(
                        log, "db.fk_check.no_violations_found", null,
                        "note" to "FK error thrown but PRAGMA check found nothing — possibly a DROP order issue"
                    )
                } else {
                    for (v in violations) {
                        Dev.error(log, "db.fk_violation", null, "detail" to v)
                    }
                }
            }
        } catch (e: Exception) {
            Dev.error(log, "db.fk_check.failed", e)
        }
    }
}