// File: src/main/kotlin/com/youmeandmyself/summary/cache/SummaryState.kt
package com.youmeandmyself.summary.cache

/**
 * Lifecycle states for a summary cache entry.
 *
 * ## State Machine Transitions
 *
 * ```
 * MISSING ──────→ GENERATING  (on claim via tryClaim())
 * GENERATING ───→ READY       (on successful generation via completeClaim())
 * GENERATING ───→ MISSING     (on failure or TTL expiry via failClaim())
 * READY ────────→ INVALIDATED (on code change detected by VfsSummaryWatcher)
 * INVALIDATED ──→ GENERATING  (on next request, treated as MISSING for claim purposes)
 * ```
 *
 * ## Design Notes
 *
 * - INVALIDATED entries still return their synopsis (stale is better than nothing)
 *   but are flagged so the prompt can annotate them as "may be outdated."
 * - GENERATING entries prevent duplicate AI calls — subsequent callers wait
 *   for the in-flight result via CompletableFuture broadcast.
 * - TTL on GENERATING prevents permanent locks from crashes/timeouts.
 */
enum class SummaryState {

    /** No summary exists for this file. Any caller may claim and generate. */
    MISSING,

    /** A caller has claimed this file — generation is in progress. */
    GENERATING,

    /** Summary is available and current. */
    READY,

    /**
     * Summary exists but the file's content has changed since summarization.
     *
     * Treated as MISSING for claim purposes on the next request.
     * The stale synopsis is still returned until a fresh one replaces it.
     */
    INVALIDATED
}