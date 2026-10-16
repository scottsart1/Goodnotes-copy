package com.inkwell.notes.speakers.ui

import com.inkwell.notes.core.model.SpeakerEngine
import com.inkwell.notes.core.model.SpeakerPrefs
import com.inkwell.notes.core.model.SpeakerTurn
import com.inkwell.notes.settings.logic.MatchSensitivity
import com.inkwell.notes.settings.logic.SpeakerPrefsLogic
import com.inkwell.notes.speakers.ui.logic.EnrollmentLogic
import com.inkwell.notes.speakers.ui.logic.NamingPromptLogic
import com.inkwell.notes.speakers.ui.logic.NamingPromptLogic.Live
import com.inkwell.notes.speakers.ui.logic.SampleLogic
import com.inkwell.notes.speakers.ui.logic.SpeakerLanes
import com.inkwell.notes.speakers.ui.logic.SpeakerNumbers
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeakerUiLogicTest {
    private val colors = mapOf("S1" to 0, "S2" to 5)

    @Test
    fun lanesClipMergeAndColourTurns() {
        val turns = listOf(
            SpeakerTurn("S2", 5_000, 7_000),
            SpeakerTurn("S1", 0, 2_000),
            SpeakerTurn("S1", 2_005, 4_000), // a breath later: same segment
            SpeakerTurn("S2", 9_000, 12_000), // past the end: clipped
            SpeakerTurn("S1", 3_000, 3_000), // empty: dropped
        )
        val g = SpeakerLanes.build(turns, 10_000, { colors.getValue(it) }, mergeGapMs = 10)
        assertEquals(3, g.size)
        assertArrayEquals(floatArrayOf(0f, 0.5f, 0.9f), g.starts, 1e-6f)
        assertArrayEquals(floatArrayOf(0.4f, 0.7f, 1f), g.ends, 1e-6f)
        assertArrayEquals(intArrayOf(0, 5, 5), g.colors)
        assertEquals(listOf("S1", "S2", "S2"), g.labels.toList())
        assertArrayEquals(longArrayOf(0, 5_000, 9_000), g.startMs)
        assertTrue(SpeakerLanes.build(turns, 0, { 0 }).isEmpty())
        assertTrue(SpeakerLanes.build(emptyList(), 1_000, { 0 }).isEmpty())
    }

    @Test
    fun lanesHitTheSegmentUnderTheFingerOrTheNearestOne() {
        val g = SpeakerLanes.build(listOf(SpeakerTurn("S1", 0, 2_000), SpeakerTurn("S2", 5_000, 7_000)), 10_000, { colors.getValue(it) }, 0)
        assertEquals(0, SpeakerLanes.hit(g, 0.1f, 0.01f))
        assertEquals(1, SpeakerLanes.hit(g, 0.55f, 0.01f))
        assertEquals("just before a segment", 1, SpeakerLanes.hit(g, 0.495f, 0.01f))
        assertEquals("just after a segment", 0, SpeakerLanes.hit(g, 0.205f, 0.01f))
        assertEquals("in a silence", -1, SpeakerLanes.hit(g, 0.35f, 0.01f))
        assertEquals(-1, SpeakerLanes.hit(g, 0.95f, 0.01f))
        assertEquals(-1, SpeakerLanes.hit(SpeakerLanes.build(emptyList(), 1, { 0 }), 0.5f, 1f))
    }

    @Test
    fun samplesStopAtTheirEndOrWhenTheyLoseThePlayer() {
        fun check(loaded: String? = "r1", pos: Long = 1_000, playing: Boolean = true, started: Boolean = true, error: String? = null) =
            SampleLogic.check("r1", 5_000, started, loaded, pos, playing, error)
        assertEquals(SampleLogic.Verdict.CONTINUE, check())
        assertEquals("not started yet", SampleLogic.Verdict.CONTINUE, check(playing = false, started = false))
        assertEquals(SampleLogic.Verdict.STOP_AND_RESTORE, check(pos = 5_000))
        assertEquals(SampleLogic.Verdict.STOP_AND_RESTORE, check(playing = false))
        assertEquals(SampleLogic.Verdict.STOP_AND_RESTORE, check(error = "missing"))
        assertEquals(SampleLogic.Verdict.RELEASE, check(loaded = "r2"))
        assertEquals(SampleLogic.Verdict.RELEASE, check(loaded = null))
    }

    @Test
    fun sampleBoundsStayInsideTheRecording() {
        assertEquals(2_000L to 6_000L, SampleLogic.bounds(2_000, 6_000, 60_000))
        assertEquals("too short grows", 2_000L to 3_500L, SampleLogic.bounds(2_000, 2_100, 60_000))
        assertEquals("too long is capped", 0L to SampleLogic.MAX_MS, SampleLogic.bounds(0, 60_000, 0))
        assertEquals("never past the end", 8_500L to 10_000L, SampleLogic.bounds(9_900, 12_000, 10_000))
        assertEquals(0L to 1_000L, SampleLogic.bounds(500, 900, 1_000))
    }

    @Test
    fun enrollmentTiming() {
        assertEquals(0f, EnrollmentLogic.progress(-5), 0f)
        assertEquals(0.5f, EnrollmentLogic.progress(7_500), 1e-6f)
        assertEquals(1f, EnrollmentLogic.progress(99_000), 0f)
        assertFalse(EnrollmentLogic.canFinish(EnrollmentLogic.MIN_MS - 1))
        assertTrue(EnrollmentLogic.canFinish(EnrollmentLogic.MIN_MS))
        assertTrue(EnrollmentLogic.shouldAutoStop(EnrollmentLogic.TARGET_MS))
        assertEquals(15, EnrollmentLogic.secondsLeft(0))
        assertEquals(1, EnrollmentLogic.secondsLeft(14_001))
        assertEquals(0, EnrollmentLogic.secondsLeft(20_000))
    }

    @Test
    fun theDockPromptFollowsRecordingsThatFinishHere() {
        assertEquals("r1", NamingPromptLogic.finished(Live("d1", "r1"), null, "d1"))
        assertEquals("a new recording started right away", "r1", NamingPromptLogic.finished(Live("d1", "r1"), Live("d1", "r2"), "d1"))
        assertNull("still recording", NamingPromptLogic.finished(Live("d1", "r1"), Live("d1", "r1"), "d1"))
        assertNull("another document's recording", NamingPromptLogic.finished(Live("d2", "r1"), null, "d1"))
        assertNull(NamingPromptLogic.finished(null, null, "d1"))

        val on = SpeakerPrefs()
        assertTrue(NamingPromptLogic.visible("r1", recordingActive = false, needsNaming = true, dismissed = false, prefs = on))
        assertFalse(NamingPromptLogic.visible(null, false, true, false, on))
        assertFalse("never while recording", NamingPromptLogic.visible("r1", true, true, false, on))
        assertFalse(NamingPromptLogic.visible("r1", false, false, false, on))
        assertFalse(NamingPromptLogic.visible("r1", false, true, true, on))
        assertFalse(NamingPromptLogic.visible("r1", false, true, false, on.copy(askToName = false)))
        assertFalse(NamingPromptLogic.visible("r1", false, true, false, on.copy(engine = SpeakerEngine.OFF)))
        assertTrue(NamingPromptLogic.visible("r1", false, true, false, on.copy(engine = SpeakerEngine.OPENAI)))
    }

    @Test
    fun numbersForTheTexts() {
        assertEquals(0L, SpeakerNumbers.megabytes(0))
        assertEquals(1L, SpeakerNumbers.megabytes(1))
        assertEquals(12L, SpeakerNumbers.megabytes(12_400_000))
        assertEquals(32L, SpeakerNumbers.megabytes(31_600_000))
        assertEquals(0, SpeakerNumbers.minutes(29_000))
        assertEquals(4, SpeakerNumbers.minutes(250_000))
        assertEquals(1, SpeakerNumbers.seconds(10))
        assertEquals(45, SpeakerNumbers.seconds(45_200))
        assertEquals(82, SpeakerNumbers.percent(0.8249f))
        assertEquals(100, SpeakerNumbers.percent(1.4f))
        assertNull(SpeakerNumbers.fraction(5, 0))
        assertEquals(0.25f, SpeakerNumbers.fraction(8, 32)!!, 1e-6f)
    }

    @Test
    fun sensitivityMapsToThresholds() {
        assertEquals(0.65f, MatchSensitivity.STRICT.threshold)
        assertEquals(0.55f, MatchSensitivity.BALANCED.threshold)
        assertEquals(0.45f, MatchSensitivity.LOOSE.threshold)
        assertEquals(MatchSensitivity.BALANCED, MatchSensitivity.of(SpeakerPrefs().matchThreshold))
        assertEquals(MatchSensitivity.STRICT, MatchSensitivity.of(0.9f))
        assertEquals(MatchSensitivity.LOOSE, MatchSensitivity.of(0.1f))
        assertEquals(MatchSensitivity.LOOSE, MatchSensitivity.of(0.49f))
        assertEquals(MatchSensitivity.BALANCED, MatchSensitivity.of(Float.NaN))
    }

    @Test
    fun expectedSpeakerChoices() {
        assertEquals(listOf(0, 2, 3, 4, 5, 6, 7, 8), SpeakerPrefsLogic.expectedSpeakerChoices)
        assertEquals(0, SpeakerPrefsLogic.expected(SpeakerPrefs(expectedSpeakers = 1)))
        assertEquals(0, SpeakerPrefsLogic.expected(SpeakerPrefs(expectedSpeakers = -3)))
        assertEquals(5, SpeakerPrefsLogic.expected(SpeakerPrefs(expectedSpeakers = 5)))
        assertEquals(8, SpeakerPrefsLogic.expected(SpeakerPrefs(expectedSpeakers = 30)))
    }
}
