package com.inkwell.notes.speakers.cloud

import com.inkwell.notes.audio.logic.TranscriptCheckpoint
import com.inkwell.notes.audio.transcription.JobCheckpoint
import com.inkwell.notes.audio.transcription.SpeakerJobPlan
import com.inkwell.notes.core.model.SpeakerEngine
import com.inkwell.notes.core.model.TranscriptSegment
import com.inkwell.notes.speakers.SpeakerReference
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The provider's labels are local to each 120 s file; these cases drive the worker's mapping
 * (planner -> provider -> mapper -> refresh -> checkpoint) through the awkward sequences.
 */
class CloudMappingEdgeCasesTest {
    private fun seg(start: Long, end: Long, text: String, speaker: String? = null, label: String? = null) =
        TranscriptSegment(start, end, text, speaker, label)

    /** A simulated provider: a block's voice id, echoed as a reference name when a clip of that voice was sent. */
    private class Block(val start: Long, val end: Long, val voice: String)

    private fun provider(blocks: List<Block>, refs: List<SpeakerReference>): List<TranscriptSegment> {
        val fresh = LinkedHashMap<String, String>()
        return blocks.map { b ->
            val label = refs.firstOrNull { String(it.wav) == b.voice }?.name ?: fresh.getOrPut(b.voice) { ('A' + fresh.size).toString() }
            seg(b.start, b.end, "${b.voice} speaking", label)
        }
    }

    /** Clips "are" the voice that speaks throughout the window (null when the window straddles two voices). */
    private fun source(truth: List<Block>, people: List<SpeakerReference>) = CloudReferenceSource(
        loadPeople = { people },
        cutClip = { s, e -> truth.firstOrNull { s >= it.start && e <= it.end }?.voice?.toByteArray() },
    )

    private class Step(val state: CloudSpeakerState, val segments: List<TranscriptSegment>, val sent: List<String>)

    private suspend fun step(state: CloudSpeakerState, done: List<TranscriptSegment>, file: List<Block>, refs: CloudReferenceSource): Step {
        val set = refs.referencesFor(state.voices)
        val assigned = CloudLabelMapper.assign(state.voices, set, provider(file, set.references))
        val all = done + assigned.segments
        return Step(state.copy(voices = CloudTurns.refresh(assigned.voices, all)), all, set.references.map { it.name })
    }

    private fun labelsByVoice(segments: List<TranscriptSegment>) =
        segments.groupBy({ it.text.substringBefore(' ') }, { it.speakerLabel }).mapValues { it.value.toSet() }

    @Test fun theSameLetterInALaterFileIsADifferentVoice() = runBlocking {
        val file0 = listOf(Block(0, 30_000, "x"))
        val file1 = listOf(Block(120_000, 130_000, "x"), Block(131_000, 150_000, "y"))
        val refs = source(file0 + file1, emptyList())
        var state = CloudSpeakerState(diarized = true)
        var s = step(state, emptyList(), file0, refs)
        assertEquals(listOf("S1"), s.state.voices.map { it.label })
        s = step(s.state, s.segments, file1, refs)
        assertEquals("the heard voice travels as its label", listOf("S1"), s.sent)
        // The provider called y "A" in file 1, as it called x "A" in file 0: a different voice.
        assertEquals(mapOf("x" to setOf("S1"), "y" to setOf("S2")), labelsByVoice(s.segments))
        assertEquals(listOf("Speaker 1", "Speaker 2"), s.segments.map { it.speaker }.distinct())
    }

    @Test fun aKnownPersonIsRecognisedThroughHerAliasThenThroughHerVoiceLabel() = runBlocking {
        val ana = SpeakerReference("Ana", "p-ana", "ana".toByteArray())
        val file0 = listOf(Block(0, 20_000, "ana"), Block(21_000, 40_000, "x"))
        val file1 = listOf(Block(120_000, 140_000, "x"), Block(141_000, 160_000, "ana"))
        val refs = source(file0 + file1, listOf(ana))
        var s = step(CloudSpeakerState(diarized = true), emptyList(), file0, refs)
        assertEquals("only an alias is sent, never the name", listOf("P1"), s.sent)
        assertEquals("Ana", s.segments.first().speaker)
        assertEquals("S1", s.segments.first().speakerLabel)
        s = step(s.state, s.segments, file1, refs)
        assertEquals("heard voices by speech, Ana no longer aliased", listOf("S1", "S2"), s.sent)
        assertEquals(mapOf("ana" to setOf("S1"), "x" to setOf("S2")), labelsByVoice(s.segments))
        assertEquals(setOf("Ana"), s.segments.filter { it.speakerLabel == "S1" }.map { it.speaker }.toSet())
        val result = CloudTurns.result("rec", s.segments, s.state.voices, 160_000)
        assertEquals(listOf("p-ana", null), result.speakers.map { it.personId })
        assertEquals(listOf(39_000L, 39_000L), result.speakers.map { it.speechMs })
    }

