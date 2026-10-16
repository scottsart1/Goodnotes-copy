package com.inkwell.notes.audio.logic

/**
 * Maps between a recording's single global timeline and its segment files.
 *
 * Every segment keeps its place on the timeline (so audio anchors written during the
 * recording stay aligned) even when its file is missing; [playable] lists the segment
 * indices that can actually be played, in order. A player that only holds the playable
 * segments addresses them by *item* index (position in [playable]).
 */
class SegmentTimeline(
    durationsMs: List<Long>,
    playable: List<Int>? = null,
) {
    private val durations: LongArray = LongArray(durationsMs.size) { durationsMs[it].coerceAtLeast(0) }
    private val starts: LongArray = LongArray(durations.size).also { s ->
        var acc = 0L
        for (i in durations.indices) {
            s[i] = acc
            acc += durations[i]
        }
    }

    /** Segment indices that have playable media, ascending. */
    val playable: List<Int> = (playable ?: durations.indices.toList()).filter { it in durations.indices }.distinct().sorted()

    val segmentCount: Int get() = durations.size
    val totalMs: Long = durations.sum()

    fun startOf(segment: Int): Long = if (segment in durations.indices) starts[segment] else totalMs
    fun durationOf(segment: Int): Long = if (segment in durations.indices) durations[segment] else 0L

    /**
     * Segment containing [globalMs] and the offset inside it. Positions are clamped to the
     * timeline; a position exactly on a boundary belongs to the following segment, except at
     * the very end. Zero-length segments are skipped.
     */
    fun locate(globalMs: Long): Position {
        if (durations.isEmpty()) return Position(0, 0)
        val g = globalMs.coerceIn(0, totalMs)
        var last = -1
        for (i in durations.indices) {
            if (durations[i] <= 0) continue
            last = i
            if (g < starts[i] + durations[i]) return Position(i, g - starts[i])
        }
        return if (last >= 0) Position(last, durations[last]) else Position(0, 0)
    }

    /** Global position of [offsetMs] inside [segment]. */
    fun globalOf(segment: Int, offsetMs: Long): Long {
        if (segment !in durations.indices) return totalMs
        return (starts[segment] + offsetMs.coerceIn(0, durations[segment])).coerceIn(0, totalMs)
    }

    /**
     * Player item and offset for [globalMs]. Positions inside a segment that cannot be played
     * move to the start of the next playable one (or the end of the last). Null when nothing
     * is playable.
     */
    fun locateItem(globalMs: Long): Position? {
        if (playable.isEmpty()) return null
        val pos = locate(globalMs)
        val item = playable.indexOf(pos.index)
        if (item >= 0) return Position(item, pos.offsetMs)
        val next = playable.indexOfFirst { it > pos.index }
        return if (next >= 0) Position(next, 0) else Position(playable.lastIndex, durationOf(playable.last()))
    }

    /** Global position of [offsetMs] inside player item [item]. */
    fun globalOfItem(item: Int, offsetMs: Long): Long {
        val segment = playable.getOrNull(item) ?: return if (item < 0) 0 else totalMs
        return globalOf(segment, offsetMs)
    }

    data class Position(val index: Int, val offsetMs: Long)
}
