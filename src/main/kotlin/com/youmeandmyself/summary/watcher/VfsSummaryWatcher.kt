package com.youmeandmyself.summary.watcher

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.util.messages.MessageBusConnection
import com.youmeandmyself.summary.config.SummaryConfigService
import com.youmeandmyself.summary.config.SummaryMode
import com.youmeandmyself.summary.cache.SummaryCache
import com.youmeandmyself.summary.pipeline.SummaryPipeline
import com.youmeandmyself.summary.structure.CodeStructureProviderFactory
import com.youmeandmyself.summary.structure.DetectionScope
import com.youmeandmyself.dev.Dev
import java.io.InputStream
import java.security.MessageDigest

/**
 * Project-level VFS watcher that keeps summary cache and pipeline in sync with file changes.
 *
 * ## Responsibilities
 *
 * - Listen to VFS events (create, content change, move/rename, delete)
 * - Compute a stable content hash for changed files (cheap SHA-256 streaming)
 * - Notify SummaryCache and SummaryPipeline about changes so synopses refresh lazily
 *
 * ## Phase 3 Changes
 *
 * Now checks [SummaryConfigService] before notifying SummaryPipeline:
 * - Hash changes are ALWAYS tracked (staleness detection is free, no API call)
 * - File deletions are ALWAYS handled (cache cleanup is free)
 * - But we only log/track — actual summarization decisions are made by SummaryPipeline
 * when it checks config via evaluateAndEnqueue()
 *
 * ## Design Note
 *
 * We purposefully do NOT trigger immediate re-summarization here.
 * SummaryPipeline will refresh on-demand (lazy) via getOrEnqueueSynopsis(),
 * which checks config (mode, budget, scope, dry-run) before doing anything.
 */
