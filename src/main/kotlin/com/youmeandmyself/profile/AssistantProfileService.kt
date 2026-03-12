package com.youmeandmyself.profile

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.youmeandmyself.ai.providers.AiProvider
import com.youmeandmyself.ai.providers.ProviderRegistry
import com.youmeandmyself.dev.Dev
import com.youmeandmyself.storage.LocalStorageFacade
import com.youmeandmyself.storage.StorageConfig
import com.youmeandmyself.summary.consumers.AssistantProfileSummarizer
import com.youmeandmyself.summary.pipeline.SummarizationService
import com.youmeandmyself.tier.CompositeTierProvider
import com.youmeandmyself.tier.Feature
import kotlinx.coroutines.*

/**
 * Central service for the Assistant Profile system.
 *
 * ## Responsibilities
 *
 * 1. **Initialization:** Read profile from disk on project open, validate, summarize if needed.
 * 2. **Change detection:** VFS watcher triggers re-validation and re-summarization on file edit.
 * 3. **System prompt injection:** Provides [getSystemPrompt] for ChatOrchestrator to prepend
 *    the summarized profile to every API request.
 * 4. **Provider resolution:** Dedicated summary provider if configured, else active chat provider.
 * 5. **Settings:** Read/write profile-related config through [LocalStorageFacade].
 * 6. **Retry:** Exponential backoff on summarization failure.
 *
 * ## Storage Access
 *
 * All database access goes through [LocalStorageFacade] — this service never writes raw SQL.
 * The facade provides:
 * - [LocalStorageFacade.loadAssistantProfileSummary] / [LocalStorageFacade.saveAssistantProfileSummary]
 * - [LocalStorageFacade.getConfigValue] / [LocalStorageFacade.setConfigValue]
 *
 * ## Lifecycle
 *
 * Registered as a project-level service in plugin.xml. Created when the project opens,
 * disposed when the project closes. The VFS watcher subscription is tied to this service's
 * disposable lifecycle.
 *
 * ## Tier Gating
 *
 * Checks `Feature.ASSISTANT_PROFILE` via CompositeTierProvider. If the user's tier doesn't
 * include this feature, the service initializes but does nothing (getSystemPrompt returns null).
 *
 * ## Multi-Profile Future
 *
 * At launch: one global profile, `id = "active"`, path from storage_config.
 * Future: multiple profiles, each with its own id, path, and summary row.
 * The service would manage a Map<String, AssistantProfileData> and the
 * caller (ChatOrchestrator) would specify which profile to use.
 * For now, everything assumes a single "active" profile.
 *
 * @param project The IntelliJ project context
 */
