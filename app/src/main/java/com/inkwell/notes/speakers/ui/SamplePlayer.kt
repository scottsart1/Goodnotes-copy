package com.inkwell.notes.speakers.ui

import com.inkwell.notes.app.AppGraph
import com.inkwell.notes.audio.PlaybackController
import com.inkwell.notes.core.model.Recording
import com.inkwell.notes.speakers.ui.logic.SampleLogic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Plays a voice's sample clip on the shared player ("play sample" in the naming sheet): loads
 * the recording if needed, plays [start, end) at normal speed, pauses at the end, and puts the
 * player back where it was (recording, position, speed). Main thread only.
 */
internal class SamplePlayer(private val playback: PlaybackController, private val scope: CoroutineScope) {
    constructor(graph: AppGraph, scope: CoroutineScope) : this(graph.playback, scope)

    private data class Before(val recording: Recording?, val positionMs: Long, val speed: Float)

    private val _playing = MutableStateFlow<String?>(null)

    /** Key of the sample playing now, or null. */
    val playing: StateFlow<String?> = _playing

    private var job: Job? = null
    private var before: Before? = null

    fun toggle(recording: Recording, key: String, startMs: Long, endMs: Long) {
        if (_playing.value == key) {
            stop()
            return
        }
        stop()
        val s = playback.state.value
        before = Before(s.recording, s.positionMs, s.speed)
        val (start, end) = SampleLogic.bounds(startMs, endMs, recording.durationMs)
        val loaded = s.recording
        if (loaded == null || loaded.id != recording.id || loaded.segmentFiles != recording.segmentFiles) playback.load(recording)
        if (s.speed != 1f) playback.setSpeed(1f)
        playback.seekTo(start)
        playback.play()
        _playing.value = key
        // Generous bound: a stalled player must not leave the sample "playing" forever.
        val limit = (end - start) + START_GRACE_MS
        job = scope.launch {
            var started = false
            val verdict = withTimeoutOrNull(limit) {
                playback.state.map { p ->
                    if (p.playing) started = true
                    SampleLogic.check(recording.id, end, started, p.recording?.id, p.positionMs, p.playing, p.error)
                }.first { it != SampleLogic.Verdict.CONTINUE }
            } ?: SampleLogic.Verdict.STOP_AND_RESTORE
            finish(verdict)
        }
    }

    /** Stops a sample that is playing and restores the player. */
    fun stop() {
        val j = job
        job = null
        j?.cancel()
        if (_playing.value != null) finish(SampleLogic.Verdict.STOP_AND_RESTORE)
    }

    private fun finish(verdict: SampleLogic.Verdict) {
        if (_playing.value == null) return
        _playing.value = null
        val b = before
        before = null
        if (verdict == SampleLogic.Verdict.RELEASE || b == null) return
        playback.pause()
        val prev = b.recording
        when {
            prev == null -> playback.seekTo(0)
            prev.id == playback.state.value.recording?.id -> playback.seekTo(b.positionMs)
            else -> {
                playback.load(prev)
                playback.seekTo(b.positionMs)
            }
        }
        if (b.speed != 1f) playback.setSpeed(b.speed)
    }

    private companion object {
        const val START_GRACE_MS = 5_000L
    }
}