    @Test fun aPersonHeardLateIsLinkedWhenHerAliasFinallyFitsIn() = runBlocking {
        // Five voices in file 0 (S1..S5); only four references fit, so Raj (p-raj) is not aliased
        // until a slot frees up. In file 1 only two voices speak, leaving room for the alias.
        val raj = SpeakerReference("Raj", "p-raj", "raj".toByteArray())
        val file0 = (0 until 5).map { i -> Block(i * 20_000L, i * 20_000L + 15_000 + i * 1_000, "v$i") }
        val file1 = listOf(Block(120_000, 150_000, "v4"), Block(151_000, 170_000, "raj"))
        val refs = source(file0 + file1, listOf(raj))
        var s = step(CloudSpeakerState(diarized = true), emptyList(), file0, refs)
        assertEquals(listOf("S1", "S2", "S3", "S4", "S5"), s.state.voices.map { it.label })
        s = step(s.state, s.segments, file1, refs)
        // Voices still fill the four slots (most speech first): Raj is new to the provider.
        assertEquals(listOf("S5", "S4", "S3", "S2"), s.sent)
        assertEquals(setOf("S5"), labelsByVoice(s.segments).getValue("v4"))
        assertEquals(setOf("S6"), labelsByVoice(s.segments).getValue("raj"))
        assertNull(s.state.voices.first { it.label == "S6" }.personId)
    }

    @Test fun resumingAfterEveryFileReproducesAnUninterruptedRun() = runBlocking {
        val ana = SpeakerReference("Ana", "p-ana", "ana".toByteArray())
        val files = listOf(
            listOf(Block(0, 25_000, "x"), Block(26_000, 50_000, "ana"), Block(51_000, 60_000, "x")),
            listOf(Block(120_000, 135_000, "y"), Block(136_000, 170_000, "x")),
            listOf(Block(240_000, 250_000, "ana"), Block(251_000, 280_000, "y"), Block(281_000, 283_000, "z")),
        )
        val truth = files.flatten()

        // Uninterrupted.
        var straight = step(CloudSpeakerState(diarized = true), emptyList(), files[0], source(truth, listOf(ana)))
        val refsStraight = source(truth, listOf(ana))
        for (f in files.drop(1)) straight = step(straight.state, straight.segments, f, refsStraight)

        // Killed after every file: the checkpoint is all that survives, and the next process
        // builds a fresh reference source.
        var resumed = step(CloudSpeakerState(diarized = true), emptyList(), files[0], source(truth, listOf(ana)))
        for ((i, f) in files.withIndex().drop(1)) {
            val text = JobCheckpoint.encode(TranscriptCheckpoint(listOf("a", "b", "c"), done = i, segments = resumed.segments), resumed.state)
            val (checkpoint, state) = JobCheckpoint.decode(text)!!
            val plan = SpeakerJobPlan.plan(SpeakerEngine.OPENAI, canDiarize = true, checkpointDone = checkpoint.done, checkpointDiarized = state.diarized)
            assertTrue(plan.cloud && plan.resume && !plan.stripLabels)
            resumed = step(state, checkpoint.segments, f, source(truth, listOf(ana)))
        }

        assertEquals(straight.segments, resumed.segments)
        assertEquals(straight.state, resumed.state)
        assertEquals(mapOf("x" to setOf("S1"), "ana" to setOf("S2"), "y" to setOf("S3"), "z" to setOf("S4")), labelsByVoice(resumed.segments))
        assertEquals(setOf("Ana"), resumed.segments.filter { it.speakerLabel == "S2" }.map { it.speaker }.toSet())
    }

