package com.inkwell.notes.audio

import com.inkwell.notes.audio.logic.Waveform
import com.inkwell.notes.audio.recorder.RecordingRuntime
import com.inkwell.notes.audio.recorder.RecordingSession
import com.inkwell.notes.audio.recorder.RecordingSession.Companion.withSegments
import com.inkwell.notes.audio.recorder.SegmentRecorder
import com.inkwell.notes.core.model.RecordingStatus
import com.inkwell.notes.core.model.TranscriptStatus
import com.inkwell.notes.core.storage.db.RecordingEntity
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The in-memory bookkeeping of one recording: the pause-free clock, the segment ledger,
 * the envelope and the published state, plus the audio anchors handed to the ink engine
 * through [RecordingRuntime.anchorFor].
 */
class RecordingSessionTest {
    private var now = 5_000_000L
    private val dir = File("/tmp/inkwell-test/doc/audio/rec")
    private val config = SegmentRecorder.Config(sampleRate = 44_100, bitrate = 96_000, segmentMs = 120_000)

    private fun session(docId: String = "doc", recordingId: String = "rec") =
        RecordingSession(docId, recordingId, startedAt = 1_700_000_000_000L, dir = dir, config = config, elapsedRealtime = { now })

    @After
    fun tearDown() {
        RecordingRuntime.session = null
    }

    @Test
    fun elapsedExcludesPausesAndTheLevelIsZeroWhilePaused() {
        val s = session()
        val flow = MutableStateFlow<RecordingState>(RecordingState.Idle)
        s.begin()
        now += 10_000
        s.sample(20_000)
        s.publish(flow)
        val recording = flow.value as RecordingState.Active
        assertEquals(10_000L, recording.elapsedMs)
        assertFalse(recording.paused)
        assertTrue(recording.level > 0f)

        s.markPaused()
        now += 60_000 // a minute paused: not recorded
        s.publish(flow)
        val paused = flow.value as RecordingState.Active
        assertEquals(10_000L, paused.elapsedMs)
        assertTrue(paused.paused)
        assertEquals(0f, paused.level, 0f)

        s.markResumed()
        now += 5_000
        s.publish(flow)
        assertEquals(15_000L, (flow.value as RecordingState.Active).elapsedMs)
        assertFalse((flow.value as RecordingState.Active).paused)
    }

    @Test
    fun samplesWhilePausedDoNotTouchTheEnvelope() {
        val s = session()
        s.begin()
        for (i in 0 until 20) {
            now += 50
            s.sample(30_000)
        }
        val before = s.waveformBytes().size
        s.markPaused()
        for (i in 0 until 20) {
            now += 50
            s.sample(32_000)
        }
        assertEquals(before, s.waveformBytes().size)
        s.markResumed()
        now += 50
        s.sample(30_000)
        // Resuming continues the envelope where it left off (one more poll, same bucket count or +1).
        assertTrue(s.waveformBytes().size in before..before + 1)
    }

    @Test
    fun anchorsFollowTheTimelineWhileRecordingAndFreezeWhilePaused() {
        val s = session(docId = "lecture")
        RecordingRuntime.session = s
        s.begin()
        now += 30_000
        assertEquals(30_000L, RecordingRuntime.anchorFor("lecture")!!.offsetMs)
        assertEquals("rec", RecordingRuntime.anchorFor("lecture")!!.recordingId)
        // Another document never gets anchors from this recording.
        assertNull(RecordingRuntime.anchorFor("other"))

        s.markPaused()
        now += 120_000
        assertEquals(30_000L, RecordingRuntime.anchorFor("lecture")!!.offsetMs)
        s.markResumed()
        now += 1_000
        assertEquals(31_000L, RecordingRuntime.anchorFor("lecture")!!.offsetMs)

        // Once stopping begins, notes are no longer linked to it.
        s.finishing = true
        assertNull(RecordingRuntime.anchorFor("lecture"))
        RecordingRuntime.session = null
        assertNull(RecordingRuntime.anchorFor("lecture"))
    }

    @Test
    fun anchorsLineUpWithFinishedSegmentsAfterRolloverAndCorrection() {
        val s = session()
        RecordingRuntime.session = s
        s.begin()
        now += 120_000
        val index = s.roll(File(dir, "seg_000.m4a"))
        assertEquals(0, index)
        now += 5_000
        assertEquals(125_000L, s.timelineMs())
        assertEquals(5_000L, s.currentSegmentMs())
        // The file turned out 40 ms shorter than the clock estimate: anchors follow the audio.
        s.correct(0, 119_960)
        assertEquals(124_960L, RecordingRuntime.anchorFor("doc")!!.offsetMs)
        val segs = s.segments()
        assertEquals(1, segs.size)
        assertEquals(File(dir, "seg_000.m4a").absolutePath, segs[0].path)
        assertEquals(119_960L, segs[0].durationMs)
        assertTrue(segs[0].measured)
    }

    @Test
    fun pausesInsideASegmentAreExcludedFromItsEstimate() {
        val s = session()
        s.begin()
        now += 50_000
        s.markPaused()
        now += 600_000
        s.markResumed()
        now += 70_000
        s.roll(File(dir, "seg_000.m4a"))
        assertEquals(listOf(120_000L), s.segments().map { it.durationMs })
        assertEquals(0L, s.currentSegmentMs())
    }

    @Test
    fun publishIsANoOpOnceFinishing() {
        val s = session()
        val flow = MutableStateFlow<RecordingState>(RecordingState.Idle)
        s.begin()
        now += 1_000
        s.publish(flow)
        assertTrue(flow.value is RecordingState.Active)
        s.finishing = true
        flow.value = RecordingState.Idle
        s.publish(flow)
        assertEquals(RecordingState.Idle, flow.value)
        // ...and the stopped clock keeps the final position.
        s.stopClock()
        now += 99_000
        assertEquals(1_000L, s.timelineMs())
    }

    @Test
    fun entityRowCarriesSegmentsAndTotalDuration() {
        val s = session()
        s.begin()
        now += 120_000
        s.roll(File(dir, "seg_000.m4a"))
        now += 120_000
        s.roll(File(dir, "seg_001.m4a"))
        s.correct(1, 119_900)
        val row = RecordingEntity(
            id = "rec", documentId = "doc", title = "t", segmentFiles = "", segmentDurations = "", durationMs = 0,
            startedAt = 0, endedAt = null, status = RecordingStatus.RECORDING, transcriptStatus = TranscriptStatus.NONE, waveformPath = null,
        ).withSegments(s.segments())
        assertEquals(239_900L, row.durationMs)
        assertEquals("120000,119900", row.segmentDurations)
        val model = row.toModel()
        assertEquals(2, model.segmentFiles.size)
        assertEquals(listOf(120_000L, 119_900L), model.segmentDurationsMs)
    }

    @Test
    fun envelopeCoversTheRecordedTimeOnly() {
        val s = session()
        s.begin()
        // 3 s of polling, then a 10 s pause, then 2 s more.
        repeat(60) { now += 50; s.sample(10_000) }
        s.markPaused()
        now += 10_000
        s.markResumed()
        repeat(40) { now += 50; s.sample(10_000) }
        val bytes = s.waveformBytes()
        // 5 s of audio = 50 buckets plus the bucket opened by the sample at exactly 5.000 s;
        // never the 150 buckets of wall time.
        assertTrue("${bytes.size}", bytes.size in Waveform.bucketsFor(5_000)..Waveform.bucketsFor(5_000) + 1)
    }
}
