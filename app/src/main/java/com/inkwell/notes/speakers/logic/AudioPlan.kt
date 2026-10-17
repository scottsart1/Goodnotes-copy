package com.inkwell.notes.speakers.logic

/** Part of one segment file on the recording timeline. */
data class AudioPiece(
    val segment: Int,
    /** Offsets inside the segment file. */
    val fromMs: Long,
    val toMs: Long,
    /** Where [fromMs] sits on the recording timeline. */
    val globalStartMs: Long,
) {
    val durationMs: Long get() = toMs - fromMs
}

/** A contiguous stretch of the recording analysed in one go. */
data class AnalysisWindow(
    val index: Int,
    val globalStartMs: Long,
    val pieces: List<AudioPiece>,
) {
    val durationMs: Long get() = pieces.sumOf { it.durationMs }
}

/** Maps recording-timeline ranges onto segment files. Pure. */
object AudioPlan {
    /**
     * At most this much audio is decoded and diarized at once (~19 MB of 16 kHz floats). The
     * native pass cannot be interrupted, so this also bounds the work lost when the system stops
     * the job (WorkManager's 10-minute limit); voices are linked across windows afterwards.
     */
    const val MAX_WINDOW_MS = 5L * 60 * 1000

    /**
     * Consecutive windows of whole segments, each at most [maxWindowMs] (a longer segment is
     * cut into equal parts). A missing or empty segment ends the window (the timeline has a
     * hole there), so every window is contiguous audio and window time + [AnalysisWindow.globalStartMs]
     * is recording time.
     */
    fun windows(durationsMs: List<Long>, available: List<Boolean>, maxWindowMs: Long = MAX_WINDOW_MS): List<AnalysisWindow> {
        require(maxWindowMs > 0)
        val out = ArrayList<AnalysisWindow>()
        var current = ArrayList<AudioPiece>()
        var currentMs = 0L
        var currentStart = 0L
        var global = 0L
        fun close() {
            if (current.isNotEmpty()) out += AnalysisWindow(out.size, currentStart, current)
            current = ArrayList()
            currentMs = 0
        }
        for ((i, raw) in durationsMs.withIndex()) {
            val d = raw.coerceAtLeast(0)
            if (d == 0L || available.getOrElse(i) { false } != true) {
                close()
                global += d
                continue
            }
            if (d > maxWindowMs) {
                close()
                val parts = ((d + maxWindowMs - 1) / maxWindowMs).toInt()
                for (p in 0 until parts) {
                    val a = d * p / parts
                    val b = d * (p + 1) / parts
                    out += AnalysisWindow(out.size, global + a, listOf(AudioPiece(i, a, b, global + a)))
                }
            } else {
                if (currentMs + d > maxWindowMs) close()
                if (current.isEmpty()) currentStart = global
                current += AudioPiece(i, 0, d, global)
                currentMs += d
            }
            global += d
        }
        close()
        return out
    }

    /**
     * The pieces of segment files covering [startMs, endMs) of the timeline. Missing segments
     * are included (the caller fills them with silence) so the clip keeps its length.
     */
    fun slice(durationsMs: List<Long>, startMs: Long, endMs: Long): List<AudioPiece> {
        val out = ArrayList<AudioPiece>()
        if (endMs <= startMs) return out
        var global = 0L
        for ((i, raw) in durationsMs.withIndex()) {
            val d = raw.coerceAtLeast(0)
            val segStart = global
            val segEnd = global + d
            global = segEnd
            if (d == 0L || segEnd <= startMs || segStart >= endMs) continue
            val a = maxOf(startMs, segStart) - segStart
            val b = minOf(endMs, segEnd) - segStart
            if (b > a) out += AudioPiece(i, a, b, segStart + a)
        }
        return out
    }
}
