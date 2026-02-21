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
 * ## Schema: 10 Tables
 *
 * Created on first run, all tables exist from day one even if some features
 * (summaries, bookmarks) haven't been built yet. This avoids future migrations.
 *
 * 1.  projects          — registry of known projects
 * 2.  chat_exchanges    — metadata about AI conversations (points to JSONL)
 * 3.  code_elements     — map of codebase structure (files, classes, methods)
 * 4.  summaries         — metadata about generated summaries (points to JSONL)
 * 5.  summary_hierarchy — parent/child relationships between summaries
 * 6.  summary_config    — per-project summarization settings
 * 7.  collections       — user-created groups for bookmarks
 * 8.  bookmarks         — saved items (chat or summary) with cached content
 * 9.  bookmark_tags     — tags on bookmarks for filtering
 * 10. storage_config    — global plugin settings (retention, cleanup thresholds)
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
     * Create all 10 tables and their indexes.
     *
     * Uses CREATE TABLE IF NOT EXISTS so this is safe to call on every startup.
     * All tables are created upfront — even those for features not yet built
     * (summaries, bookmarks). This avoids schema migrations later.
     *
     * The schema matches Storage_schema_complete.md v3 exactly.
     */
    private fun createSchema(conn: Connection) {
        conn.createStatement().use { stmt ->

            // DEV MODE: Wipe and recreate tables when schema is evolving.
            // This is safe because JSONL is the source of truth — the database
            // can always be rebuilt. In production, Dev.isEnabled is false and
            // this block is skipped entirely.
            if (DevMode.isEnabled()) {
                Dev.info(log, "db.schema.dev_wipe", "reason" to "dev mode active, ensuring clean schema")
                stmt.execute("DROP TABLE IF EXISTS bookmark_tags")
                stmt.execute("DROP TABLE IF EXISTS bookmarks")
                stmt.execute("DROP TABLE IF EXISTS collections")
                stmt.execute("DROP TABLE IF EXISTS summary_config")
                stmt.execute("DROP TABLE IF EXISTS summary_hierarchy")
                stmt.execute("DROP TABLE IF EXISTS summaries")
                stmt.execute("DROP TABLE IF EXISTS chat_exchanges")
                stmt.execute("DROP TABLE IF EXISTS code_elements")
                stmt.execute("DROP TABLE IF EXISTS storage_config")
                stmt.execute("DROP TABLE IF EXISTS projects")
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
                    provider_id       TEXT NOT NULL,
                    model_id          TEXT NOT NULL,
                    purpose           TEXT NOT NULL,
                    timestamp         TEXT NOT NULL,
                    prompt_tokens     INTEGER,
                    completion_tokens INTEGER,
                    total_tokens      INTEGER,
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
                    open_files        TEXT
                )
            """.trimIndent())
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_chat_project ON chat_exchanges(project_id)")
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_chat_timestamp ON chat_exchanges(timestamp)")
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_chat_purpose ON chat_exchanges(purpose)")
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_chat_duplicate ON chat_exchanges(duplicate_hash)")
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_chat_has_code ON chat_exchanges(has_code_block)")


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
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS bookmarks (
                    id             TEXT PRIMARY KEY,
                    collection_id  TEXT NOT NULL REFERENCES collections(id) ON DELETE CASCADE,
                    source_type    TEXT NOT NULL CHECK(source_type IN ('CHAT', 'SUMMARY')),
                    source_id      TEXT NOT NULL,
                    cached_content TEXT,
                    note           TEXT,
                    added_at       TEXT NOT NULL,
                    sort_order     INTEGER NOT NULL DEFAULT 0
                )
            """.trimIndent())
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_bm_collection ON bookmarks(collection_id)")
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_bm_source ON bookmarks(source_type, source_id)")

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
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_bt_tag ON bookmark_tags(tag)")

            // ── Table 10: storage_config ──
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS storage_config (
                    id           INTEGER PRIMARY KEY AUTOINCREMENT,
                    config_key   TEXT NOT NULL UNIQUE,
                    config_value TEXT
                )
            """.trimIndent())

            // Insert default config values if they don't exist yet.
            // Using INSERT OR IGNORE so this is safe on every startup.
            val defaults = listOf(
                "retention_policy" to "FOREVER",
                "cleanup_suggestion_threshold_mb" to "2048",
                "last_cleanup_suggestion" to null,
                "storage_root_path" to StorageConfig.DEFAULT_ROOT.absolutePath
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

        Dev.info(log, "db.schema.created", "tables" to 10)
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
}