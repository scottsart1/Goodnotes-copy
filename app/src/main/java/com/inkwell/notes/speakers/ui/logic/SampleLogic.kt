package com.inkwell.notes.speakers.ui.logic

import com.inkwell.notes.speakers.SpeakerModelState

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

/** Recording a voice sample in Settings ("Record my voice"). Pure timing, phases and preconditions. */
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

    enum class Reason {
        MIC_BUSY, DOCUMENT_RECORDING, PERMISSION, SAVE_FAILED,
        /** The on-device models were not downloaded and verified when the clip was analysed. */
        MODELS_MISSING,
        /** Remember voices is off: SpeakerService.enroll stores nothing. */
        VOICES_OFF,
        /** The person was deleted meanwhile. */
        PERSON_GONE,
    }

    /**
     * What must hold before a sample is worth recording. SpeakerService.enroll answers false
     * (and only queues the model download) when the models are missing or voices are not
     * remembered, so the dialog settles these first instead of calling every false "too little speech".
     */
    sealed interface Gate {
        data object Ready : Gate
        /** The on-device models are missing; [models] says whether a download runs or failed. */
        data class NeedsModels(val models: SpeakerModelState) : Gate
        data object VoicesOff : Gate
    }

    fun gate(models: SpeakerModelState, rememberVoices: Boolean): Gate = when {
        !rememberVoices -> Gate.VoicesOff
        models !is SpeakerModelState.Ready -> Gate.NeedsModels(models)
        else -> Gate.Ready
    }

    /** Whether the "Start recording" button may be offered. */
    fun canStart(gate: Gate): Boolean = gate is Gate.Ready

    /** Whether the gate offers a model download right now (not while one is already running). */
    fun offersDownload(gate: Gate): Boolean = gate is Gate.NeedsModels && gate.models !is SpeakerModelState.Downloading

    /**
     * The phase after SpeakerService.enroll answered [ok] under [gate]: false means "too little
     * speech" only when the models were ready, voices are remembered and the person still exists.
     */
    fun afterEnroll(ok: Boolean, gate: Gate, personExists: Boolean = true): Phase = when {
        ok -> Phase.Saved
        gate is Gate.VoicesOff -> Phase.Failed(Reason.VOICES_OFF)
        gate is Gate.NeedsModels -> Phase.Failed(Reason.MODELS_MISSING)
        !personExists -> Phase.Failed(Reason.PERSON_GONE)
        else -> Phase.TooLittleSpeech
    }

    fun progress(elapsedMs: Long): Float = (elapsedMs.toFloat() / TARGET_MS).coerceIn(0f, 1f)

    fun canFinish(elapsedMs: Long): Boolean = elapsedMs >= MIN_MS

    fun shouldAutoStop(elapsedMs: Long): Boolean = elapsedMs >= TARGET_MS

    /** Seconds left, rounded up, for the countdown. */
    fun secondsLeft(elapsedMs: Long): Int = (((TARGET_MS - elapsedMs).coerceAtLeast(0) + 999) / 1000).toInt()
}
