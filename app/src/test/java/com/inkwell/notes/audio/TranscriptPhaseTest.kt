package com.inkwell.notes.audio

import androidx.work.WorkInfo.State
import com.inkwell.notes.audio.transcription.TranscriptPhase
import com.inkwell.notes.core.model.TranscriptStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptPhaseTest {
    private fun phase(status: TranscriptStatus, job: State?, progress: Pair<Int, Int>? = null, error: String? = null) =
        TranscriptPhase.of(status, job, progress, error)

    @Test
    fun runningJobShowsProgress() {
        assertEquals(TranscriptPhase.Running(2, 9), phase(TranscriptStatus.RUNNING, State.RUNNING, 2 to 9))
        assertEquals(TranscriptPhase.Running(0, 0), phase(TranscriptStatus.QUEUED, State.RUNNING))
        assertTrue(phase(TranscriptStatus.RUNNING, State.RUNNING).busy)
    }

    @Test
    fun queuedOrBackingOffIsQueued() {
        assertEquals(TranscriptPhase.Queued, phase(TranscriptStatus.QUEUED, State.ENQUEUED))
        assertEquals(TranscriptPhase.Queued, phase(TranscriptStatus.QUEUED, State.BLOCKED))
        // Row marked before the job shows up.
        assertEquals(TranscriptPhase.Queued, phase(TranscriptStatus.QUEUED, null))
        // A retried job after an earlier failure.
        assertEquals(TranscriptPhase.Queued, phase(TranscriptStatus.QUEUED, State.FAILED))
    }

    @Test
    fun doneWinsOverOldFinishedJobs() {
        assertEquals(TranscriptPhase.Done, phase(TranscriptStatus.DONE, State.SUCCEEDED))
        assertEquals(TranscriptPhase.Done, phase(TranscriptStatus.DONE, State.FAILED, error = "old"))
        assertEquals(TranscriptPhase.Done, phase(TranscriptStatus.DONE, null))
        assertFalse(TranscriptPhase.Done.busy)
    }

    @Test
    fun failuresCarryTheJobMessage() {
        assertEquals(TranscriptPhase.Failed("Invalid API key"), phase(TranscriptStatus.FAILED, State.FAILED, error = "Invalid API key"))
        assertEquals(TranscriptPhase.Failed(null), phase(TranscriptStatus.FAILED, null))
    }

    @Test
    fun runningWithoutAJobWasInterrupted() {
        assertEquals(TranscriptPhase.Interrupted, phase(TranscriptStatus.RUNNING, null))
        assertEquals(TranscriptPhase.Interrupted, phase(TranscriptStatus.RUNNING, State.CANCELLED))
    }

    @Test
    fun nothingYet() {
        assertEquals(TranscriptPhase.None, phase(TranscriptStatus.NONE, null))
        assertEquals(TranscriptPhase.None, phase(TranscriptStatus.NONE, State.CANCELLED))
    }
}
