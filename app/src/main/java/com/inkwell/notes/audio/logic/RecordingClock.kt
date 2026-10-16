package com.inkwell.notes.audio.logic

/**
 * Elapsed recorded time that excludes pauses. [now] is a monotonic millisecond clock
 * (SystemClock.elapsedRealtime on device). Not thread-safe; callers synchronise.
 */
class PausableClock(private val now: () -> Long) {
    private var accumulatedMs = 0L
    private var runningSince: Long? = null

    val running: Boolean get() = runningSince != null

    /** Starts or resumes counting. No effect while already running. */
    fun resume() {
        if (runningSince == null) runningSince = now()
    }

    /** Stops counting, keeping the time recorded so far. No effect while paused. */
    fun pause() {
        val since = runningSince ?: return
        accumulatedMs += (now() - since).coerceAtLeast(0)
        runningSince = null
    }

    fun elapsedMs(): Long = accumulatedMs + (runningSince?.let { (now() - it).coerceAtLeast(0) } ?: 0L)
}

/**
 * The finished segments of a recording and where the segment being written started.
 *
 * Segment boundaries are first known only from the clock ([roll] records an estimate); the
 * real duration measured from the finished file replaces it later ([correct]). The timeline
 * position of "now" is the sum of finished segments plus the time spent in the current one,
 * so anchors line up with the files that will be played back. Not thread-safe.
 */
class SegmentLedger {
    data class Segment(val path: String, val durationMs: Long, val measured: Boolean)

    private val segments = ArrayList<Segment>()
    private var currentStartElapsedMs = 0L

    val finished: List<Segment> get() = segments.toList()
    val count: Int get() = segments.size

    /** Sum of the finished segments' durations. */
    fun committedMs(): Long = segments.sumOf { it.durationMs }

    /** Time spent in the segment currently being written. */
    fun currentSegmentMs(elapsedMs: Long): Long = (elapsedMs - currentStartElapsedMs).coerceAtLeast(0)

    /** Timeline position for [elapsedMs] of recorded (pause-free) time. */
    fun timelineMs(elapsedMs: Long): Long = committedMs() + currentSegmentMs(elapsedMs)

    /**
     * Closes the current segment (written to [path]) at [elapsedMs]; returns its index.
     * Its duration is estimated from the clock until [correct] is called.
     */
    fun roll(path: String, elapsedMs: Long): Int {
        segments += Segment(path, currentSegmentMs(elapsedMs), measured = false)
        currentStartElapsedMs = elapsedMs.coerceAtLeast(currentStartElapsedMs)
        return segments.lastIndex
    }

    /** Replaces segment [index]'s duration with the duration measured from its file. */
    fun correct(index: Int, measuredMs: Long) {
        val s = segments.getOrNull(index) ?: return
        if (measuredMs > 0) segments[index] = s.copy(durationMs = measuredMs, measured = true)
    }

    /** Drops segment [index] (its file turned out unusable). */
    fun drop(index: Int) {
        if (index in segments.indices) segments.removeAt(index)
    }

    fun paths(): List<String> = segments.map { it.path }
    fun durations(): List<Long> = segments.map { it.durationMs }
}
