// File: src/main/kotlin/com/youmeandmyself/context/orchestrator/config/SummaryConfigService.kt
package com.youmeandmyself.context.orchestrator.config

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.youmeandmyself.dev.Dev
import com.youmeandmyself.storage.LocalStorageFacade
import com.youmeandmyself.storage.model.ExchangePurpose
import java.nio.file.FileSystems
import java.nio.file.PathMatcher
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Project-level service that owns all summary configuration logic.
 *
 * ## Purpose
 *
 * Single source of truth for summary settings. Replaces the scattered
 * summary-related fields in PluginSettingState with a centralized service
 * backed by the summary_config SQLite table.
 *
 * ## Single Point of Contact
 *
 * All database access goes through [LocalStorageFacade] — this service never
 * touches DatabaseHelper directly. This maintains the architectural principle
 * of a single point of contact between modules, preventing hidden dependencies
 * and making future storage changes easier.
 *
 * ## Responsibilities
 *
 * - Read/write summary_config via LocalStorageFacade
 * - Enforce budget caps (check before allowing a summarization request)
 * - Evaluate scope filters (include/exclude patterns, min file lines)
 * - Track session token spend (in-memory counter)
 * - Provide dry-run evaluation ("would this file be summarized? why/why not?")
 * - Notify listeners when config changes
 *
 * ## Thread Safety
 *
 * Config is stored in an AtomicReference. Session token counter uses AtomicInteger.
 * Listeners are in a CopyOnWriteArrayList. All public methods are safe to call
 * from any thread.
 *
 * ## Persistence Strategy
 *
 * - Config writes to SQLite on apply() (user clicks OK/Apply in settings)
 * - Kill switch writes immediately (safety-critical)
 * - Session token counter is in-memory only — resets on project open
 * - LocalStorageFacade handles write serialization via its Mutex
 *
 * ## Initialization
 *
 * Lazy — first call to getConfig() triggers load from storage.
 * Before initialization, safe defaults are returned (OFF, disabled, dry-run).
 *
 * @param project The IntelliJ project this service belongs to
 */
@Service(Service.Level.PROJECT)
class SummaryConfigService(private val project: Project) : Disposable {

    private val log = Logger.getInstance(SummaryConfigService::class.java)

    /** The current config, held in memory for fast reads. */
    private val currentConfig = AtomicReference<SummaryConfig>(SummaryConfig())

    /** Session token counter. Resets to 0 on project open. */
    private val sessionTokenCounter = AtomicInteger(0)

    /** Whether we've loaded from storage yet. */
    @Volatile
    private var initialized = false

    /** Listeners notified when config changes. */
    private val listeners = CopyOnWriteArrayList<ConfigChangeListener>()

    /** Compiled glob matchers, cached to avoid recompiling on every check. */
    @Volatile
    private var includeMatchers: List<PathMatcher> = emptyList()
    @Volatile
    private var excludeMatchers: List<PathMatcher> = emptyList()

    // ==================== Config Access ====================

    /**
     * Get the current summary configuration.
     *
     * Returns an immutable snapshot safe to read from any thread.
     * Session token counter is injected for consistency.
     * Triggers lazy initialization on first call.
     */
    fun getConfig(): SummaryConfig {
        ensureInitialized()
        return currentConfig.get().copy(tokensUsedSession = sessionTokenCounter.get())
    }

    /**
     * Update the configuration in memory.
     *
     * IMPORTANT: Does NOT persist to SQLite. Call apply() to persist.
     * Exception: kill switch (setEnabled) persists immediately.
     */
    fun updateConfig(newConfig: SummaryConfig) {
        ensureInitialized()
        val oldConfig = currentConfig.getAndSet(newConfig)
        rebuildMatchers(newConfig)

        Dev.info(log, "config.updated",
            "mode" to newConfig.mode.name,
            "enabled" to newConfig.enabled,
            "dryRun" to newConfig.dryRun,
            "budget" to (newConfig.maxTokensPerSession?.toString() ?: "unlimited")
        )

        notifyListeners(oldConfig, newConfig)
    }

    /**
     * Persist the current in-memory config to SQLite via LocalStorageFacade.
     *
     * Called when the user clicks OK/Apply in the settings UI.
     */
    fun apply() {
        val config = getConfig()
        persistConfig(config)

        Dev.info(log, "config.applied",
            "mode" to config.mode.name,
            "enabled" to config.enabled
        )
    }

    // ==================== Mode ====================

    fun getMode(): SummaryMode {
        ensureInitialized()
        return currentConfig.get().mode
    }

