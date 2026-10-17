package com.inkwell.notes.speakers

import com.inkwell.notes.core.model.SpeakerTurn
import com.inkwell.notes.core.storage.db.PersonEntity
import com.inkwell.notes.core.storage.db.RecordingSpeakerEntity
import com.inkwell.notes.core.storage.db.TranscriptSegmentEntity
import com.inkwell.notes.speakers.logic.SpeakerRecords
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeakerRecordsTest {
    private fun person(id: String, name: String, color: Int = 0, isMe: Boolean = false) = PersonEntity(id, name, isMe, color, 1, 2, 3)

    private fun row(label: String, personId: String? = null, confirmed: Boolean = false, speech: Long = 10_000, start: Long = 0, end: Long = 5_000, engine: String = "device:x") =
        RecordingSpeakerEntity("rec", label, personId, null, confirmed, speech, start, end, engine)

    private fun seg(start: Long, end: Long, text: String, label: String? = null, speaker: String? = null, source: String = "openai:whisper-1") =
        TranscriptSegmentEntity(rowId = 7, recordingId = "rec", startMs = start, endMs = end, text = text, speaker = speaker, source = source, speakerLabel = label)

    private fun t(label: String, start: Long, end: Long) = SpeakerTurn(label, start, end)

    @Test
    fun recordingSpeakersCarryNamesAndColours() {
        val people = mapOf("p" to person("p", "Priya", color = 0))
        val list = SpeakerRecords.recordingSpeakers(listOf(row("S2", "p", true), row("S10"), row("S1")), people)
        assertEquals(listOf("S1", "S2", "S10"), list.map { it.label })
        assertEquals(listOf("Speaker 1", "Priya", "Speaker 10"), list.map { it.displayName })
        assertEquals(0, list[1].colorIndex)
        assertEquals(listOf(1, 0, 2), list.map { it.colorIndex })
        // A deleted person (row still pointing at them) reads as unnamed.
        assertNull(SpeakerRecords.recordingSpeakers(listOf(row("S1", "gone")), emptyMap()).single().personId)
    }

    @Test
    fun relabelAlignsAndSplitsWhilePreservingSource() {
        val text = "First person talks about the plan for a while. Second person disagrees with most of it."
        val segs = listOf(seg(0, 20_000, text, source = "openai:gpt-4o-transcribe"), seg(20_000, 22_000, "Okay."))
        val turns = listOf(t("S1", 0, 10_000), t("S2", 10_000, 23_000))
        val out = SpeakerRecords.relabel(segs, turns, mapOf("S1" to "Priya", "S2" to "Speaker 2"), keepExistingLabels = true)!!
        assertEquals(3, out.size)
        assertEquals(listOf("S1", "S2", "S2"), out.map { it.speakerLabel })
        assertEquals(listOf("Priya", "Speaker 2", "Speaker 2"), out.map { it.speaker })
        assertEquals(listOf("openai:gpt-4o-transcribe", "openai:gpt-4o-transcribe", "openai:whisper-1"), out.map { it.source })
        assertTrue(out.all { it.rowId == 0L && it.recordingId == "rec" })
        assertEquals(text.filterNot { it.isWhitespace() }, out.take(2).joinToString("") { it.text }.filterNot { it.isWhitespace() })
    }

    @Test
    fun relabelKeepsCloudLabelsAndOnlyRefreshesNames() {
        val segs = listOf(seg(0, 5_000, "Hi", label = "S1", speaker = "Speaker 1"), seg(5_000, 9_000, "Hello", label = "S2", speaker = "A"))
        val out = SpeakerRecords.relabel(segs, turns = listOf(t("S2", 0, 9_000)), names = mapOf("S1" to "Ana"), keepExistingLabels = true)!!
        assertEquals(listOf("S1", "S2"), out.map { it.speakerLabel })
        assertEquals(listOf("Ana", "Speaker 2"), out.map { it.speaker })
        // Already up to date: no write.
        assertNull(SpeakerRecords.relabel(out, emptyList(), mapOf("S1" to "Ana"), keepExistingLabels = true))
        // A device re-analysis re-aligns even labelled blocks.
        val realigned = SpeakerRecords.relabel(segs, listOf(t("S2", 0, 9_000)), mapOf("S2" to "Bo"), keepExistingLabels = false)!!
        assertEquals(listOf("S2", "S2"), realigned.map { it.speakerLabel })
        assertEquals(listOf("Bo", "Bo"), realigned.map { it.speaker })
    }

    @Test
    fun relabelWithoutTurnsOrSegmentsChangesNothing() {
        assertNull(SpeakerRecords.relabel(emptyList(), listOf(t("S1", 0, 1)), emptyMap(), false))
        assertNull(SpeakerRecords.relabel(listOf(seg(0, 1_000, "x")), emptyList(), emptyMap(), false))
    }

    private fun result(vararg speakers: DetectedSpeaker, turns: List<SpeakerTurn>) = DiarizationResult("rec", "device:x", turns, speakers.toList())

    private fun detected(label: String, personId: String? = null, confidence: Float? = null, start: Long = 0, end: Long = 4_000) =
        DetectedSpeaker(label, personId, confidence, FloatArray(4) { 1f }, 4_000, start, end)

    @Test
    fun carryOverKeepsTheUsersDecisions() {
        val oldTurns = listOf(t("S1", 0, 10_000), t("S2", 10_000, 20_000))
        val oldRows = listOf(row("S1", "ana", confirmed = true), row("S2", null, confirmed = true))
        // Re-analysis numbered the voices the other way round.
        val r = result(detected("S1"), detected("S2"), turns = listOf(t("S1", 10_000, 20_000), t("S2", 0, 10_000)))
        val a = SpeakerRecords.carryOver(r, oldRows, oldTurns, setOf("ana"))
        assertEquals(SpeakerRecords.Assignment("ana", null, true), a["S2"])
        // A skipped (dismissed) voice stays dismissed.
        assertEquals(SpeakerRecords.Assignment(null, null, true), a["S1"])
    }

    @Test
    fun carryOverUsesEngineRecognitionForTheRestAndSkipsUnknownPeople() {
        val r = result(detected("S1", "bo", 0.9f), detected("S2", "ghost", 0.8f), detected("S3", "bo", 0.7f), turns = listOf(t("S1", 0, 1_000)))
        val a = SpeakerRecords.carryOver(r, emptyList(), emptyList(), setOf("bo"))
        assertEquals(SpeakerRecords.Assignment("bo", 0.9f, false), a["S1"])
        assertFalse("S2" in a)
        assertFalse("one voice per person", "S3" in a)
        // An automatic match from before (not confirmed) is not carried: it is re-matched.
        val again = SpeakerRecords.carryOver(result(detected("S1"), turns = listOf(t("S1", 0, 5_000))), listOf(row("S1", "bo", confirmed = false)), listOf(t("S1", 0, 5_000)), setOf("bo"))
        assertTrue(again.isEmpty())
    }

    @Test
    fun rowsForUsesTurnSpeechAndValidSampleWindows() {
        val turns = listOf(t("S1", 0, 6_000), t("S2", 6_000, 30_000), t("S3", 30_000, 30_400))
        val r = result(detected("S1", start = 0, end = 4_000), detected("S2", start = 6_000, end = 25_000), turns = turns)
        val rows = SpeakerRecords.rowsFor(r, turns, mapOf("S1" to SpeakerRecords.Assignment("p", 0.7f, false)), totalMs = 31_000)
        assertEquals(listOf("S1", "S2", "S3"), rows.map { it.label })
        assertEquals(listOf(6_000L, 24_000L, 400L), rows.map { it.speechMs })
        assertEquals("p" to 0.7f, rows[0].personId to rows[0].confidence)
        assertEquals(0L to 4_000L, rows[0].sampleStartMs to rows[0].sampleEndMs)
        // 19 s sample is out of range: re-picked from the turns (2-10 s).
        assertTrue(rows[1].sampleEndMs - rows[1].sampleStartMs in 2_000L..10_000L)
        assertTrue(rows.all { it.engine == "device:x" && it.recordingId == "rec" })
    }

    @Test
    fun refreshRecomputesSpeechAndDropsEmptiedVoices() {
        val rows = listOf(row("S1", speech = 1, start = 0, end = 4_000), row("S2", speech = 1, start = 9_000, end = 13_000), row("S3"))
        val turns = listOf(t("S1", 0, 12_000), t("S2", 12_000, 20_000))
        val r = SpeakerRecords.refresh(rows, turns, transcriptLabels = setOf("S1", "S2"), totalMs = 20_000)
        assertEquals(listOf("S3"), r.deletes)
        val s1 = r.upserts.first { it.label == "S1" }
        assertEquals(12_000L, s1.speechMs)
        assertEquals(0L to 4_000L, s1.sampleStartMs to s1.sampleEndMs)
        // S2's sample now mostly belongs to S1: re-picked inside S2's turn.
        val s2 = r.upserts.first { it.label == "S2" }
        assertTrue(s2.sampleStartMs >= 12_000)
        // A voice with blocks but no turns is kept.
        assertTrue(SpeakerRecords.refresh(listOf(row("S9")), emptyList(), setOf("S9"), 0).deletes.isEmpty())
    }

    @Test
    fun mergedKeepsTheNamedPerson() {
        val named = row("S1", "ana", confirmed = true)
        val unnamed = row("S2")
        assertEquals("ana", SpeakerRecords.merged(named, unnamed).personId)
        assertTrue(SpeakerRecords.merged(named, unnamed).confirmed)
        assertEquals("S2", SpeakerRecords.merged(named, unnamed).label)
        assertEquals("bo", SpeakerRecords.merged(named, row("S3", "bo", confirmed = false)).personId)
        val manual = row("S4", engine = SpeakerRecords.MANUAL_ENGINE)
        assertEquals("device:x", SpeakerRecords.merged(unnamed, manual).engine)
        assertEquals(mapOf("S1" to "Ana", "S2" to "Speaker 2"), SpeakerRecords.names(listOf(named, unnamed), mapOf("ana" to person("ana", "Ana"))))
    }
}
