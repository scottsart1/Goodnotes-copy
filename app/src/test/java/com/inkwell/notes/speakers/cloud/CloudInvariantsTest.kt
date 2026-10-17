package com.inkwell.notes.speakers.cloud

import com.inkwell.notes.ai.provider.DiarizeReferences
import com.inkwell.notes.ai.provider.DiarizedTranscriptionParser
import com.inkwell.notes.ai.provider.WavClip
import com.inkwell.notes.core.model.TranscriptSegment
import com.inkwell.notes.speakers.logic.Pcm
import com.inkwell.notes.speakers.logic.WavWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Properties that must hold for any provider output: seeded random runs through the whole pipeline. */
class CloudInvariantsTest {
    private val letters = listOf("A", "B", "C", "D", "E", "F", "a", "b")

    @Test fun turnsAreSortedNonOverlappingAndEveryVoiceGetsAValidSample() {
        val rnd = Random(42)
        repeat(300) { run ->
            val total = 2_000L + rnd.nextLong(0, 600_000)
            val n = rnd.nextInt(0, 25)
            val segs = (0 until n).map {
                val start = rnd.nextLong(0, total)
                val end = (start + rnd.nextLong(0, 15_000)).coerceAtMost(total)
                TranscriptSegment(start, end, "t$it", speaker = null, speakerLabel = if (rnd.nextInt(5) == 0) null else "S${rnd.nextInt(1, 5)}")
            }
            val turns = CloudTurns.turns(segs)
            for (i in turns.indices) {
                assertTrue("run $run: turn $i has length", turns[i].endMs > turns[i].startMs)
                if (i > 0) assertTrue("run $run: turns overlap at $i", turns[i].startMs >= turns[i - 1].endMs)
                assertTrue("run $run: turn inside the recording", turns[i].startMs >= 0 && turns[i].endMs <= total)
            }
            val voices = CloudTurns.refresh((1..4).map { CloudVoice("S$it") }, segs)
            for (v in voices) {
                val s = v.clipStartMs
                val e = v.clipEndMs
                if (s != null && e != null) {
                    assertTrue("run $run: ${v.label} clip $s..$e", e - s in CloudTurns.CLIP_MIN_MS..CloudTurns.CLIP_MAX_MS)
                    assertTrue("run $run: clip inside the recording", s >= 0 && e <= total)
                }
            }
            val result = CloudTurns.result("rec", segs, voices, total)
            val present = segs.mapNotNull { it.speakerLabel }.toSet()
            assertEquals("run $run: one speaker per voice with blocks", present, result.speakers.map { it.label }.toSet())
            for (sp in result.speakers) {
                assertTrue("run $run: ${sp.label} sample ${sp.sampleStartMs}..${sp.sampleEndMs} of $total", sp.sampleEndMs - sp.sampleStartMs in 2_000..10_000)
                assertTrue("run $run: sample inside the recording", sp.sampleStartMs >= 0 && sp.sampleEndMs <= total)
            }
            assertEquals(result.speakers.map { it.label }, result.speakers.map { it.label }.sortedBy { CloudLabels.indexOf(it) })
        }
    }