@Service(Service.Level.PROJECT)
class VfsSummaryWatcher(
    private val project: Project,
) {
    private val cache: SummaryCache by lazy {
        SummaryCache.getInstance(project)
    }

    private val pipeline: SummaryPipeline by lazy {
        SummaryPipeline.getInstance(project)
    }

    private val log = Logger.getInstance(VfsSummaryWatcher::class.java)

    /** Lazy reference to config service. */
    private val configService: SummaryConfigService by lazy {
        SummaryConfigService.getInstance(project)
    }

    /** Lazy reference to structure provider factory — for element-level hash comparison. */
    private val structureFactory: CodeStructureProviderFactory by lazy {
        CodeStructureProviderFactory.getInstance(project)
    }

    /** Lazy reference to storage facade — for marking SQLite entries as stale (Fix #4). */
    private val storage: com.youmeandmyself.storage.LocalStorageFacade by lazy {
        com.youmeandmyself.storage.LocalStorageFacade.getInstance(project)
    }

    private val connection: MessageBusConnection = project.messageBus.connect()

    init {
        connection.subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: MutableList<out VFileEvent>) {
                for (e in events) {
                    try {
                        when (e) {
                            is VFileContentChangeEvent -> {
                                val vf = e.file ?: continue
                                onContentChanged(vf)
                            }
                            is VFileCreateEvent -> {
                                val vf = e.file ?: continue
                                onCreated(vf)
                            }
                            is VFileMoveEvent -> {
                                val vf = e.file ?: continue
                                onMovedOrRenamed(vf)
                            }
                            is VFileDeleteEvent -> {
                                val path = e.path
                                onDeleted(path)
                            }
                        }
                    } catch (_: Throwable) {
                        // Swallow to avoid breaking the VFS pipeline
                    }
                }
            }
        })
    }

    private fun onCreated(vf: VirtualFile) {
        if (!isProcessable(vf)) return
        val (hash, _) = computeHashAndLang(vf) ?: return

        // Always track hash changes — staleness detection is free
        pipeline.onFileChanged(vf.path, hash)

        // Log if summarization is active (useful for dry-run visibility)
        if (configService.isEnabled() && configService.getMode() == SummaryMode.SMART_BACKGROUND) {
            Dev.info(log, "vfs.created.tracked",
                "path" to vf.path,
                "mode" to configService.getMode().name
            )
        }
    }

    private fun onContentChanged(vf: VirtualFile) {
        if (!isProcessable(vf)) return
        val (hash, languageId) = computeHashAndLang(vf) ?: return

        // Always track file-level hash changes (cheap, no PSI needed)
        pipeline.onFileChanged(vf.path, hash)

        // Attempt element-level invalidation via PSI
        // If PSI is unavailable (dumb mode, unsupported language), we skip this.
        // The file-level hash change is still recorded above, so staleness is
        // detected on next access via the validation rule.
        performElementLevelInvalidation(vf, languageId)
    }

    private fun onMovedOrRenamed(vf: VirtualFile) {
        if (!isProcessable(vf)) return
        val (hash, _) = computeHashAndLang(vf) ?: return

        // Always track hash changes
        pipeline.onFileChanged(vf.path, hash)
    }

    private fun onDeleted(path: String) {
        // Always handle deletions — cache cleanup is free
        pipeline.onFileDeleted(path)
    }

    // -------- Element-Level Invalidation --------

    /**
     * Compute element-level semantic hashes and update the cache.
     *
     * Uses PSI to detect all elements in the file and compute their
     * semantic fingerprints. Only elements whose fingerprint has changed
     * are invalidated in the cache — others remain READY.
     *
     * If PSI is unavailable (dumb mode, unsupported language), this is a no-op.
     * The file-level hash change is still tracked by the caller, so staleness
     * is caught on next access via the validation rule (every level, every time).
     */
    private fun performElementLevelInvalidation(vf: VirtualFile, languageId: String?) {
        try {
            val provider = structureFactory.get()
            if (provider == null) {
                // PSI unavailable — skip element-level invalidation.
                // File-level hash change was already recorded.
                Dev.info(log, "vfs.element_invalidation.skipped",
                    "path" to vf.path,
                    "reason" to "PSI unavailable (dumb mode or unsupported language)"
                )
                return
            }

            // Detect all elements in the file
            val elements = provider.detectElements(vf, DetectionScope.All)
            if (elements.isEmpty()) {
                Dev.info(log, "vfs.element_invalidation.no_elements",
                    "path" to vf.path
                )
                return
            }

            // Compute semantic hash for each element
            val elementHashes = mutableMapOf<String, String>()
            for (element in elements) {
                val hash = provider.computeElementHash(vf, element)
                elementHashes[element.signature] = hash
            }

            // Update the in-memory cache — only entries whose hash differs are invalidated
            cache.onElementHashChanges(vf.path, elementHashes)

            // Also mark SQLite entries as stale (Fix #4).
            // This ensures consistency between memory and disk. If the IDE crashes
            // (no graceful shutdown), the next warm-up loads is_stale = 1 from SQLite
            // and treats the summary correctly.
            // See: BUG FIX — Element Summary Hash Validation.md, Fix #4
            try {
                // Collect signatures of elements that were actually invalidated
                // (i.e., whose hash changed, not all elements in the file)
                val invalidatedSignatures = mutableListOf<String>()
                for ((signature, newHash) in elementHashes) {
                    val entry = cache.getElementEntries(vf.path)
                    val cached = entry[signature]
                    // If the entry is INVALIDATED, it was just invalidated by onElementHashChanges
                    if (cached != null && cached.state == com.youmeandmyself.summary.cache.SummaryState.INVALIDATED) {
                        invalidatedSignatures.add(signature)
                    }
                }

                if (invalidatedSignatures.isNotEmpty()) {
                    val projectId = storage.resolveProjectId()
                    storage.markElementSummariesStale(projectId, vf.path, invalidatedSignatures)

                    Dev.info(log, "vfs.element_invalidation.sqlite_marked",
                        "path" to vf.path,
                        "count" to invalidatedSignatures.size
                    )
                }
            } catch (e: Throwable) {
                // SQLite marking is a safety net, not critical path.
                // If it fails, the in-memory invalidation still works for this session.
                Dev.warn(log, "vfs.element_invalidation.sqlite_mark_failed", e,
                    "path" to vf.path
                )
            }

            Dev.info(log, "vfs.element_invalidation.completed",
                "path" to vf.path,
                "elementsChecked" to elementHashes.size
            )
        } catch (e: Throwable) {
            // Element-level invalidation is an optimisation, not a requirement.
            // If it fails, the file-level hash change still ensures staleness
            // is detected on next access.
            Dev.warn(log, "vfs.element_invalidation.error", e,
                "path" to vf.path
            )
        }
    }

    // -------- Helpers (unchanged from original) --------

    private fun isProcessable(vf: VirtualFile): Boolean {
        if (!vf.isValid || vf.isDirectory) return false
        val len = runCatching { vf.length }.getOrDefault(0L)
        if (len <= 0L || len > 5 * 1024 * 1024) return false
        if (vf.fileType.isBinary) return false

        val path = vf.path
        if (path.contains("/.git/") ||
            path.contains("/.idea/") ||
            path.contains("/node_modules/") ||
            path.contains("/vendor/") ||
            path.contains("/build/") ||
            path.contains("/out/") ||
            path.contains("/dist/") ||
            path.contains("/target/")
        ) return false

        return true
    }

    private fun computeHashAndLang(vf: VirtualFile): Pair<String, String?>? {
        return try {
            val hash = vf.inputStream.use { digestSha256(it) }
            val languageId = vf.fileType?.name
            hash to languageId
        } catch (_: Throwable) {
            null
        }
    }

    private fun digestSha256(source: InputStream): String {
        val buf = ByteArray(DEFAULT_BUFFER)
        val md = MessageDigest.getInstance("SHA-256")
        while (true) {
            val read = source.read(buf)
            if (read <= 0) break
            md.update(buf, 0, read)
        }
        return md.digest().joinToString("") { b -> "%02x".format(b) }
    }

    private companion object {
        private const val DEFAULT_BUFFER = 32 * 1024
    }
}