    fun setMode(mode: SummaryMode) {
        ensureInitialized()
        val old = currentConfig.get()
        val new = old.copy(mode = mode)
        currentConfig.set(new)
        notifyListeners(old, new)
    }

    // ==================== Kill Switch ====================

    fun isEnabled(): Boolean {
        ensureInitialized()
        return currentConfig.get().enabled
    }

    /**
     * Toggle the kill switch.
     *
     * Unlike other config changes, this persists IMMEDIATELY.
     * When a user hits the kill switch, they mean NOW.
     */
    fun setEnabled(enabled: Boolean) {
        ensureInitialized()
        val old = currentConfig.get()
        val new = old.copy(enabled = enabled)
        currentConfig.set(new)

        // Persist immediately — safety-critical
        persistConfig(new)

        Dev.info(log, "config.kill_switch",
            "enabled" to enabled,
            "immediate_persist" to true
        )

        notifyListeners(old, new)
    }

    // ==================== Budget ====================

    fun getRemainingBudget(): Int? {
        val config = currentConfig.get()
        return config.maxTokensPerSession?.let {
            (it - sessionTokenCounter.get()).coerceAtLeast(0)
        }
    }

    fun recordTokensUsed(tokens: Int, purpose: ExchangePurpose) {
        val newTotal = sessionTokenCounter.addAndGet(tokens)

        Dev.info(log, "config.tokens_recorded",
            "tokens" to tokens,
            "purpose" to purpose.name,
            "sessionTotal" to newTotal,
            "budget" to (currentConfig.get().maxTokensPerSession?.toString() ?: "unlimited")
        )
    }

    fun getTokensUsedThisSession(): Int = sessionTokenCounter.get()

    fun resetSessionCounter() {
        val old = sessionTokenCounter.getAndSet(0)
        Dev.info(log, "config.session_reset", "previousTotal" to old)
    }

    // ==================== Scope Evaluation ====================

    /**
     * Evaluate whether a file should be summarized given current config.
     *
     * Checks in order: kill switch, mode, budget, min lines, exclude patterns,
     * include patterns. Does NOT check dry-run — caller handles that.
     */
    fun shouldSummarize(filePath: String, lineCount: Int): ScopeDecision {
        ensureInitialized()
        val config = getConfig()

        if (!config.enabled) return ScopeDecision.deniedByKillSwitch()
        if (config.mode == SummaryMode.OFF) return ScopeDecision.deniedByMode(config.mode)
        if (config.mode == SummaryMode.SUMMARIZE_PATH) {
            return ScopeDecision.denied("Summarize Path mode is not yet available. Coming in a future update.")
        }

        if (!config.hasBudget) {
            return ScopeDecision.deniedByBudget(
                config.tokensUsedSession, config.maxTokensPerSession ?: 0
            )
        }

        val minLines = config.minFileLines
        if (minLines != null && lineCount < minLines) {
            return ScopeDecision.deniedByMinLines(lineCount, minLines)
        }

        val excludeMatch = matchesAnyPattern(filePath, excludeMatchers, currentConfig.get().excludePatterns)
        if (excludeMatch != null) return ScopeDecision.deniedByPattern(excludeMatch)

        if (includeMatchers.isNotEmpty()) {
            val includeMatch = matchesAnyPattern(filePath, includeMatchers, currentConfig.get().includePatterns)
            if (includeMatch == null) return ScopeDecision.denied("File does not match any include pattern")
            return ScopeDecision.allowed("Matches include pattern: $includeMatch")
        }

        return ScopeDecision.allowed("File passes all scope checks")
    }

    // ==================== Dry Run ====================

    fun evaluateDryRun(filePath: String, lineCount: Int): DryRunResult {
        val config = getConfig()
        val scopeDecision = shouldSummarize(filePath, lineCount)
        val estimatedTokens = (lineCount * 40 / 4) // ~40 chars/line, ~4 chars/token

        val providerInfo = try {
            val provider = com.youmeandmyself.ai.providers.ProviderRegistry.selectedSummaryProvider(project)
            provider?.let { "${it.displayName} (${it.id})" }
        } catch (_: Throwable) { null }

        return DryRunResult(
            wouldSummarize = scopeDecision.allowed,
            reason = if (config.dryRun && scopeDecision.allowed) {
                "Dry-run mode: would summarize (${scopeDecision.reason})"
            } else {
                scopeDecision.reason
            },
            estimatedTokens = estimatedTokens,
            budgetRemaining = config.remainingBudget,
            matchedPattern = scopeDecision.matchedPattern,
            providerInfo = providerInfo ?: "No summary provider configured"
        )
    }

    fun isDryRun(): Boolean {
        ensureInitialized()
        return currentConfig.get().dryRun
    }

