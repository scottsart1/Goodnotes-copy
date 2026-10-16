package com.inkwell.notes.audio

import com.inkwell.notes.audio.logic.TranscriptCheckpoint
import com.inkwell.notes.audio.transcription.JobCheckpoint
import com.inkwell.notes.audio.transcription.SpeakerJobPlan
import com.inkwell.notes.core.model.SpeakerEngine
import com.inkwell.notes.core.model.TranscriptSegment
import com.inkwell.notes.speakers.cloud.CloudSpeakerState
import com.inkwell.notes.speakers.cloud.CloudVoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeakerJobPlanTest {
    private val files = listOf("/a/seg_000.m4a", "/a/seg_001.m4a", "/a/seg_002.m4a")

    @Test fun deviceAndOffTranscribeAsBefore() {
        for (engine in listOf(SpeakerEngine.DEVICE, SpeakerEngine.OFF)) {
            val p = SpeakerJobPlan.plan(engine, canDiarize = true, checkpointDone = 1, checkpointDiarized = false)
            assertEquals(SpeakerJobPlan.Plan(cloud = false, resume = true, stripLabels = false, cloudUnavailable = false), p)
        }
    }

    @Test fun openAiLabelsWhenAvailableAndSaysSoWhenNot() {
        assertEquals(
            SpeakerJobPlan.Plan(cloud = true, resume = true, stripLabels = false, cloudUnavailable = false),
            SpeakerJobPlan.plan(SpeakerEngine.OPENAI, canDiarize = true, checkpointDone = 0, checkpointDiarized = false),
        )
        assertEquals(
            SpeakerJobPlan.Plan(cloud = false, resume = true, stripLabels = false, cloudUnavailable = true),
            SpeakerJobPlan.plan(SpeakerEngine.OPENAI, canDiarize = false, checkpointDone = 0, checkpointDiarized = false),
        )
    }

    @Test fun resumedJobsKeepOneWayOfLabelling() {
        // Same mode: continue.
        assertTrue(SpeakerJobPlan.plan(SpeakerEngine.OPENAI, true, 2, checkpointDiarized = true).let { it.cloud && it.resume && !it.stripLabels })
        // Lost OpenAI mid-job: keep the text, drop the labels, tell the user.
        assertEquals(
            SpeakerJobPlan.Plan(cloud = false, resume = true, stripLabels = true, cloudUnavailable = true),
            SpeakerJobPlan.plan(SpeakerEngine.OPENAI, false, 2, checkpointDiarized = true),
        )
        // Switched away from OpenAI mid-job: drop the labels silently.
        assertEquals(
            SpeakerJobPlan.Plan(cloud = false, resume = true, stripLabels = true, cloudUnavailable = false),
            SpeakerJobPlan.plan(SpeakerEngine.DEVICE, true, 2, checkpointDiarized = true),
        )
        // Switched to OpenAI mid-job: every segment needs labels, so start over.
        assertEquals(
            SpeakerJobPlan.Plan(cloud = true, resume = false, stripLabels = false, cloudUnavailable = false),
            SpeakerJobPlan.plan(SpeakerEngine.OPENAI, true, 2, checkpointDiarized = false),
        )
    }

    @Test fun checkpointCarriesSpeakerStateAndStaysReadableBothWays() {
        val segments = listOf(TranscriptSegment(0, 4_000, "Hello", "Ana", "S1"), TranscriptSegment(4_000, 9_000, "Hi", "Speaker 2", "S2"))
        val checkpoint = TranscriptCheckpoint(files, done = 1, segments = segments, language = "en", source = "openai:gpt-4o-transcribe-diarize")
        val state = CloudSpeakerState(
            diarized = true,
            voices = listOf(CloudVoice("S1", "p-ana", "Ana", 4_000, 0, 4_000), CloudVoice("S2", speechMs = 5_000, clipStartMs = 4_000, clipEndMs = 9_000)),
        )
        val text = JobCheckpoint.encode(checkpoint, state)
        assertFalse("windows only, never audio", text.contains("wav", ignoreCase = true))

        val (c, s) = JobCheckpoint.decode(text)!!
        assertEquals(checkpoint, c)
        assertEquals(state, s)
        assertEquals("the plain reader still understands the file", checkpoint, TranscriptCheckpoint.decode(text))

        // A checkpoint written before speaker labelling.
        val (old, oldState) = JobCheckpoint.decode(TranscriptCheckpoint.encode(checkpoint.copy(segments = listOf(TranscriptSegment(0, 1, "x")))))!!
        assertEquals(1, old.done)
        assertEquals(CloudSpeakerState(), oldState)
        // A damaged speaker part does not lose the transcript progress.
        val damaged = TranscriptCheckpoint.encode(checkpoint).replaceFirst("{", "{\"cloudSpeakers\":{\"voices\":7},")
        val (kept, reset) = JobCheckpoint.decode(damaged)!!
        assertEquals(checkpoint, kept)
        assertEquals(CloudSpeakerState(), reset)
        assertNull(JobCheckpoint.decode("not json"))
    }

    @Test fun strippingKeepsTextAndTiming() {
        val c = TranscriptCheckpoint(files, done = 2, segments = listOf(TranscriptSegment(0, 4_000, "Hello", "Ana", "S1"), TranscriptSegment(5_000, 6_000, "Hi")))
        val stripped = JobCheckpoint.withoutSpeakers(c)
        assertEquals(listOf(TranscriptSegment(0, 4_000, "Hello"), TranscriptSegment(5_000, 6_000, "Hi")), stripped.segments)
        assertEquals(2, stripped.done)
        assertTrue(stripped.matches(files))
    }
}