    @Test fun mappingKeepsOneRecordingLabelPerProviderLabelPerFileAndNumbersStayDense() {
        val rnd = Random(7)
        repeat(200) { run ->
            var voices = emptyList<CloudVoice>()
            var all = emptyList<TranscriptSegment>()
            val files = rnd.nextInt(1, 5)
            for (f in 0 until files) {
                val offset = f * 120_000L
                // Echo some heard voices and people, as the provider would.
                val targets = LinkedHashMap<String, ReferenceTarget>()
                for (v in voices.shuffled(rnd).take(rnd.nextInt(0, 3))) targets[v.label] = ReferenceTarget.Voice(v.label)
                if (rnd.nextBoolean()) targets["P1"] = ReferenceTarget.Person("p-${rnd.nextInt(3)}", "Person ${rnd.nextInt(3)}")
                val refs = ReferenceSet(emptyList(), targets)
                val labels = targets.keys.toList() + letters
                val blocks = (0 until rnd.nextInt(0, 12)).map {
                    val start = offset + rnd.nextLong(0, 120_000)
                    TranscriptSegment(start, start + rnd.nextLong(0, 10_000), "b$f-$it", speaker = if (rnd.nextInt(6) == 0) null else labels[rnd.nextInt(labels.size)])
                }
                val out = CloudLabelMapper.assign(voices, refs, blocks)
                // One recording label per provider label (case-insensitive) within this file.
                val byRaw = blocks.filter { it.speaker != null }.associate { b ->
                    b.speaker!!.lowercase() to out.segments.first { it.text == b.text }.speakerLabel
                }
                for (b in blocks) {
                    val mapped = out.segments.first { it.text == b.text }
                    if (b.speaker == null) assertNull(mapped.speakerLabel) else assertEquals("run $run file $f", byRaw[b.speaker!!.lowercase()], mapped.speakerLabel)
                }
                // Echoed voices keep their label; an echoed person maps to one voice linked to them.
                for ((name, t) in targets) {
                    val got = byRaw[name.lowercase()] ?: continue
                    when (t) {
                        is ReferenceTarget.Voice -> assertEquals("run $run file $f", t.label, got)
                        is ReferenceTarget.Person -> assertEquals("run $run file $f", t.personId, out.voices.first { it.label == got }.personId)
                    }
                }
                assertTrue("run $run: at most one voice per person", out.voices.mapNotNull { it.personId }.let { it.size == it.toSet().size })
                voices = CloudTurns.refresh(out.voices, all + out.segments)
                all = all + out.segments
            }
            assertEquals("run $run: labels are S1..Sn without gaps", (1..voices.size).map { "S$it" }, voices.map { it.label })
            // Display names agree with the voice across every block.
            for (v in voices) {
                val names = all.filter { it.speakerLabel == v.label }.map { it.speaker }.toSet()
                assertTrue("run $run: ${v.label} names $names", names.size <= 1)
                if (v.personName != null && names.isNotEmpty()) assertEquals(setOf(v.personName), names)
            }
        }
    }

    @Test fun parsedBlocksAlwaysStayInsideTheirFileAndInOrder() {
        val rnd = Random(3)
        repeat(200) { run ->
            val offset = rnd.nextLong(0, 600_000)
            val duration = if (rnd.nextInt(4) == 0) 0L else rnd.nextLong(1, 130_000)
            val count = rnd.nextInt(0, 10)
            val items = (0 until count).joinToString(",") {
                val start = rnd.nextDouble(-5.0, 150.0)
                val end = start + rnd.nextDouble(-2.0, 20.0)
                val startField = if (rnd.nextInt(5) == 0) "" else "\"start\":$start,"
                val endField = if (rnd.nextInt(5) == 0) "" else "\"end\":$end,"
                """{"type":"transcript.text.segment",$startField$endField"text":"t$it","speaker":"${letters[rnd.nextInt(letters.size)]}"}"""
            }
            val p = DiarizedTranscriptionParser.parse("""{"text":"x","segments":[$items]}""", offset, duration)
            var last = Long.MIN_VALUE
            for (s in p.segments) {
                assertTrue("run $run: start >= offset", s.startMs >= offset)
                assertTrue("run $run: end >= start", s.endMs >= s.startMs)
                if (duration > 0) assertTrue("run $run: inside the file", s.endMs <= offset + duration)
                assertTrue("run $run: sorted", s.startMs >= last)
                last = s.startMs
                // No items at all: the top-level text becomes one unlabelled block.
                if (count > 0) assertNotNull("run $run", s.speaker) else assertNull(s.speaker)
                assertNull(s.speakerLabel)
            }
            if (count == 0) assertEquals("run $run: the text fallback", listOf("x"), p.segments.map { it.text })
        }
    }

    @Test fun clipsCutByTheAudioLayerPassTheApiGateAtBothEdges() {
        for (ms in listOf(2_000L, 2_001L, 5_000L, 9_999L, 10_000L)) {
            val wav = WavWriter.encode(FloatArray(Pcm.samplesFor(ms, 16_000)), 16_000)
            val info = WavClip.parse(wav)
            assertNotNull("$ms ms is a readable WAV", info)
            assertEquals(ms, info!!.durationMs)
            assertNotNull("$ms ms is accepted", DiarizeReferences.fitClip(wav))
        }
        assertNull("1.999 s is refused before upload", DiarizeReferences.fitClip(WavWriter.encode(FloatArray(Pcm.samplesFor(1_999, 16_000)), 16_000)))
        val long = WavWriter.encode(FloatArray(Pcm.samplesFor(10_001, 16_000)), 16_000)
        assertEquals("over 10 s is trimmed, not refused", 10_000L, WavClip.parse(DiarizeReferences.fitClip(long)!!)!!.durationMs)
    }
}