    // ==================== Listeners ====================

    fun addConfigChangeListener(listener: ConfigChangeListener) { listeners.add(listener) }
    fun removeConfigChangeListener(listener: ConfigChangeListener) { listeners.remove(listener) }

    // ==================== Lazy Initialization ====================

    /**
     * Load config from storage on first access.
     *
     * If LocalStorageFacade hasn't initialized yet or no config row exists,
     * safe defaults are used (OFF, disabled, dry-run).
     */
    private fun ensureInitialized() {
        if (initialized) return

        synchronized(this) {
            if (initialized) return

            try {
                loadFromStorage()
            } catch (e: Throwable) {
                Dev.warn(log, "config.init_failed", e, "project" to project.name)
            }

            initialized = true

            Dev.info(log, "config.initialized",
                "project" to project.name,
                "mode" to currentConfig.get().mode.name,
                "enabled" to currentConfig.get().enabled,
                "dryRun" to currentConfig.get().dryRun
            )
        }
    }

    // ==================== Storage I/O via LocalStorageFacade ====================

    /**
     * Load config through LocalStorageFacade — the single point of contact for storage.
     */
    private fun loadFromStorage() {
        val facade = getFacade() ?: run {
            Dev.info(log, "config.facade_not_ready", "project" to project.name)
            return
        }

        val projectId = getProjectId()
        val loaded = facade.loadSummaryConfig(projectId)

        if (loaded != null) {
            currentConfig.set(loaded)
            rebuildMatchers(loaded)
            Dev.info(log, "config.loaded",
                "project" to project.name,
                "mode" to loaded.mode.name,
                "enabled" to loaded.enabled
            )
        } else {
            // No config exists yet — persist defaults so the row exists
            val defaults = SummaryConfig()
            facade.saveSummaryConfig(projectId, defaults)
            Dev.info(log, "config.defaults_inserted", "project" to project.name)
        }
    }

    /**
     * Persist config through LocalStorageFacade.
     */
    private fun persistConfig(config: SummaryConfig) {
        val facade = getFacade() ?: run {
            Dev.warn(log, "config.persist_skipped_no_facade", null, "project" to project.name)
            return
        }

        facade.saveSummaryConfig(getProjectId(), config)
    }

    /**
     * Get the LocalStorageFacade instance.
     * Returns null if not available yet (project still loading).
     */
    private fun getFacade(): LocalStorageFacade? {
        return try {
            LocalStorageFacade.getInstance(project)
        } catch (_: Throwable) {
            null
        }
    }

    private fun getProjectId(): String = project.basePath ?: project.name

    // ==================== Pattern Matching ====================

    private fun rebuildMatchers(config: SummaryConfig) {
        includeMatchers = compilePatterns(config.includePatterns)
        excludeMatchers = compilePatterns(config.excludePatterns)
    }

    private fun compilePatterns(patterns: List<String>): List<PathMatcher> {
        return patterns.mapNotNull { pattern ->
            try {
                FileSystems.getDefault().getPathMatcher("glob:$pattern")
            } catch (e: Exception) {
                Dev.warn(log, "config.bad_pattern", e, "pattern" to pattern)
                null
            }
        }
    }

    private fun matchesAnyPattern(
        filePath: String,
        matchers: List<PathMatcher>,
        originalPatterns: List<String>
    ): String? {
        if (matchers.isEmpty()) return null
        val path = java.nio.file.Path.of(filePath)
        val fileName = path.fileName ?: return null

        matchers.forEachIndexed { index, matcher ->
            if (matcher.matches(fileName) || matcher.matches(path)) {
                return originalPatterns.getOrNull(index)
            }
        }
        return null
    }

    // ==================== Lifecycle ====================

    override fun dispose() {
        listeners.clear()
        Dev.info(log, "config.disposed", "project" to project.name)
    }

    private fun notifyListeners(oldConfig: SummaryConfig, newConfig: SummaryConfig) {
        listeners.forEach { listener ->
            try { listener.onConfigChanged(oldConfig, newConfig) }
            catch (e: Throwable) { Dev.warn(log, "config.listener_error", e) }
        }
    }

    companion object {
        fun getInstance(project: Project): SummaryConfigService =
            project.getService(SummaryConfigService::class.java)

        val DEFAULT_EXCLUDE_PATTERNS = listOf(
            "*Test*", "*Spec*", "*/test/*", "*/tests/*",
            "*/build/*", "*/out/*", "*/dist/*",
            "*/node_modules/*", "*/vendor/*",
            "*/.git/*", "*/.idea/*",
            "*.min.js", "*.min.css", "*.generated.*"
        )
    }
}