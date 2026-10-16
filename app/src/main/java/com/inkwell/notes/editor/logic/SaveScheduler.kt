package com.inkwell.notes.editor.logic

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Per-key debounced persistence. Each [submit] replaces the key's pending value and restarts
 * its timer; when the timer fires the latest value is saved. Saves for one key never overlap
 * and always run in submission order, so an older value can never overwrite a newer one.
 *
 * Runs in [scope] (the process scope in production) so saves finish even after the screen
 * that produced them is gone. A failed save keeps its value pending and is retried with
 * back-off up to [maxRetries] times; [flushAll] always tries again.
 */
class DebouncedSaveScheduler<K : Any, V : Any>(
    private val scope: CoroutineScope,
    private val delayMs: Long,
    private val retryDelayMs: Long = 2_000,
    private val maxRetries: Int = 3,
    private val save: suspend (key: K, value: V) -> Unit,
    private val onError: (key: K, error: Throwable, willRetry: Boolean) -> Unit = { _, _, _ -> },
) {
    private class Slot<V> {
        var pending: V? = null
        var inFlight: V? = null
        var timer: Job? = null
        var failures = 0
        val mutex = Mutex()
    }

    private val lock = Any()
    private val slots = HashMap<K, Slot<V>>()

    /** Replaces the pending value for [key] and (re)starts its debounce timer. */
    fun submit(key: K, value: V) {
        synchronized(lock) {
            val slot = slots.getOrPut(key) { Slot() }
            slot.pending = value
            slot.failures = 0
            schedule(key, slot, delayMs)
        }
    }

    /** The newest value for [key] that is not yet durably saved, or null. */
    fun latest(key: K): V? = synchronized(lock) { slots[key]?.let { it.pending ?: it.inFlight } }

    /** True while any value is waiting or being written. */
    fun hasPending(): Boolean = synchronized(lock) { slots.values.any { it.pending != null || it.inFlight != null } }

    /** Keys with unsaved values. */
    fun pendingKeys(): Set<K> = synchronized(lock) {
        slots.filterValues { it.pending != null || it.inFlight != null }.keys.toSet()
    }

    /** Drops the pending value of [key] (e.g. its page was deleted). An in-flight write still completes. */
    fun discard(key: K) {
        synchronized(lock) {
            slots[key]?.let {
                it.timer?.cancel()
                it.timer = null
                it.pending = null
            }
        }
    }

    /** Saves [key] now if it has a pending value. */
    fun flush(key: K): Job {
        synchronized(lock) { slots[key]?.let { it.timer?.cancel(); it.timer = null } }
        return scope.launch { run(key) }
    }

    /**
     * Saves every pending value immediately. The returned job completes once all pending and
     * in-flight writes have finished.
     */
    fun flushAll(): Job {
        val keys = synchronized(lock) {
            slots.forEach { (_, slot) -> if (slot.pending != null) { slot.timer?.cancel(); slot.timer = null } }
            slots.keys.toList()
        }
        return scope.launch { keys.map { k -> launch { run(k) } }.joinAll() }
    }

    private fun schedule(key: K, slot: Slot<V>, afterMs: Long) {
        slot.timer?.cancel()
        lateinit var job: Job
        job = scope.launch {
            delay(afterMs)
            // Past the delay this run belongs to nobody: a newer submit must not cancel a write.
            synchronized(lock) { if (slot.timer === job) slot.timer = null }
            run(key)
        }
        slot.timer = job
    }

    private suspend fun run(key: K) {
        val slot = synchronized(lock) { slots[key] } ?: return
        slot.mutex.withLock {
            val value = synchronized(lock) {
                val v = slot.pending
                slot.pending = null
                slot.inFlight = v
                v
            } ?: return
            try {
                save(key, value)
                synchronized(lock) {
                    slot.inFlight = null
                    slot.failures = 0
                }
            } catch (c: CancellationException) {
                synchronized(lock) {
                    if (slot.pending == null) slot.pending = value
                    slot.inFlight = null
                }
                throw c
            } catch (t: Throwable) {
                val retry: Boolean
                synchronized(lock) {
                    slot.inFlight = null
                    if (slot.pending == null) slot.pending = value
                    slot.failures++
                    retry = slot.failures <= maxRetries
                    if (retry && slot.timer == null) schedule(key, slot, retryDelayMs * slot.failures)
                }
                onError(key, t, retry)
            }
        }
    }
}
