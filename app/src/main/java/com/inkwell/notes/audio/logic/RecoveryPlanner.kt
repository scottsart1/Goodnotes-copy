package com.inkwell.notes.audio.logic

import java.util.Locale

/**
 * Decides which audio segments of an interrupted recording survive. Segments recorded in
 * the database were finalised by the recorder when it moved on to the next file; the file
 * that was being written when the process died has no MP4 index and usually cannot be
 * played, so it is kept only when its duration can still be read.
 */
object RecoveryPlanner {
    private val SEGMENT_NAME = Regex("""seg_(\d{3,})\.m4a""")

    /** What is known about one candidate segment file. */
    data class Probe(
        val path: String,
        /** Position in the recording (from the file name); used for ordering. */
        val index: Int,
        val sizeBytes: Long,
        /** Duration read from the file now, or null when it cannot be read. */
        val measuredMs: Long?,
        /** Duration stored when the segment was finalised, or null if never persisted. */
        val persistedMs: Long?,
    )

    data class Plan(val kept: List<Kept>, val dropped: List<String>) {
        val totalMs: Long get() = kept.sumOf { it.durationMs }
        val paths: List<String> get() = kept.map { it.path }
        val durations: List<Long> get() = kept.map { it.durationMs }
    }

    data class Kept(val path: String, val durationMs: Long)

    /** Segment index encoded in a file name like seg_004.m4a, or null for other files. */
    fun indexOf(fileName: String): Int? = SEGMENT_NAME.matchEntire(fileName)?.groupValues?.get(1)?.toIntOrNull()

    /** File name of segment [index]. */
    fun nameOf(index: Int): String = String.format(Locale.ROOT, "seg_%03d.m4a", index)

    fun plan(probes: List<Probe>): Plan {
        val kept = ArrayList<Kept>()
        val dropped = ArrayList<String>()
        for (p in probes.distinctBy { it.path }.sortedWith(compareBy({ it.index }, { it.path }))) {
            val duration = durationOf(p)
            if (duration != null) kept += Kept(p.path, duration) else dropped += p.path
        }
        return Plan(kept, dropped)
    }

    /** A usable duration for [p], or null when the segment must be dropped. */
    private fun durationOf(p: Probe): Long? {
        if (p.sizeBytes <= 0) return null
        val measured = p.measuredMs?.takeIf { it > 0 }
        if (measured != null) return measured
        // A segment the recorder had already finalised is trusted even if probing failed now.
        return p.persistedMs?.takeIf { it > 0 }
    }
}