class AssistantProfileService(
    private val project: Project
) : Disposable {

    private val log = Dev.logger(AssistantProfileService::class.java)

    // ── Dependencies ─────────────────────────────────────────────────────

    /**
     * Single reference to the storage facade. Resolved lazily because the
     * facade may not be initialized when this service is constructed.
     *
     * All DB access in this service goes through this reference — no
     * repeated getInstance() calls, no raw SQL.
     */
    private val storage by lazy { LocalStorageFacade.getInstance(project) }

    // ── State ────────────────────────────────────────────────────────────

    /** The currently loaded and validated profile. Null if not loaded or invalid. */
    @Volatile
    private var currentProfile: AssistantProfileData? = null

    /** The current profile summary text. Null if not yet summarized or summarization failed. */
    @Volatile
    private var currentSummary: String? = null

    /** Content hash of the last successfully summarized profile. */
    @Volatile
    private var lastSummarizedHash: String? = null

    /** Whether the service has completed initial load. */
    @Volatile
    private var initialized: Boolean = false

    /** Coroutine scope for background summarization work. Cancelled on dispose. */
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ── Retry state ──────────────────────────────────────────────────────

    /** Number of consecutive summarization failures. Reset on success. */
    @Volatile
    private var retryCount: Int = 0

    /** Maximum retry attempts before entering "summarization unavailable" state. */
    private val maxRetries = 5

    /** Base delay for exponential backoff (ms). Doubles each retry. */
    private val baseRetryDelayMs = 2_000L

    // ── Public API ───────────────────────────────────────────────────────

    /**
     * Get the summarized assistant profile for system prompt injection.
     *
     * Called by ChatOrchestrator on every chat message. Returns the cached
     * summary or null if:
     * - Feature not available for the user's tier
     * - Profile not loaded or invalid
     * - Summary not yet generated
     * - Profile is disabled in settings
     * - Summarization failed and fallback is not enabled
     *
     * ## Performance
     *
     * This is a hot path (called on every message). Returns a cached string —
     * no disk I/O, no DB query, no computation. The summary is refreshed in
     * the background when the profile file changes.
     *
     * @return The summarized profile text to prepend to system prompt, or null
     */
    fun getSystemPrompt(): String? {
        if (!isFeatureAvailable()) return null
        if (!isEnabled()) return null

        val summary = currentSummary
        if (summary != null && summary.isNotBlank()) {
            return summary
        }

        // No summary available — check fallback setting
        if (isFallbackEnabled()) {
            val profile = currentProfile
            if (profile != null && profile.sections.isNotEmpty()) {
                Dev.info(log, "assistant_profile.fallback_to_full",
                    "sectionCount" to profile.sections.size
                )
                return profile.toFullText()
            }
        }

        return null
    }

    /**
     * Initialize the profile system.
     *
     * Called on project open (from the service's initialization or an explicit trigger).
     * Reads the profile from disk, validates it, loads any existing summary from SQLite,
     * and triggers re-summarization if the content has changed.
     *
     * This method is safe to call multiple times — it's idempotent.
     */
    fun initialize() {
        if (!isFeatureAvailable()) {
            Dev.info(log, "assistant_profile.init.feature_unavailable")
            initialized = true
            return
        }

        try {
            val profilePath = getProfilePath()

            // Generate default template if no file exists
            AssistantProfileFileManager.generateDefaultTemplate(profilePath)

            // Read and validate
            val yamlContent = AssistantProfileFileManager.readProfile(profilePath)
            if (yamlContent == null) {
                Dev.info(log, "assistant_profile.init.no_file", "path" to profilePath)
                initialized = true
                return
            }

            val validationResult = AssistantProfileValidator.validate(yamlContent)
            if (!validationResult.isValid) {
                Dev.warn(log, "assistant_profile.init.validation_failed", null,
                    "errors" to validationResult.errors.joinToString("; ")
                )
                currentProfile = null
                initialized = true
                return
            }

            currentProfile = validationResult.profile
            val contentHash = AssistantProfileFileManager.contentHash(yamlContent)

            // Load existing summary from SQLite
            loadSummaryFromDb()

            // Check if re-summarization is needed
            if (lastSummarizedHash != contentHash) {
                Dev.info(log, "assistant_profile.init.summary_stale",
                    "storedHash" to (lastSummarizedHash ?: "none"),
                    "currentHash" to contentHash
                )
                triggerSummarization(contentHash)
            } else {
                Dev.info(log, "assistant_profile.init.summary_current")
            }

            // Set up file watcher
            registerFileWatcher(profilePath)

            initialized = true
            Dev.info(log, "assistant_profile.init.complete",
                "hasSummary" to (currentSummary != null),
                "sectionCount" to (currentProfile?.sections?.size ?: 0)
            )
        } catch (e: Exception) {
            Dev.error(log, "assistant_profile.init.failed", e)
            initialized = true
        }
    }

    /**
     * Force re-summarization of the current profile.
     *
     * Called from the settings UI when the user clicks "Re-summarize".
     * Bypasses the hash check — always re-summarizes.
     */
    fun forceSummarize() {
        val profile = currentProfile ?: return
        if (profile.sections.isEmpty()) return

        val profilePath = getProfilePath()
        val yamlContent = AssistantProfileFileManager.readProfile(profilePath) ?: return
        val contentHash = AssistantProfileFileManager.contentHash(yamlContent)

        retryCount = 0  // Reset retry state
        triggerSummarization(contentHash)
    }

    /**
     * Check if the assistant profile feature is enabled in settings.
     */
    fun isEnabled(): Boolean {
        return try {
            storage.getConfigValue("assistant_profile_enabled") != "false"
        } catch (e: Exception) {
            Dev.warn(log, "assistant_profile.config.enabled_read_failed", e)
            true // Default to enabled if config can't be read
        }
    }

    /**
     * Check if the fallback-to-full-profile setting is enabled.
     */
    fun isFallbackEnabled(): Boolean {
        return try {
            storage.getConfigValue("assistant_profile_fallback_full") == "true"
        } catch (e: Exception) {
            Dev.warn(log, "assistant_profile.config.fallback_read_failed", e)
            false // Default to disabled if config can't be read
        }
    }

    /**
     * Update a profile setting through the facade.
     *
     * Called by AssistantProfileConfigurable when the user saves settings.
     * All writes go through the facade — no direct SQL.
     *
     * @param key The config key (e.g., "assistant_profile_enabled")
     * @param value The config value
     */
    fun updateSetting(key: String, value: String?) {
        try {
            storage.setConfigValue(key, value)
        } catch (e: Exception) {
            Dev.error(log, "assistant_profile.config.write_failed", e, "key" to key)
        }
    }

    /**
     * Get the current profile data (for the settings UI).
     */
    fun getCurrentProfile(): AssistantProfileData? = currentProfile

    /**
     * Get the current summary text (for the settings UI to display).
     */
    fun getCurrentSummary(): String? = currentSummary

    /**
     * Check if initialization is complete.
     */
    fun isInitialized(): Boolean = initialized

    // ── Private: Summarization ───────────────────────────────────────────

    /**
     * Trigger background summarization of the current profile.
     *
     * Runs in the service's coroutine scope so it doesn't block the caller.
     * Uses exponential backoff on failure.
     *
     * @param contentHash The hash of the current profile content
     */
    private fun triggerSummarization(contentHash: String) {
        val profile = currentProfile ?: return

        serviceScope.launch {
            try {
                val provider = resolveSummarizationProvider()
                if (provider == null) {
                    Dev.warn(log, "assistant_profile.summarize.no_provider", null)
                    return@launch
                }

                val service = SummarizationService.getInstance(project)
                val profileText = profile.toFullText()

                val result = AssistantProfileSummarizer.summarize(
                    provider = provider,
                    profileText = profileText,
                    service = service
                )

                if (result.isError) {
                    handleSummarizationFailure(contentHash, result.errorMessage)
                    return@launch
                }

                // Success — update cached state and persist to SQLite via facade
                currentSummary = result.summaryText
                lastSummarizedHash = contentHash
                retryCount = 0

                storage.saveAssistantProfileSummary(
                    summaryText = result.summaryText,
                    sourceHash = contentHash,
                    providerId = provider.id,
                    modelId = provider.displayName, // Same known bug as elsewhere — stores display name
                    fullTokens = result.tokenUsage?.promptTokens,
                    summaryTokens = result.tokenUsage?.completionTokens,
                    rawFile = result.exchangeId  // Points to the JSONL exchange entry
                )

                Dev.info(log, "assistant_profile.summarize.success",
                    "summaryLength" to result.summaryText.length,
                    "exchangeId" to result.exchangeId
                )

            } catch (e: Exception) {
                Dev.error(log, "assistant_profile.summarize.exception", e)
                handleSummarizationFailure(contentHash, e.message)
            }
        }
    }

    /**
     * Handle summarization failure with exponential backoff retry.
     *
     * After [maxRetries] consecutive failures, enters "summarization unavailable"
     * state and stops retrying. Resumes when the profile file changes or the
     * user triggers manual re-summarization.
     */
    private fun handleSummarizationFailure(contentHash: String, errorMessage: String?) {
        retryCount++

        if (retryCount > maxRetries) {
            Dev.warn(log, "assistant_profile.summarize.max_retries_reached", null,
                "retryCount" to retryCount,
                "lastError" to (errorMessage ?: "unknown")
            )
            return
        }

        val delayMs = baseRetryDelayMs * (1L shl (retryCount - 1).coerceAtMost(10))

        Dev.info(log, "assistant_profile.summarize.retry_scheduled",
            "retryCount" to retryCount,
            "delayMs" to delayMs,
            "lastError" to (errorMessage ?: "unknown")
        )

        serviceScope.launch {
            delay(delayMs)
            triggerSummarization(contentHash)
        }
    }

    // ── Private: Provider Resolution ─────────────────────────────────────

    /**
     * Resolve which AI provider to use for profile summarization.
     *
     * Priority:
     * 1. Dedicated summarization provider (if configured in AiProfilesState)
     * 2. Active chat provider (fallback)
     *
     * The user sees which provider is used on the settings page.
     *
     * ## Multi-Profile Future
     *
     * When multiple assistant profiles exist, each could potentially use a
     * different provider. For now, there's one global resolution.
     */
    private fun resolveSummarizationProvider(): AiProvider? {
        // Check for a dedicated summarization provider
        // TODO (multi-profile): Per-profile provider override
        val summaryProvider = ProviderRegistry.selectedSummaryProvider(project)
        if (summaryProvider != null) {
            Dev.info(log, "assistant_profile.provider.dedicated",
                "providerId" to summaryProvider.id
            )
            return summaryProvider
        }

        // Fall back to active chat provider
        val chatProvider = ProviderRegistry.selectedChatProvider(project)
        if (chatProvider != null) {
            Dev.info(log, "assistant_profile.provider.chat_fallback",
                "providerId" to chatProvider.id
            )
        } else {
            Dev.warn(log, "assistant_profile.provider.none_available", null)
        }

        return chatProvider
    }

    // ── Private: SQLite ──────────────────────────────────────────────────

    /**
     * Load the existing profile summary from SQLite via the facade.
     *
     * Populates [currentSummary] and [lastSummarizedHash] from the stored row.
     * If no row exists, both remain null.
     */
    private fun loadSummaryFromDb() {
        try {
            val row = storage.loadAssistantProfileSummary()
            if (row != null) {
                currentSummary = row.summaryText
                lastSummarizedHash = row.sourceHash

                Dev.info(log, "assistant_profile.db.loaded",
                    "summaryLength" to row.summaryText.length,
                    "sourceHash" to row.sourceHash
                )
            } else {
                Dev.info(log, "assistant_profile.db.no_existing_summary")
            }
        } catch (e: Exception) {
            Dev.warn(log, "assistant_profile.db.load_failed", e)
        }
    }

    // ── Private: File Watching ────────────────────────────────────────────

    /**
     * Register a VFS listener for profile file changes.
     *
     * When the profile file is modified (either through the IDE editor or externally),
     * triggers re-validation and re-summarization.
     *
     * The listener subscription is tied to this service's Disposable lifecycle —
     * automatically cleaned up when the project closes.
     */
    private fun registerFileWatcher(profilePath: String) {
        val listener = AssistantProfileFileManager.createFileWatcher(profilePath) {
            onProfileFileChanged()
        }

        project.messageBus
            .connect(this)  // 'this' as Disposable — auto-cleanup on dispose
            .subscribe(VirtualFileManager.VFS_CHANGES, listener)

        Dev.info(log, "assistant_profile.watcher.registered", "path" to profilePath)
    }

    /**
     * Handle profile file modification.
     *
     * Re-reads, re-validates, and re-summarizes if the content hash has changed.
     * Called from the VFS watcher on the EDT — kicks off summarization in background.
     */
    private fun onProfileFileChanged() {
        try {
            val profilePath = getProfilePath()
            val yamlContent = AssistantProfileFileManager.readProfile(profilePath) ?: return

            val validationResult = AssistantProfileValidator.validate(yamlContent)
            if (!validationResult.isValid) {
                Dev.warn(log, "assistant_profile.file_changed.validation_failed", null,
                    "errors" to validationResult.errors.joinToString("; ")
                )
                currentProfile = null
                currentSummary = null
                return
            }

            currentProfile = validationResult.profile
            val contentHash = AssistantProfileFileManager.contentHash(yamlContent)

            if (lastSummarizedHash != contentHash) {
                Dev.info(log, "assistant_profile.file_changed.content_changed",
                    "oldHash" to (lastSummarizedHash ?: "none"),
                    "newHash" to contentHash
                )
                retryCount = 0  // Reset retry state for new content
                triggerSummarization(contentHash)
            } else {
                Dev.info(log, "assistant_profile.file_changed.content_unchanged")
            }
        } catch (e: Exception) {
            Dev.error(log, "assistant_profile.file_changed.error", e)
        }
    }

    // ── Private: Settings ────────────────────────────────────────────────

    /**
     * Get the profile file path from settings.
     *
     * Falls back to the default path if not configured.
     */
    fun getProfilePath(): String {
        val configured = try {
            storage.getConfigValue("assistant_profile_file_path")
        } catch (e: Exception) {
            Dev.warn(log, "assistant_profile.config.path_read_failed", e)
            null
        }
        if (!configured.isNullOrBlank()) return configured

        return AssistantProfileFileManager.defaultProfilePath(StorageConfig.DEFAULT_ROOT)
    }

    // ── Private: Tier ────────────────────────────────────────────────────

    /**
     * Check if the Assistant Profile feature is available for the user's tier.
     */
    private fun isFeatureAvailable(): Boolean {
        return try {
            val tierProvider = CompositeTierProvider.getInstance()
            tierProvider.canUse(Feature.ASSISTANT_PROFILE)
        } catch (e: Exception) {
            Dev.warn(log, "assistant_profile.tier_check_failed", e)
            false
        }
    }

    // ── Lifecycle ────────────────────────────────────────────────────────

    override fun dispose() {
        serviceScope.cancel("AssistantProfileService disposed")
        Dev.info(log, "assistant_profile.disposed")
    }

    companion object {
        /**
         * Get the AssistantProfileService instance for a project.
         *
         * @param project The IntelliJ project
         * @return The service instance (created by the IntelliJ service container)
         */
        fun getInstance(project: Project): AssistantProfileService {
            return project.getService(AssistantProfileService::class.java)
        }
    }
}