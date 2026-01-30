// File: src/main/kotlin/com/youmeandmyself/context/orchestrator/VfsSummaryWatcher.kt
package com.youmeandmyself.context.orchestrator

import com.intellij.openapi.components.Service
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
import java.io.InputStream
import java.security.MessageDigest

/**
 * Project-level VFS watcher that keeps SummaryStore in sync with file changes.
 *
 * Responsibilities:
 *  - Listen to VFS events (create, content change, move/rename, delete).
 *  - Compute a stable content hash for changed files (cheap SHA-256 streaming).
 *  - Notify SummaryStore about changes so synopses/header samples refresh lazily.
 *
 * Notes:
 *  - We purposefully do NOT trigger immediate re-summarization here.
 *    SummaryStore will refresh on-demand (lazy) via getOrEnqueueSynopsis().
 *  - We ignore directories, symlinks, and binary/very large files heuristically.
 */
@Service(Service.Level.PROJECT)
class VfsSummaryWatcher(
    private val project: Project,
    private val summaryStore: SummaryStore
) {

    // We keep a connection so it’s disposed with the project automatically.
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
                                // treat move/rename like content-stable but path-changed;
                                // we’ll mark stale on the new path to be safe
                                onMovedOrRenamed(vf)
                            }
                            is VFileDeleteEvent -> {
                                val path = e.path
                                onDeleted(path)
                            }
                        }
                    } catch (_: Throwable) {
                        // Swallow to avoid breaking the VFS pipeline; optionally log
                    }
                }
            }
        })
    }

    private fun onCreated(vf: VirtualFile) {
        if (!isProcessable(vf)) return
        val (hash, lang) = computeHashAndLang(vf) ?: return
        // Mark “stale but usable” and let SummaryStore lazily refresh on demand
        summaryStore.onHashChange(vf.path, hash)
        // We DO NOT auto enqueue here to avoid storms during mass imports
    }

    private fun onContentChanged(vf: VirtualFile) {
        if (!isProcessable(vf)) return
        val (hash, _) = computeHashAndLang(vf) ?: return
        summaryStore.onHashChange(vf.path, hash)
    }

    private fun onMovedOrRenamed(vf: VirtualFile) {
        if (!isProcessable(vf)) return
        val (hash, _) = computeHashAndLang(vf) ?: return
        summaryStore.onHashChange(vf.path, hash)
    }

    private fun onDeleted(path: String) {
        summaryStore.onFileDeleted(path)
    }

    // -------- Helpers --------

    private fun isProcessable(vf: VirtualFile): Boolean {
        if (!vf.isValid || vf.isDirectory) return false
        // Skip very large files (> 5 MB) to avoid heavy hashing on save
        val len = runCatching { vf.length }.getOrDefault(0L)
        if (len <= 0L || len > 5 * 1024 * 1024) return false

        // Cheap binary-ish filter: if file type reports binary, skip
        if (vf.fileType.isBinary) return false

        // Exclusions (match your orchestrator caps/exclusions policy)
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