    @Test fun switchingTheEngineMidJobKeepsTextAndDropsLabelsOnce() {
        val labelled = listOf(seg(0, 4_000, "Hello", "Ana", "S1"), seg(5_000, 9_000, "Hi", "Speaker 2", "S2"))
        val state = CloudSpeakerState(diarized = true, voices = listOf(CloudVoice("S1", "p-ana", "Ana", 4_000, 0, 4_000), CloudVoice("S2", speechMs = 4_000)))
        val text = JobCheckpoint.encode(TranscriptCheckpoint(listOf("a", "b"), done = 1, segments = labelled), state)
        val (checkpoint, saved) = JobCheckpoint.decode(text)!!
        val plan = SpeakerJobPlan.plan(SpeakerEngine.DEVICE, canDiarize = true, checkpoint.done, saved.diarized)
        assertEquals(SpeakerJobPlan.Plan(cloud = false, resume = true, stripLabels = true, cloudUnavailable = false), plan)
        val stripped = JobCheckpoint.withoutSpeakers(checkpoint)
        assertEquals(listOf("Hello", "Hi"), stripped.segments.map { it.text })
        assertTrue(stripped.segments.all { it.speaker == null && it.speakerLabel == null })
        // The second run writes an unlabelled checkpoint; a third run resumes it as plain.
        val again = JobCheckpoint.decode(JobCheckpoint.encode(stripped.copy(done = 2), CloudSpeakerState(diarized = false)))!!
        val plan3 = SpeakerJobPlan.plan(SpeakerEngine.DEVICE, canDiarize = true, again.first.done, again.second.diarized)
        assertEquals(SpeakerJobPlan.Plan(cloud = false, resume = true, stripLabels = false, cloudUnavailable = false), plan3)
    }

    @Test fun unlabelledBlocksNeverCreateVoicesOrTurns() {
        val out = CloudLabelMapper.assign(
            listOf(CloudVoice("S1", speechMs = 3_000)), ReferenceSet.EMPTY,
            listOf(seg(0, 2_000, "No label"), seg(2_000, 3_000, "Blank", "  "), seg(3_000, 5_000, "Labelled", "A")),
        )
        assertEquals(listOf(null, null, "S2"), out.segments.map { it.speakerLabel })
        assertEquals(listOf(null, null, "Speaker 2"), out.segments.map { it.speaker })
        assertEquals(listOf("S1", "S2"), out.voices.map { it.label })
        val result = CloudTurns.result("rec", out.segments, CloudTurns.refresh(out.voices, out.segments), 5_000)
        assertEquals("a voice without blocks in this transcript is not reported", listOf("S2"), result.speakers.map { it.label })
        assertEquals(listOf("S2"), result.turns.map { it.label })
    }

    @Test fun aVoiceWithOnlyAZeroLengthBlockStillGetsAValidSample() {
        val segs = CloudLabelMapper.assign(emptyList(), ReferenceSet.EMPTY, listOf(seg(5_000, 5_000, "Hm", "A"), seg(6_000, 20_000, "Long", "B"))).let {
            CloudTurns.result("rec", it.segments, CloudTurns.refresh(it.voices, it.segments), 20_000)
        }
        assertEquals(listOf("S1", "S2"), segs.speakers.map { it.label })
        val hm = segs.speakers.first { it.label == "S1" }
        assertEquals(0L, hm.speechMs)
        assertTrue(hm.sampleEndMs - hm.sampleStartMs in 2_000..10_000)
        assertTrue(hm.sampleStartMs >= 0 && hm.sampleEndMs <= 20_000)
        assertEquals("no turn for a zero-length block", listOf("S2"), segs.turns.map { it.label })
    }

    @Test fun aliasesAndLabelsCanNeverCollideWithTheProvidersLetters() = runBlocking {
        val people = ('a'..'z').map { SpeakerReference("Person $it", "p-$it", "$it".toByteArray()) }
        val set = ReferencePlanner.plan(emptyList(), people) { _, _ -> null }
        assertEquals(listOf("P1", "P2", "P3", "P4"), set.references.map { it.name })
        for (letter in 'A'..'Z') assertNull(set.target(letter.toString()))
        assertNull(set.target("Person a"))
    }

    @Test fun clipWindowsAlwaysFitWhatTheProviderAccepts() {
        val segs = listOf(
            seg(0, 1_999, "too short", "Speaker 1", "S1"),
            seg(3_000, 5_000, "exactly two", "Speaker 2", "S2"),
            seg(10_000, 10_000 + 10_000, "exactly ten", "Speaker 3", "S3"),
            seg(30_000, 30_000 + 10_001, "just over", "Speaker 4", "S4"),
        )
        val voices = CloudTurns.refresh(listOf(CloudVoice("S1"), CloudVoice("S2"), CloudVoice("S3"), CloudVoice("S4")), segs)
        assertNull(voices[0].clipStartMs)
        for (v in voices.drop(1)) {
            val len = v.clipEndMs!! - v.clipStartMs!!
            assertTrue("${v.label}: $len", len in CloudTurns.CLIP_MIN_MS..CloudTurns.CLIP_MAX_MS)
        }
        assertEquals(10_000L, voices[3].clipEndMs!! - voices[3].clipStartMs!!)
    }
}
