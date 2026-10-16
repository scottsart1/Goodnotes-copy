package com.inkwell.notes.whiteboard.logic

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Debounced, crash-conscious autosave. [submit] keeps only the newest value per key and
 * writes it [delayMs] after the last change; [flush] writes everything pending immediately.
 * Writes of one key are serialised, a value is only marked saved if nothing newer arrived
 * meanwhile, and failed writes are retried with backoff. Pending values stay readable via
 * [pending] so a reopened screen never loads a stale file.
 *
 * Runs in a long-lived [scope] (the app scope) so saves outlive the screen that made them.
 */
class DebouncedSaver<K : Any, V : Any>(
    private val scope: CoroutineScope,
    private val delayMs: Long,
    private val save: suspend (K, V) -> Unit,
    private val onResult: (key: K, error: Throwable?, willRetry: Boolean) -> Unit = { _, _, _ -> },
    private val retryDelaysMs: List<Long> = listOf(1_000L, 4_000L, 15_000L),
) {
    private class Entry<V>(val value: V, val version: Long, val attempts: Int = 0)

    private val lock = Any()
    private val entries = HashMap<K, Entry<V>>()
    private val timers = HashMap<K, Job>()
    private val writeLocks = HashMap<K, Mutex>()
    private var versionCounter = 0L

    /** Records the newest [value] for [key] and (re)starts its debounce timer. */
    fun submit(key: K, value: V) {
        synchronized(lock) {
            entries[key] = Entry(value, ++versionCounter)
            schedule(key, delayMs)
        }
    }

    /** The newest value not yet written for [key], if any. */
    fun pending(key: K): V? = synchronized(lock) { entries[key]?.value }

    val hasPending: Boolean get() = synchronized(lock) { entries.isNotEmpty() }

    /** Writes every pending value now (or only [key]'s). Returns when the writes finished. */
    suspend fun flush(key: K? = null) {
        val keys = synchronized(lock) {
            val ks = if (key != null) listOfNotNull(key.takeIf { entries.containsKey(it) }) else entries.keys.toList()
            ks.forEach { timers.remove(it)?.cancel() }
            ks
        }
        withContext(NonCancellable) { keys.forEach { write(it) } }
    }

    /** Fire-and-forget [flush] in the saver's scope (lifecycle pause, screen exit). */
    fun flushAsync(): Job = scope.launch { flush() }

    // Called with [lock] held.
    private fun schedule(key: K, afterMs: Long) {
        timers.remove(key)?.cancel()
        timers[key] = scope.launch {
            delay(afterMs)
            val self = coroutineContext[Job]
            synchronized(lock) { if (timers[key] === self) timers.remove(key) }
            withContext(NonCancellable) { write(key) }
        }
    }

    private suspend fun write(key: K) {
        val mutex = synchronized(lock) { writeLocks.getOrPut(key) { Mutex() } }
        mutex.withLock {
            val entry = synchronized(lock) { entries[key] } ?: return
            try {
                save(key, entry.value)
                synchronized(lock) {
                    if (entries[key]?.version == entry.version) entries.remove(key)
                }
                onResult(key, null, false)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                val retryIn = retryDelaysMs.getOrNull(entry.attempts)
                synchronized(lock) {
                    val current = entries[key]
                    if (current != null && current.version == entry.version) {
                        entries[key] = Entry(current.value, current.version, entry.attempts + 1)
                        if (retryIn != null && timers[key] == null) schedule(key, retryIn)
                    }
                }
                onResult(key, t, retryIn != null)
            }
        }
    }
}
