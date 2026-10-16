package com.inkwell.notes.ink.render

/**
 * Version bookkeeping of one zoom level's tiles of one page. Pure Kotlin (generic over the
 * bitmap type) so the invalidation rules are unit tested.
 *
 * Rules:
 * - A tile shows content of [Tile.version] (a page "visual version"); it is current when that
 *   equals the page's latest visual version.
 * - Edits that do not touch a current tile simply advance its version (no re-render).
 * - Items added on top of a current tile queue an [Increment]: the next render draws just those
 *   items onto a copy of the tile instead of re-rendering everything.
 * - Any other touching edit marks the tile dirty; it keeps showing its old bitmap until a full
 *   re-render finishes, so nothing ever flashes empty.
 */
class TileBook<B : Any> {
    class Increment(val from: Long, val to: Long, val items: List<Any>)

    class Tile<B : Any>(val ix: Int, val iy: Int) {
        var bitmap: B? = null
        var version: Long = -1L
        var dirty: Int = 0
        val increments = ArrayList<Increment>()
        var inFlight = false
        var failures = 0
        /** Uptime (ms) of the last failed render, for retry backoff. */
        var failedAt = 0L
        var lastUsedFrame = 0L
    }

    enum class Need { NONE, INCREMENT, FULL }

    class Ticket(val full: Boolean, val dirtyAtStart: Int, val version: Long, val baseVersion: Long, val incrementCount: Int, val items: List<Any>)

    val tiles = HashMap<Long, Tile<B>>()

    fun get(ix: Int, iy: Int): Tile<B>? = tiles[key(ix, iy)]
    fun getOrCreate(ix: Int, iy: Int): Tile<B> = tiles.getOrPut(key(ix, iy)) { Tile(ix, iy) }

    /**
     * Applies an edit that moved the page from [prev] to [next] visual version. [touches] tells
     * whether the edit's area overlaps a tile. [addedOnTop] are new items painted above
     * everything else (eligible for incremental drawing), or null for any other edit.
     */
    fun onEdit(prev: Long, next: Long, addedOnTop: List<Any>?, touches: (Tile<B>) -> Boolean) {
        for (t in tiles.values) {
            val chainEnd = if (t.increments.isEmpty()) t.version else t.increments.last().to
            if (!touches(t)) {
                when {
                    t.increments.isEmpty() && t.version == prev -> t.version = next
                    t.increments.isNotEmpty() && chainEnd == prev -> t.increments += Increment(prev, next, emptyList())
                }
                continue
            }
            if (addedOnTop != null && t.bitmap != null && chainEnd == prev) {
                t.increments += Increment(prev, next, addedOnTop)
            } else {
                t.dirty++
                t.increments.clear()
            }
        }
    }

    /** Marks every tile stale (e.g. the PDF background changed). */
    fun invalidateAll() {
        for (t in tiles.values) { t.dirty++; t.increments.clear() }
    }

    fun need(t: Tile<B>, current: Long): Need = when {
        t.inFlight -> Need.NONE
        t.increments.isNotEmpty() && t.bitmap != null && t.increments.first().from == t.version -> Need.INCREMENT
        t.bitmap == null || t.version != current -> Need.FULL
        else -> Need.NONE
    }

    fun begin(t: Tile<B>, need: Need, current: Long): Ticket {
        t.inFlight = true
        return if (need == Need.INCREMENT) {
            val items = ArrayList<Any>()
            for (inc in t.increments) items.addAll(inc.items)
            Ticket(false, t.dirty, t.increments.last().to, t.version, t.increments.size, items)
        } else {
            Ticket(true, t.dirty, current, -1L, 0, emptyList())
        }
    }

    /**
     * Installs a finished render. [current] is the page's visual version now. Returns true when
     * the tile's bitmap changed.
     */
    fun finish(t: Tile<B>, ticket: Ticket, result: B?, current: Long): Boolean {
        t.inFlight = false
        if (result == null) { t.failures++; return false }
        t.failures = 0
        if (ticket.full) {
            val v = if (t.dirty == ticket.dirtyAtStart) current else ticket.version
            if (t.bitmap != null && v <= t.version && t.dirty != ticket.dirtyAtStart) return false
            t.bitmap = result
            t.version = v
            t.increments.clear()
            return true
        }
        if (t.version != ticket.baseVersion) return false
        t.bitmap = result
        t.version = ticket.version
        val n = minOf(ticket.incrementCount, t.increments.size)
        repeat(n) { t.increments.removeAt(0) }
        // Increments queued during the render chain on from the new version; anything else is stale.
        if (t.increments.isNotEmpty() && t.increments.first().from != t.version) t.increments.clear()
        return true
    }

    /** True when the tile shows at least [version] (pending wet strokes can be dropped). */
    fun covers(ix: Int, iy: Int, version: Long): Boolean {
        val t = get(ix, iy) ?: return false
        return t.bitmap != null && t.version >= version
    }

    fun clear() = tiles.clear()

    companion object {
        fun key(ix: Int, iy: Int): Long = (ix.toLong() shl 32) or (iy.toLong() and 0xFFFFFFFFL)
    }
}
