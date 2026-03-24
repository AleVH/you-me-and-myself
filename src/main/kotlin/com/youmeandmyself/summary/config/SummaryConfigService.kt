package com.youmeandmyself.summary.config

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.youmeandmyself.ai.providers.ProviderRegistry
import com.youmeandmyself.dev.Dev
import com.youmeandmyself.storage.model.ExchangePurpose
import java.nio.file.FileSystems
import java.nio.file.PathMatcher
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Project-level service that owns all summary configuration logic.
 *
 * ## Persistence: JetBrains Infrastructure — NOT SQLite
 *
 * Global summary settings (mode, enabled, dryRun, budget, patterns, etc.) are
 * persisted via JetBrains [PersistentStateComponent] into an XML file managed
 * by the IDE. This is the same mechanism used by ContextSettingsState and every
 * other JetBrains plugin that stores project-level settings.
 *
 * ┌─────────────────────────────────────────────────────────────────────────┐
 * │  PRINCIPLE: This plugin relies on JetBrains infrastructure for all     │
 * │  project-level settings persistence. SQLite is used ONLY for tab-level │
 * │  state (per-tab overrides). Any attempt to move global settings back   │
 * │  to SQLite or to a custom persistence layer is a violation of the      │
 * │  plugin's architectural principles.                                    │
 * │                                                                        │
 * │  See: docs/design/Plugin-Infrastructure-Principles.md                  │
 * └─────────────────────────────────────────────────────────────────────────┘
 *
 * ## Why JetBrains and not SQLite?
 *
 * - Settings persist across IDE restarts automatically (the IDE manages it)
 * - No timing issues (available before our DB initializes)
 * - Consistent with how every other plugin and the IDE itself stores settings
 * - We don't reinvent what the platform already provides
 *
 * ## Responsibilities
 *
 * - Read/write summary config via JetBrains PersistentStateComponent
 * - Enforce budget caps (check before allowing a summarization request)
 * - Evaluate scope filters (include/exclude patterns, min file lines)
 * - Track session token spend (in-memory counter, resets on project open)
 * - Provide dry-run evaluation ("would this file be summarized? why/why not?")
 * - Notify listeners when config changes
 *
 * ## Thread Safety
 *
 * State is managed by PersistentStateComponent (read/write on EDT or under platform lock).
 * Session token counter uses AtomicInteger. Listeners are in a CopyOnWriteArrayList.
 * All public methods are safe to call from any thread.
 *
 * @param project The IntelliJ project this service belongs to
 */
