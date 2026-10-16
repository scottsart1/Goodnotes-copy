package com.inkwell.notes.pdf

/**
 * Thread-safe LRU cache bounded by the total [sizeOf] of its values (bytes for bitmaps,
 * characters for text). Entries larger than [maxEntrySize] are not cached at all, so one huge
 * render never flushes everything else. Sizes are recorded when an entry is added, so a value
 * whose reported size changes later (a recycled bitmap reports 0 bytes) is still accounted
 * correctly. Evicted values are simply dropped (never recycled): callers may still use them.
 */
internal class SizedLruCache<K : Any, V : Any>(
    val maxSize: Long,
    private val maxEntrySize: Long = maxSize / 2,
    private val sizeOf: (V) -> Long,
) {
    private class Entry<V>(val value: V, val size: Long)

    private val map = LinkedHashMap<K, Entry<V>>(32, 0.75f, true)
    private var size = 0L

    @Synchronized fun get(key: K): V? = map[key]?.value

    @Synchronized fun put(key: K, value: V): Boolean {
        val s = sizeOf(value).coerceAtLeast(0)
        map.remove(key)?.let { size -= it.size }
        if (s > maxEntrySize || s > maxSize) return false
        map[key] = Entry(value, s)
        size += s
        trimTo(maxSize)
        return true
    }

    @Synchronized fun remove(key: K): V? = map.remove(key)?.let {
        size -= it.size
        it.value
    }

    /** Removes every entry whose key matches [predicate]. */
    @Synchronized fun removeIf(predicate: (K) -> Boolean): Int {
        var removed = 0
        val it = map.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (predicate(e.key)) {
                size -= e.value.size
                it.remove()
                removed++
            }
        }
        return removed
    }

    @Synchronized fun clear() {
        map.clear()
        size = 0
    }

    /** Shrinks the cache to [fraction] of its budget (memory pressure). */
    @Synchronized fun trimToFraction(fraction: Float) = trimTo((maxSize * fraction.coerceIn(0f, 1f)).toLong())

    @Synchronized fun currentSize(): Long = size

    @Synchronized fun count(): Int = map.size

    @Synchronized fun containsKey(key: K): Boolean = map.containsKey(key)

    private fun trimTo(limit: Long) {
        val it = map.entries.iterator()
        while (size > limit && it.hasNext()) {
            size -= it.next().value.size
            it.remove()
        }
    }
}
