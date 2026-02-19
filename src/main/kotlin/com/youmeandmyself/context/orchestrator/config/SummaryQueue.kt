package com.youmeandmyself.context.orchestrator.config

import com.intellij.openapi.diagnostic.Logger
import com.youmeandmyself.dev.Dev
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.PriorityBlockingQueue

/**
 * Priority queue for summary requests with cancel and observe capabilities.
 *
 * ## Purpose
 *
 * Replaces the simple `pendingSynopsis: Set<String>` in SummaryStore with a
 * proper queue that supports ordering, cancellation, and UI observation.
 *
 * ## Priority Ordering
 *
 * Lower priority number = processed first.
 * - Negative values: user-requested summaries (ON_DEMAND) — always first
 * - Zero: normal priority (default)
 * - Positive values: background/warmup — processed last
 *
 * This ensures user-triggered summaries never wait behind background work.
 *
 * ## Thread Safety
 *
 * Uses PriorityBlockingQueue for the queue and CopyOnWriteArrayList for listeners.
 * All public methods are safe to call from any thread.
 *
 * ## Deduplication
 *
 * A file can only appear once in the queue. Attempting to enqueue a file that's
 * already queued returns false. If a re-enqueue is needed (e.g., after content change),
 * cancel the existing request first.
 */
class SummaryQueue {

    private val log = Logger.getInstance(SummaryQueue::class.java)

    /**
     * The priority queue. Ordered by SummaryRequest.priority (ascending),
     * then by enqueuedAt (FIFO within same priority).
     */
    private val queue = PriorityBlockingQueue<SummaryRequest>(
        16, // initial capacity
        compareBy<SummaryRequest> { it.priority }
            .thenBy { it.enqueuedAt }
    )

    /**
     * Fast lookup set for deduplication. Contains file paths currently in the queue.
     * Must be kept in sync with the queue.
     */
    private val pathIndex = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** Listeners notified when the queue changes. */
    private val listeners = CopyOnWriteArrayList<QueueChangeListener>()

    // ==================== Enqueue / Dequeue ====================

    /**
     * Add a summary request to the queue.
     *
     * @param request The summary request to enqueue
     * @return true if enqueued, false if the file is already in the queue
     */
    fun enqueue(request: SummaryRequest): Boolean {
        // Deduplication: skip if already queued
        if (!pathIndex.add(request.filePath)) {
            Dev.info(log, "queue.duplicate_skipped", "path" to request.filePath)
            return false
        }

        queue.add(request)

        Dev.info(log, "queue.enqueued",
            "path" to request.filePath,
            "priority" to request.priority,
            "trigger" to request.triggeredBy.name,
            "queueSize" to queue.size
        )

        notifyListeners()
        return true
    }

    /**
     * Take the highest-priority request from the queue.
     *
     * Returns null if the queue is empty. Non-blocking.
     * The returned request is removed from the queue and path index.
     */
    fun poll(): SummaryRequest? {
        val request = queue.poll() ?: return null
        pathIndex.remove(request.filePath)

        Dev.info(log, "queue.polled",
            "path" to request.filePath,
            "remainingSize" to queue.size
        )

        notifyListeners()
        return request
    }

    /**
     * Peek at the highest-priority request without removing it.
     */
    fun peek(): SummaryRequest? = queue.peek()

    // ==================== Cancel ====================

    /**
     * Cancel a specific file's summary request.
     *
     * @param filePath Absolute file path to cancel
     * @return true if a request was found and cancelled
     */
    fun cancel(filePath: String): Boolean {
        val removed = pathIndex.remove(filePath)
        if (removed) {
            // Remove from the actual queue
            queue.removeIf { it.filePath == filePath }

            Dev.info(log, "queue.cancelled",
                "path" to filePath,
                "remainingSize" to queue.size
            )

            notifyListeners()
        }
        return removed
    }

    /**
     * Cancel all pending summary requests.
     *
     * @return Number of requests that were cancelled
     */
    fun cancelAll(): Int {
        val count = queue.size
        queue.clear()
        pathIndex.clear()

        Dev.info(log, "queue.cancelled_all", "count" to count)

        if (count > 0) {
            notifyListeners()
        }
        return count
    }

    // ==================== Status ====================

    /**
     * Number of requests currently in the queue.
     */
    fun size(): Int = queue.size

    /**
     * Check if the queue is empty.
     */
    fun isEmpty(): Boolean = queue.isEmpty()

    /**
     * Check if a specific file is in the queue.
     */
    fun contains(filePath: String): Boolean = pathIndex.contains(filePath)

    /**
     * Get all file paths currently in the queue, ordered by priority.
     *
     * Returns a snapshot — the queue may change after this call.
     */
    fun pendingPaths(): List<String> {
        return queue.toList()
            .sortedWith(compareBy<SummaryRequest> { it.priority }.thenBy { it.enqueuedAt })
            .map { it.filePath }
    }

    /**
     * Get a snapshot of all requests in the queue, ordered by priority.
     *
     * Useful for the status UI to show queue contents with full detail.
     */
    fun snapshot(): List<SummaryRequest> {
        return queue.toList()
            .sortedWith(compareBy<SummaryRequest> { it.priority }.thenBy { it.enqueuedAt })
    }

    /**
     * Get a summary string for logging/status display.
     */
    fun statusSummary(): String {
        val items = snapshot()
        if (items.isEmpty()) return "Queue empty"

        val byTrigger = items.groupBy { it.triggeredBy }
        return buildString {
            append("${items.size} items queued: ")
            byTrigger.entries.joinTo(this) { (trigger, reqs) ->
                "${reqs.size} ${trigger.name.lowercase()}"
            }
        }
    }

    // ==================== Listeners ====================

    /**
     * Register a listener for queue changes.
     *
     * Called on enqueue, poll, cancel, and cancelAll.
     * Keep implementations fast — called on the modifying thread.
     */
    fun addQueueChangeListener(listener: QueueChangeListener) {
        listeners.add(listener)
    }

    /**
     * Remove a previously registered listener.
     */
    fun removeQueueChangeListener(listener: QueueChangeListener) {
        listeners.remove(listener)
    }

    private fun notifyListeners() {
        val currentSize = queue.size
        listeners.forEach { listener ->
            try {
                listener.onQueueChanged(currentSize)
            } catch (e: Throwable) {
                Dev.warn(log, "queue.listener_error", e)
            }
        }
    }
}

/**
 * Listener for queue state changes.
 */
fun interface QueueChangeListener {
    /**
     * Called when the queue changes (item added, removed, or cancelled).
     * @param newSize Current queue size after the change
     */
    fun onQueueChanged(newSize: Int)
}