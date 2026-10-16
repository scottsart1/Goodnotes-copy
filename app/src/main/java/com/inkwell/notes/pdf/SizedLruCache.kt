package com.inkwell.notes.pdf

/**
 * Thread-safe LRU cache bounded by the total [sizeOf] of its values (bytes for bitmaps,
 * characters for text). Entries larger than [maxEntrySize] are not cached at all, so one huge
 * render never flushes everything else. Evicted values are simply dropped (never recycled):
 * callers may still be drawing them.
 */
internal class SizedLruCache<K : Any, V : Any>(
    val maxSize: Long,
    private val maxEntrySize: Long = maxSize / 2,
    private val sizeOf: (V) -> Long,
) {
    private val map = LinkedHashMap<K, V>(32, 0.75f, true)
    private var size = 0L

    @Synchronized fun get(key: K): V? = map[key]

    @Synchronized fun put(key: K, value: V): Boolean {
        val s = sizeOf(value).coerceAtLeast(0)
        val previous = map.remove(key)
        if (previous != null) size -= sizeOf(previous).coerceAtLeast(0)
        if (s > maxEntrySize || s > maxSize) return false
        map[key] = value
        size += s
        trimTo(maxSize)
        return true
    }

    @Synchronized fun remove(key: K): V? = map.remove(key)?.also { size -= sizeOf(it).coerceAtLeast(0) }

    /** Removes every entry whose key matches [predicate]. */
    @Synchronized fun removeIf(predicate: (K) -> Boolean): Int {
        var removed = 0
        val it = map.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (predicate(e.key)) {
                size -= sizeOf(e.value).coerceAtLeast(0)
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
            val e = it.next()
            size -= sizeOf(e.value).coerceAtLeast(0)
            it.remove()
        }
    }
}
