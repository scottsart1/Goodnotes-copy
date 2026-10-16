package com.inkwell.notes.speakers.cloud

import com.inkwell.notes.core.model.SpeakerTurn
import com.inkwell.notes.core.model.TranscriptSegment
import com.inkwell.notes.speakers.SpeakerReference
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudSpeakersTest {
    private fun seg(start: Long, end: Long, text: String, speaker: String? = null, label: String? = null) =
        TranscriptSegment(start, end, text, speaker, label)

    private fun person(id: String?, name: String, bytes: Int = 8) = SpeakerReference(name, id, ByteArray(bytes) { 1 })

    // ------------------------------------------------------------------ labels

    @Test fun recordingLabelsAreNumberedFromOne() {
        assertEquals(1, CloudLabels.indexOf("S1"))
        assertEquals(12, CloudLabels.indexOf("S12"))
        assertNull(CloudLabels.indexOf("A"))
        assertNull(CloudLabels.indexOf("S0"))
        assertNull(CloudLabels.indexOf("Sx"))
        assertNull(CloudLabels.indexOf(null))
        assertEquals("S1", CloudLabels.next(emptyList()))
        assertEquals("S4", CloudLabels.next(listOf(CloudVoice("S1"), CloudVoice("S3"))))
        assertEquals("openai:gpt-4o-transcribe-diarize", CloudLabels.ENGINE)
    }

    // ------------------------------------------------------------------ mapping

    @Test fun freshProviderLabelsBecomeNewVoicesInOrderOfAppearance() {
        val out = CloudLabelMapper.assign(
            emptyList(), ReferenceSet.EMPTY,
            listOf(seg(5_000, 6_000, "Second", "B"), seg(0, 4_000, "First", "A"), seg(7_000, 9_000, "Third", "a"), seg(9_500, 9_900, "Nobody")),
        )
        assertEquals(listOf("First", "Second", "Third", "Nobody"), out.segments.map { it.text })
        assertEquals(listOf("S1", "S2", "S1", null), out.segments.map { it.speakerLabel })
        assertEquals(listOf("Speaker 1", "Speaker 2", "Speaker 1", null), out.segments.map { it.speaker })
        assertEquals(listOf("S1", "S2"), out.voices.map { it.label })
    }

    @Test fun echoedReferencesKeepVoicesAcrossFilesAndLinkPeople() {
        val heard = listOf(CloudVoice("S1", speechMs = 50_000), CloudVoice("S2", personId = "p-ana", personName = "Ana", speechMs = 20_000))
        val refs = ReferenceSet(
            emptyList(),
            mapOf(
                "S1" to ReferenceTarget.Voice("S1"),
                "S2" to ReferenceTarget.Voice("S2"),
                "P1" to ReferenceTarget.Person("p-raj", "Raj"),
                "P2" to ReferenceTarget.Person("p-ana", "Ana"),
            ),
        )
        val out = CloudLabelMapper.assign(
            heard, refs,
            listOf(
                seg(120_000, 125_000, "Back to the plan", "S2"),
                seg(126_000, 130_000, "Sure", "p1"),
                seg(131_000, 133_000, "Me again", "S1"),
                seg(134_000, 136_000, "Echo of Ana's own reference", "P2"),
                seg(137_000, 139_000, "Someone new", "A"),
            ),
        )
        assertEquals(listOf("S2", "S3", "S1", "S2", "S4"), out.segments.map { it.speakerLabel })
        assertEquals(listOf("Ana", "Raj", "Speaker 1", "Ana", "Speaker 4"), out.segments.map { it.speaker })
        assertEquals(listOf("S1", "S2", "S3", "S4"), out.voices.map { it.label })
        assertEquals("p-raj", out.voices.first { it.label == "S3" }.personId)
        assertNull(out.voices.first { it.label == "S4" }.personId)
        // Existing stats are untouched; the caller refreshes them from the blocks.
        assertEquals(50_000L, out.voices.first { it.label == "S1" }.speechMs)
    }

    @Test fun anEchoForAnUnknownVoiceIsTreatedAsNew() {
        val refs = ReferenceSet(emptyList(), mapOf("S7" to ReferenceTarget.Voice("S7")))
        val out = CloudLabelMapper.assign(listOf(CloudVoice("S1")), refs, listOf(seg(0, 1_000, "Hi", "S7")), unnamed = { "Voice $it" })
        assertEquals("S2", out.segments.single().speakerLabel)
        assertEquals("Voice 2", out.segments.single().speaker)
    }

    // ------------------------------------------------------------------ planning

    @Test fun plannerPutsHeardVoicesFirstThenPeopleUnderAliases() = runBlocking {
        val voices = listOf(
            CloudVoice("S1", speechMs = 10_000, clipStartMs = 1_000, clipEndMs = 6_000),
            CloudVoice("S2", personId = "p-ana", personName = "Ana", speechMs = 40_000, clipStartMs = 20_000, clipEndMs = 30_000),
            CloudVoice("S3", speechMs = 900),
        )
        val people = listOf(
            person("p-me", "Sam"), person(null, "S1"), person("p-ana", "Ana"), person("p-raj", "Raj"), person("p-lee", "Lee"),
        )
        val cut = mutableListOf<Pair<Long, Long>>()
        val set = ReferencePlanner.plan(voices, people) { s, e -> cut += s to e; ByteArray(4) { s.toByte() } }
        assertEquals(listOf("S2", "S1", "P1", "P2"), set.references.map { it.name })
        assertEquals(listOf("p-ana", null, "p-me", "p-raj"), set.references.map { it.personId })
        assertEquals(listOf(20_000L to 30_000L, 1_000L to 6_000L), cut)
        assertEquals(ReferenceTarget.Person("p-me", "Sam"), set.target("p1"))
        assertEquals(ReferenceTarget.Voice("S2"), set.target(" S2 "))
        assertNull(set.target("A"))
        assertNull("names never travel", set.references.firstOrNull { it.name == "Sam" || it.name == "Raj" })
    }

    @Test fun plannerFallsBackToStoredVoiceAndSkipsUnusableClips() = runBlocking {
        val voices = listOf(
            CloudVoice("S1", personId = "p-ana", personName = "Ana", speechMs = 5_000),
            CloudVoice("S2", speechMs = 4_000, clipStartMs = 0, clipEndMs = 4_000),
            CloudVoice("S3", speechMs = 3_000, clipStartMs = 5_000, clipEndMs = 8_000),
        )
        val ana = person("p-ana", "Ana", bytes = 16)
        val set = ReferencePlanner.plan(voices, listOf(ana, person("p-bad", "Bad", bytes = 0)), max = 4) { s, _ -> if (s == 0L) null else ByteArray(8) }
        assertEquals(listOf("S1", "S3"), set.references.map { it.name })
        assertArrayEquals("a recognised voice without a clip yet uses the stored one", ana.wav, set.references[0].wav)
        assertEquals(ReferenceTarget.Voice("S1"), set.target("S1"))
        assertTrue(ReferencePlanner.plan(voices, listOf(ana), max = 0) { _, _ -> ByteArray(8) }.references.isEmpty())
    }

    @Test fun plannerNeverSendsMoreThanFour() = runBlocking {
        val voices = (1..6).map { CloudVoice("S$it", speechMs = it * 1_000L, clipStartMs = it * 10_000L, clipEndMs = it * 10_000L + 5_000) }
        val set = ReferencePlanner.plan(voices, listOf(person("p", "P")), max = 9) { _, _ -> ByteArray(8) }
        assertEquals(listOf("S6", "S5", "S4", "S3"), set.references.map { it.name })
    }

    @Test fun referenceSourceLoadsPeopleOnceAndCachesClipsPerWindow() = runBlocking {
        var loads = 0
        var cuts = 0
        val source = CloudReferenceSource(
            loadPeople = { loads++; listOf(person("p-me", "Sam")) },
            cutClip = { _, _ -> cuts++; ByteArray(8) },
        )
        val v1 = listOf(CloudVoice("S1", speechMs = 5_000, clipStartMs = 0, clipEndMs = 5_000))
        source.referencesFor(emptyList())
        source.referencesFor(v1)
        source.referencesFor(v1)
        assertEquals(1, loads)
        assertEquals(1, cuts)
        source.referencesFor(listOf(CloudVoice("S1", speechMs = 9_000, clipStartMs = 10_000, clipEndMs = 18_000)))
        assertEquals(2, cuts)
        source.referencesFor(v1)
        assertEquals("evicted windows are cut again", 3, cuts)
    }

    @Test fun referenceSourceSurvivesFailingCollaborators() = runBlocking {
        val source = CloudReferenceSource(
            loadPeople = { error("store locked") },
            cutClip = { _, _ -> throw java.io.IOException("decoder") },
        )
        val set = source.referencesFor(listOf(CloudVoice("S1", speechMs = 5_000, clipStartMs = 0, clipEndMs = 5_000)))
        assertTrue(set.references.isEmpty())
    }

    // ------------------------------------------------------------------ the whole loop

    /** Who really speaks when; a clip of a stretch "is" that voice (its id as bytes). */
    private class Spoken(val file: Int, val start: Long, val end: Long, val voice: String)

    private val truth = listOf(
        Spoken(0, 0, 20_000, "ana"), Spoken(0, 21_000, 40_000, "x"), Spoken(0, 41_000, 60_000, "ana"),
        Spoken(1, 120_000, 135_000, "x"), Spoken(1, 136_000, 150_000, "y"), Spoken(1, 151_000, 170_000, "x"),
        Spoken(2, 240_000, 250_000, "y"), Spoken(2, 251_000, 270_000, "ana"), Spoken(2, 271_000, 272_000, "z"), Spoken(2, 273_000, 280_000, "y"),
    )

    /** A provider that recognises a voice only through a reference clip of it; labels are local to each file. */
    private fun provider(file: Int, refs: List<SpeakerReference>): List<TranscriptSegment> {
        val fresh = LinkedHashMap<String, String>()
        return truth.filter { it.file == file }.map { t ->
            val label = refs.firstOrNull { String(it.wav) == t.voice }?.name ?: fresh.getOrPut(t.voice) { ('A' + fresh.size).toString() }
            seg(t.start, t.end, "${t.voice} speaking", label)
        }
    }

    private fun source() = CloudReferenceSource(
        loadPeople = { listOf(SpeakerReference("Ana", "p-ana", "ana".toByteArray())) },
        cutClip = { s, e -> truth.firstOrNull { s >= it.start && e <= it.end }?.voice?.toByteArray() },
    )

    private suspend fun step(file: Int, state: CloudSpeakerState, done: List<TranscriptSegment>, refs: CloudReferenceSource): Pair<CloudSpeakerState, List<TranscriptSegment>> {
        val set = refs.referencesFor(state.voices)
        val assigned = CloudLabelMapper.assign(state.voices, set, provider(file, set.references))
        val all = done + assigned.segments
        return state.copy(voices = CloudTurns.refresh(assigned.voices, all)) to all
    }

    @Test fun rollingReferencesKeepOneLabelPerVoiceAcrossSegmentsAndResumes() = runBlocking {
        var state = CloudSpeakerState(diarized = true)
        var segments = emptyList<TranscriptSegment>()
        val refs = source()
        for (file in 0..1) step(file, state, segments, refs).let { state = it.first; segments = it.second }

        // The job is stopped here and resumed in a new process from its checkpoint.
        val saved = com.inkwell.notes.audio.transcription.JobCheckpoint.encode(
            com.inkwell.notes.audio.logic.TranscriptCheckpoint(listOf("a", "b", "c"), done = 2, segments = segments), state,
        )
        val (checkpoint, restored) = com.inkwell.notes.audio.transcription.JobCheckpoint.decode(saved)!!
        val (finalState, all) = step(2, restored, checkpoint.segments, source())

        val labelOf = all.groupBy({ it.text.substringBefore(' ') }, { it.speakerLabel }).mapValues { it.value.toSet() }
        assertEquals(mapOf("ana" to setOf("S1"), "x" to setOf("S2"), "y" to setOf("S3"), "z" to setOf("S4")), labelOf)
        assertEquals(setOf("Ana"), all.filter { it.speakerLabel == "S1" }.map { it.speaker }.toSet())
        assertEquals(setOf("Speaker 3"), all.filter { it.speakerLabel == "S3" }.map { it.speaker }.toSet())

        val result = CloudTurns.result("rec", all, finalState.voices, totalMs = 300_000)
        assertEquals(listOf("S1", "S2", "S3", "S4"), result.speakers.map { it.label })
        assertEquals(listOf("p-ana", null, null, null), result.speakers.map { it.personId })
        assertEquals(53_000L, result.speakers[1].speechMs)
        for (i in 1 until result.turns.size) assertTrue(result.turns[i].startMs >= result.turns[i - 1].endMs)
    }

    // ------------------------------------------------------------------ turns and results

    @Test fun turnsMergeShortGapsAndNeverOverlap() {
        val segs = listOf(
            seg(0, 4_000, "a", "Speaker 1", "S1"),
            seg(5_000, 9_000, "b", "Speaker 1", "S1"),
            seg(8_500, 12_000, "c", "Speaker 2", "S2"),
            seg(9_000, 10_000, "inside", "Speaker 1", "S1"),
            seg(15_000, 15_000, "zero length", "Speaker 2", "S2"),
            seg(16_000, 20_000, "d", null, null),
            seg(20_000, 22_000, "e", "Speaker 2", "S2"),
        )
        val turns = CloudTurns.turns(segs)
        assertEquals(listOf(SpeakerTurn("S1", 0, 9_000), SpeakerTurn("S2", 9_000, 12_000), SpeakerTurn("S2", 20_000, 22_000)), turns)
        for (i in 1 until turns.size) assertTrue(turns[i].startMs >= turns[i - 1].endMs)
    }

    @Test fun clipWindowsComeFromTheLongestStretch() {
        val turns = listOf(SpeakerTurn("S1", 0, 1_500), SpeakerTurn("S1", 10_000, 34_000), SpeakerTurn("S2", 40_000, 43_000), SpeakerTurn("S3", 50_000, 51_000))
        assertEquals(17_000L to 27_000L, CloudTurns.clipWindow(turns, "S1"))
        assertEquals(40_000L to 43_000L, CloudTurns.clipWindow(turns, "S2"))
        assertNull(CloudTurns.clipWindow(turns, "S3"))
        assertNull(CloudTurns.clipWindow(turns, "S9"))
        // Too short for a reference: a 2 s sample inside the recording for "play sample".
        assertEquals(49_500L to 51_500L, CloudTurns.sampleWindow(turns, emptyList(), "S3", totalMs = 60_000))
        assertEquals("kept inside the recording", 57_000L to 59_000L, CloudTurns.sampleWindow(listOf(SpeakerTurn("S4", 58_500, 59_000)), emptyList(), "S4", totalMs = 59_000))
    }

    @Test fun refreshAndResultDescribeEveryVoiceWithBlocks() {
        val segs = listOf(
            seg(0, 6_000, "Opening", "Ana", "S1"),
            seg(6_000, 7_000, "Yes", "Speaker 2", "S2"),
            seg(7_500, 19_500, "Long answer", "Speaker 2", "S2"),
            seg(30_000, 30_000, "Hm", "Speaker 3", "S3"),
        )
        val voices = CloudTurns.refresh(listOf(CloudVoice("S1", "p-ana", "Ana"), CloudVoice("S2"), CloudVoice("S3"), CloudVoice("S4")), segs)
        assertEquals(listOf(6_000L, 13_000L, 0L, 0L), voices.map { it.speechMs })
        assertEquals(0L to 6_000L, voices[0].clipStartMs!! to voices[0].clipEndMs!!)
        assertEquals("centred 10 s of the 6-19.5 s turn", 7_750L to 17_750L, voices[1].clipStartMs!! to voices[1].clipEndMs!!)
        assertNull(voices[2].clipStartMs)

        val result = CloudTurns.result("rec", segs, voices.reversed(), totalMs = 31_000)
        assertEquals("rec", result.recordingId)
        assertEquals(CloudLabels.ENGINE, result.engine)
        assertEquals(listOf("S1", "S2", "S3"), result.speakers.map { it.label })
        assertEquals(listOf("p-ana", null, null), result.speakers.map { it.personId })
        assertTrue(result.speakers.all { it.confidence == null && it.centroid == null })
        assertEquals(29_000L to 31_000L, result.speakers[2].let { it.sampleStartMs to it.sampleEndMs })
        assertEquals(listOf(SpeakerTurn("S1", 0, 6_000), SpeakerTurn("S2", 6_000, 19_500)), result.turns)
        for (s in result.speakers) assertTrue(s.sampleEndMs - s.sampleStartMs in 2_000..10_000)
    }
}