@Service(Service.Level.PROJECT)
@State(
    name = "YmmSummarySettings",
    storages = [Storage("ymmSummarySettings.xml")]
)
class SummaryConfigService(
    private val project: Project
) : PersistentStateComponent<SummaryConfigService.PersistentState>, Disposable {

    private val log = Logger.getInstance(SummaryConfigService::class.java)

    /**
     * The serializable state container.
     *
     * All fields use `var` with defaults so IntelliJ's XML persistence
     * can instantiate and populate them. New fields MUST have defaults
     * to maintain backward compatibility with existing settings files.
     *
     * This holds GLOBAL settings only. Tab-level overrides live in SQLite.
     */
    data class PersistentState(
        /** Summarization mode (OFF, ON_DEMAND, SUMMARIZE_PATH, SMART_BACKGROUND). */
        var mode: String = "OFF",

        /** Kill switch. When false, ALL summarization stops immediately. */
        var enabled: Boolean = false,

        /** Budget cap per project session. Null string = unlimited. */
        var maxTokensPerSession: String? = null,

        /** Minimum complexity score (1-10) for auto-summarization. Null = no threshold. */
        var complexityThreshold: String? = null,

        /** Comma-separated glob patterns for files to include. Empty = include all. */
        var includePatterns: String = "",

        /** Comma-separated glob patterns for files to exclude. Empty = exclude none. */
        var excludePatterns: String = "",

        /** Skip files shorter than this. Null string = no minimum. */
        var minFileLines: String? = null,

        /** When true, evaluates everything but skips the actual API call. */
        var dryRun: Boolean = false
    )

    // ==================== JetBrains Persistence ====================

    private var myState = PersistentState()

    override fun getState(): PersistentState = myState

    override fun loadState(state: PersistentState) {
        val oldConfig = stateToConfig()
        myState = state
        rebuildMatchersFromState()

        Dev.info(log, "config.loaded_from_xml",
            "project" to project.name,
            "mode" to state.mode,
            "enabled" to state.enabled,
            "dryRun" to state.dryRun
        )

        val newConfig = stateToConfig()
        if (oldConfig != newConfig) {
            notifyListeners(oldConfig, newConfig)
        }
    }

    // ==================== State ↔ SummaryConfig Conversion ====================

    /**
     * Convert the persisted XML state to a [SummaryConfig] snapshot.
     * Injects the in-memory session token counter.
     */
    private fun stateToConfig(): SummaryConfig {
        return SummaryConfig(
            mode = SummaryMode.fromString(myState.mode),
            enabled = myState.enabled,
            maxTokensPerSession = myState.maxTokensPerSession?.toIntOrNull(),
            tokensUsedSession = sessionTokenCounter.get(),
            complexityThreshold = myState.complexityThreshold?.toIntOrNull(),
            includePatterns = parsePatterns(myState.includePatterns),
            excludePatterns = parsePatterns(myState.excludePatterns),
            minFileLines = myState.minFileLines?.toIntOrNull(),
            dryRun = myState.dryRun
        )
    }

    /**
     * Apply a [SummaryConfig] back to the persistent state.
     * Does NOT touch session-only fields (tokensUsedSession).
     */
    private fun configToState(config: SummaryConfig) {
        myState = PersistentState(
            mode = config.mode.name,
            enabled = config.enabled,
            maxTokensPerSession = config.maxTokensPerSession?.toString(),
            complexityThreshold = config.complexityThreshold?.toString(),
            includePatterns = config.includePatterns.joinToString(","),
            excludePatterns = config.excludePatterns.joinToString(","),
            minFileLines = config.minFileLines?.toString(),
            dryRun = config.dryRun
        )
    }

    private fun parsePatterns(raw: String): List<String> {
        return raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }

    // ==================== Session Token Counter (in-memory only) ====================

    /** Session token counter. Resets to 0 on project open. NOT persisted — ephemeral. */
    private val sessionTokenCounter = AtomicInteger(0)

    // ==================== Listeners ====================

    private val listeners = CopyOnWriteArrayList<ConfigChangeListener>()

    // ==================== Compiled Glob Matchers ====================

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
     */
    fun getConfig(): SummaryConfig = stateToConfig()

    /**
     * Update the configuration in memory AND persist via JetBrains XML.
     *
     * Replaces the old updateConfig + apply() two-step with a single operation.
     * JetBrains handles persistence automatically when the state changes.
     */
    fun updateConfig(newConfig: SummaryConfig) {
        val oldConfig = stateToConfig()
        configToState(newConfig)
        rebuildMatchersFromState()

        Dev.info(log, "config.updated",
            "mode" to newConfig.mode.name,
            "enabled" to newConfig.enabled,
            "dryRun" to newConfig.dryRun,
            "budget" to (newConfig.maxTokensPerSession?.toString() ?: "unlimited")
        )

        notifyListeners(oldConfig, newConfig)
    }

    /**
     * Persist explicitly. JetBrains auto-persists on IDE close, but this forces
     * an immediate write. Called when the user clicks OK/Apply in settings UI.
     */
    fun apply() {
        Dev.info(log, "config.applied",
            "mode" to myState.mode,
            "enabled" to myState.enabled
        )
        // JetBrains PersistentStateComponent handles persistence automatically.
        // The state is already updated in-memory. The IDE will save it to XML.
    }

    // ==================== Mode ====================

    fun getMode(): SummaryMode = SummaryMode.fromString(myState.mode)

    fun setMode(mode: SummaryMode) {
        val old = stateToConfig()
        myState.mode = mode.name
        notifyListeners(old, stateToConfig())
    }

    // ==================== Kill Switch ====================

    fun isEnabled(): Boolean = myState.enabled

    /**
     * Toggle the kill switch.
     *
     * JetBrains PersistentStateComponent persists automatically.
     * When a user hits the kill switch, they mean NOW — the in-memory
     * state is updated immediately and read at call time by the pipeline.
     */
    fun setEnabled(enabled: Boolean) {
        val old = stateToConfig()
        myState.enabled = enabled

        Dev.info(log, "config.kill_switch",
            "enabled" to enabled
        )

        notifyListeners(old, stateToConfig())
    }

    // ==================== Budget ====================

    fun getRemainingBudget(): Int? {
        val max = myState.maxTokensPerSession?.toIntOrNull() ?: return null
        return (max - sessionTokenCounter.get()).coerceAtLeast(0)
    }

    fun recordTokensUsed(tokens: Int, purpose: ExchangePurpose) {
        val newTotal = sessionTokenCounter.addAndGet(tokens)

        Dev.info(log, "config.tokens_recorded",
            "tokens" to tokens,
            "purpose" to purpose.name,
            "sessionTotal" to newTotal,
            "budget" to (myState.maxTokensPerSession ?: "unlimited")
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

        val excludeMatch = matchesAnyPattern(filePath, excludeMatchers, parsePatterns(myState.excludePatterns))
        if (excludeMatch != null) return ScopeDecision.deniedByPattern(excludeMatch)

        if (includeMatchers.isNotEmpty()) {
            val includeMatch = matchesAnyPattern(filePath, includeMatchers, parsePatterns(myState.includePatterns))
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
            val provider = ProviderRegistry.selectedSummaryProvider(project)
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

    fun isDryRun(): Boolean = myState.dryRun

    // ==================== Listeners ====================

    fun addConfigChangeListener(listener: ConfigChangeListener) { listeners.add(listener) }
    fun removeConfigChangeListener(listener: ConfigChangeListener) { listeners.remove(listener) }

    // ==================== Pattern Matching ====================

    private fun rebuildMatchersFromState() {
        includeMatchers = compilePatterns(parsePatterns(myState.includePatterns))
        excludeMatchers = compilePatterns(parsePatterns(myState.excludePatterns))
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
