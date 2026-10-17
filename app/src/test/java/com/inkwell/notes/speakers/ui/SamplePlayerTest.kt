package com.inkwell.notes.speakers.ui

import com.inkwell.notes.audio.PlaybackController
import com.inkwell.notes.audio.PlaybackState
import com.inkwell.notes.core.model.Recording
import com.inkwell.notes.core.model.RecordingStatus
import com.inkwell.notes.speakers.ui.logic.SampleLogic
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The naming sheet's "play sample" over the shared player: plays the clip, then puts the player back. */
@OptIn(ExperimentalCoroutinesApi::class)
class SamplePlayerTest {
    private class FakePlayback : PlaybackController {
        override val state = MutableStateFlow(PlaybackState())
        val calls = ArrayList<String>()

        override fun load(recording: Recording) {
            calls += "load:${recording.id}"
            state.value = PlaybackState(recording = recording, durationMs = recording.durationMs, speed = state.value.speed)
        }

        override fun play() {
            calls += "play"
            // Like the real controller: a new attempt clears the last error.
            state.update { it.copy(playing = true, error = null) }
        }

        override fun pause() {
            calls += "pause"
            state.update { it.copy(playing = false) }
        }

        override fun seekTo(positionMs: Long) {
            calls += "seek:$positionMs"
            state.update { it.copy(positionMs = positionMs) }
        }

        override fun setSpeed(speed: Float) {
            calls += "speed:$speed"
            state.update { it.copy(speed = speed) }
        }

        override fun release() {
            calls += "release"
            state.value = PlaybackState()
        }

        /** Playback advanced to [positionMs] (a tick of the real player). */
        fun tick(positionMs: Long) = state.update { it.copy(positionMs = positionMs) }
    }

    private fun recording(id: String, durationMs: Long = 600_000) =
        Recording(id, "d1", id, listOf("/a/$id.m4a"), listOf(durationMs), durationMs, 0, durationMs, RecordingStatus.COMPLETE)

    private val r1 = recording("r1")

    @Test
    fun aSamplePlaysItsClipThenRestoresPositionAndSpeed() = runTest {
        val playback = FakePlayback()
        playback.load(r1)
        playback.seekTo(30_000)
        playback.setSpeed(1.5f)
        playback.calls.clear()
        val player = SamplePlayer(playback, backgroundScope)

        player.toggle(r1, "S1", 5_000, 9_000)
        runCurrent()
        assertEquals("S1", player.playing.value)
        assertEquals(listOf("speed:1.0", "seek:5000", "play"), playback.calls)
        assertTrue(playback.state.value.playing)

        playback.tick(7_000)
        runCurrent()
        assertEquals("still inside the clip", "S1", player.playing.value)

        playback.tick(9_000)
        runCurrent()
        assertNull(player.playing.value)
        assertEquals(listOf("speed:1.0", "seek:5000", "play", "pause", "seek:30000", "speed:1.5"), playback.calls)
        assertFalse(playback.state.value.playing)
        assertEquals(30_000, playback.state.value.positionMs)
        assertEquals(1.5f, playback.state.value.speed)
    }

    @Test
    fun tappingTheSameSampleAgainStopsItAndRestores() = runTest {
        val playback = FakePlayback()
        playback.load(r1)
        playback.seekTo(42_000)
        playback.calls.clear()
        val player = SamplePlayer(playback, backgroundScope)

        player.toggle(r1, "S1", 5_000, 9_000)
        runCurrent()
        player.toggle(r1, "S1", 5_000, 9_000)
        runCurrent()
        assertNull(player.playing.value)
        assertEquals(listOf("seek:5000", "play", "pause", "seek:42000"), playback.calls)
        // A third tap (double tap on Stop) changes nothing more.
        player.stop()
        assertEquals(4, playback.calls.size)
    }

    @Test
    fun switchingToAnotherVoicesSampleKeepsTheOriginalPositionToRestore() = runTest {
        val playback = FakePlayback()
        playback.load(r1)
        playback.seekTo(42_000)
        playback.calls.clear()
        val player = SamplePlayer(playback, backgroundScope)

        player.toggle(r1, "S1", 5_000, 9_000)
        runCurrent()
        playback.tick(6_000)
        runCurrent()
        player.toggle(r1, "S2", 100_000, 104_000)
        runCurrent()
        assertEquals("S2", player.playing.value)
        playback.tick(104_000)
        runCurrent()
        assertNull(player.playing.value)
        assertEquals("back where the user was before the first sample, not inside the first clip", 42_000, playback.state.value.positionMs)
    }

    @Test
    fun aSampleOfAnotherRecordingLoadsItAndReloadsTheOldOneAfterwards() = runTest {
        val playback = FakePlayback()
        playback.load(r1)
        playback.seekTo(42_000)
        playback.calls.clear()
        val r2 = recording("r2")
        val player = SamplePlayer(playback, backgroundScope)

        player.toggle(r2, "S1", 0, 4_000)
        runCurrent()
        assertEquals(listOf("load:r2", "seek:0", "play"), playback.calls)
        playback.tick(4_000)
        runCurrent()
        assertNull(player.playing.value)
        assertEquals(listOf("load:r2", "seek:0", "play", "pause", "load:r1", "seek:42000"), playback.calls)
        assertEquals("r1", playback.state.value.recording?.id)
    }

    @Test
    fun aStalledPlayerDoesNotLeaveTheSamplePlayingForever() = runTest {
        val playback = FakePlayback()
        playback.load(r1)
        playback.calls.clear()
        val player = SamplePlayer(playback, backgroundScope)
        // The fake "plays" but never advances.
        player.toggle(r1, "S1", 5_000, 9_000)
        runCurrent()
        advanceTimeBy(4_000 + 5_000 + 1)
        runCurrent()
        assertNull(player.playing.value)
        assertTrue(playback.calls.contains("pause"))
    }

    @Test
    fun aPlayerErrorOrAnotherLoadEndsTheSample() = runTest {
        val playback = FakePlayback()
        playback.load(r1)
        playback.calls.clear()
        val player = SamplePlayer(playback, backgroundScope)

        player.toggle(r1, "S1", 5_000, 9_000)
        runCurrent()
        playback.state.update { it.copy(error = "File missing") }
        runCurrent()
        assertNull(player.playing.value)
        assertEquals(SampleLogic.Verdict.STOP_AND_RESTORE, SampleLogic.check("r1", 9_000, true, "r1", 5_000, true, "File missing"))

        // Something else loads another recording meanwhile: the sample lets go without touching the player.
        playback.calls.clear()
        player.toggle(r1, "S2", 5_000, 9_000)
        runCurrent()
        playback.load(recording("r9"))
        runCurrent()
        assertNull(player.playing.value)
        assertEquals(listOf("seek:5000", "play", "load:r9"), playback.calls)
    }
}
