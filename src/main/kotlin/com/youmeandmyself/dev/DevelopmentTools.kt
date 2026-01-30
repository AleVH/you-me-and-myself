// File: src/main/kotlin/com/youmeandmyself/dev/DevelopmentTools.kt
package com.youmeandmyself.dev

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.vfs.VirtualFile
import kotlin.system.measureTimeMillis

/**
 * One place for consistent logging + tiny helpers.
 * - Always use IntelliJ's Logger (no slf4j, no extra bindings).
 * - Structured, key=value style lines with stable tags.
 * - Small text utilities to keep logs readable.
 */
object Dev {

    /** Get a per-class IntelliJ logger (category = FQN). */
    fun logger(owner: Class<*>): Logger = Logger.getInstance(owner)

    /** Log an INFO line: tag + key=value pairs. */
    fun info(log: Logger, tag: String, vararg kv: Pair<String, Any?>) {
        log.info("$tag ${fmt(kv)}")
    }

    /** Log a WARN line, with optional throwable. */
    fun warn(log: Logger, tag: String, t: Throwable? = null, vararg kv: Pair<String, Any?>) {
        if (t != null) log.warn("$tag ${fmt(kv)}", t) else log.warn("$tag ${fmt(kv)}")
    }

    /** Log an ERROR line, with optional throwable. */
    fun error(log: Logger, tag: String, t: Throwable? = null, vararg kv: Pair<String, Any?>) {
        if (t != null) log.error("$tag ${fmt(kv)}", t) else log.error("$tag ${fmt(kv)}")
    }

    /**
     * Time a block and log its duration as INFO with the given tag.
     * Usage:
     *   Dev.timed(log, "syn.http", "path" to path) {
     *       // your work...
     *   }
     */
    fun <T> timed(log: Logger, tag: String, vararg kv: Pair<String, Any?>, block: () -> T): T {
        // Run the block, then compute duration without relying on lateinit (works for nullable T as well).
        val t0 = System.nanoTime()
        val out = block()
        val durMs = (System.nanoTime() - t0) / 1_000_000
        log.info("$tag ${fmt(kv)} durMs=$durMs")
        return out
    }

    /** Short preview for user text (single-line, trimmed). */
    fun preview(text: String?, max: Int = 80): String =
        text.orEmpty().replace("\n", " ").replace("\r", " ").let {
            if (it.length <= max) it else it.substring(0, max) + "…"
        }

    /** Safe file name helper for logs. */
    fun fileName(vf: VirtualFile?): String = vf?.name ?: "NONE"

    // ---------- internal ----------

    private fun fmt(kv: Array<out Pair<String, Any?>>): String =
        kv.joinToString(" ") { (k, v) -> "$k=${valStr(v)}" }.trim()

    private fun valStr(v: Any?): String = when (v) {
        null -> "null"
        is String -> quote(v)
        is CharSequence -> quote(v.toString())
        else -> v.toString()
    }

    private fun quote(s: String): String {
        val oneLine = s.replace("\n", "\\n").replace("\r", "\\r")
        return if (oneLine.any { it.isWhitespace() || it == '=' }) "\"$oneLine\"" else oneLine
    }
}
