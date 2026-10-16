package com.inkwell.notes.speakers.ui.logic

/** Playing a short voice sample over the shared player and handing it back afterwards. Pure. */
object SampleLogic {
    const val MIN_MS = 1_500L
    const val MAX_MS = 12_000L

    enum class Verdict {
        CONTINUE,
        /** The sample is over (end reached, paused, failed): pause and restore the earlier position. */
        STOP_AND_RESTORE,
        /** Something else took the player over: leave it alone. */
        RELEASE,
    }

    /** The clip to play for a sample of [startMs]..[endMs] on a recording of [durationMs] (0 = unknown). */
    fun bounds(startMs: Long, endMs: Long, durationMs: Long): Pair<Long, Long> {
        val limit = if (durationMs > 0) durationMs else Long.MAX_VALUE
        val start = startMs.coerceIn(0, maxOf(0, limit - MIN_MS))
        val end = endMs.coerceIn(start + MIN_MS, start + MAX_MS).coerceAtMost(limit)
        return start to maxOf(end, start)
    }

    /** What to do on a player update while the sample of [sampleRecordingId] plays up to [endMs]. */
    fun check(
        sampleRecordingId: String,
        endMs: Long,
        started: Boolean,
        loadedId: String?,
        positionMs: Long,
        playing: Boolean,
        error: String?,
    ): Verdict = when {
        loadedId != sampleRecordingId -> Verdict.RELEASE
        error != null -> Verdict.STOP_AND_RESTORE
        positionMs >= endMs -> Verdict.STOP_AND_RESTORE
        started && !playing -> Verdict.STOP_AND_RESTORE
        else -> Verdict.CONTINUE
    }
}

/** Recording a voice sample in Settings ("Record my voice"). Pure timing and phases. */
object EnrollmentLogic {
    /** How long a sample records unless stopped. */
    const val TARGET_MS = 15_000L
    /** Shortest sample worth sending to the voiceprint model. */
    const val MIN_MS = 6_000L

    sealed interface Phase {
        data object Ready : Phase
        data class Recording(val elapsedMs: Long, val level: Float) : Phase
        data object Saving : Phase
        data object Saved : Phase
        /** The model found too little speech in the clip. */
        data object TooLittleSpeech : Phase
        data class Failed(val reason: Reason) : Phase
    }

    enum class Reason { MIC_BUSY, DOCUMENT_RECORDING, PERMISSION, SAVE_FAILED }

    fun progress(elapsedMs: Long): Float = (elapsedMs.toFloat() / TARGET_MS).coerceIn(0f, 1f)

    fun canFinish(elapsedMs: Long): Boolean = elapsedMs >= MIN_MS

    fun shouldAutoStop(elapsedMs: Long): Boolean = elapsedMs >= TARGET_MS

    /** Seconds left, rounded up, for the countdown. */
    fun secondsLeft(elapsedMs: Long): Int = (((TARGET_MS - elapsedMs).coerceAtLeast(0) + 999) / 1000).toInt()
}
