package com.inkwell.notes.speakers

import com.inkwell.notes.core.model.SpeakerTurn
import com.inkwell.notes.core.storage.db.PersonEntity
import com.inkwell.notes.core.storage.db.RecordingSpeakerEntity
import com.inkwell.notes.core.storage.db.TranscriptSegmentEntity
import com.inkwell.notes.speakers.logic.AudioPlan
import com.inkwell.notes.speakers.logic.Pcm
import com.inkwell.notes.speakers.logic.ReferenceClipCodec
import com.inkwell.notes.speakers.logic.Resampler
import com.inkwell.notes.speakers.logic.SampleWindows
import com.inkwell.notes.speakers.logic.SpeakerRecords
import com.inkwell.notes.speakers.logic.TurnPostProcessor
import com.inkwell.notes.speakers.logic.VoiceVector
import com.inkwell.notes.speakers.logic.Voiceprint
import com.inkwell.notes.speakers.logic.VoiceprintCodec
import com.inkwell.notes.speakers.logic.WavFormat
import com.inkwell.notes.speakers.logic.WavWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Robustness of the engine's pure pieces against corrupt files, odd audio rates, and random
 * recordings: codecs never throw, streaming equals whole-signal conversion, sample windows stay
 * valid, relabelling keeps every block, and analysis windows cover every available segment once.
 */
class EngineRobustnessTest {
    @Test
    fun codecsNeverThrowOnCorruption() {
        val rnd = Random(2)
        val print = Voiceprint("wespeaker_resnet34_lm", 8, List(3) { i -> VoiceVector("t$i", 1f, FloatArray(8) { it.toFloat() }) })
        val good = VoiceprintCodec.encode(print)
        val clip = ReferenceClipCodec.encode(com.inkwell.notes.speakers.logic.ReferenceClip(1000, WavWriter.encode(FloatArray(100)), "tag"))
        repeat(3000) {
            val src = if (rnd.nextBoolean()) good else clip
            val b = src.copyOf(rnd.nextInt(0, src.size + 3))
            for (k in 0 until rnd.nextInt(0, 4)) if (b.isNotEmpty()) b[rnd.nextInt(b.size)] = rnd.nextInt().toByte()
            VoiceprintCodec.decode(b)
            ReferenceClipCodec.decode(b)
            WavFormat.parse(b)
        }
        // Random garbage headers.
        repeat(2000) {
            val b = ByteArray(rnd.nextInt(0, 120)) { rnd.nextInt().toByte() }
            if (b.size >= 12) { "RIFF".toByteArray().copyInto(b, 0); "WAVE".toByteArray().copyInto(b, 8) }
            if (b.size >= 20 && rnd.nextBoolean()) "fmt ".toByteArray().copyInto(b, 12)
            val f = WavFormat.parse(b)
            if (f != null) f.toMono(ByteArray(rnd.nextInt(0, 64)) { rnd.nextInt().toByte() }, rnd.nextInt(0, 64))
        }
    }

    @Test
    fun resamplerStreamingMatchesWholeAtEveryRate() {
        val rnd = Random(7)
        for (rate in listOf(8_000, 11_025, 22_050, 32_000, 44_100, 48_000, 96_000, 44_056, 12_345)) {
            val input = FloatArray(rate / 3) { (rnd.nextFloat() - 0.5f) }
            val whole = Resampler.convert(input, rate, 16_000)
            val r = Resampler(rate, 16_000)
            val parts = ArrayList<FloatArray>()
            var at = 0
            while (at < input.size) {
                val n = minOf(rnd.nextInt(1, 3000), input.size - at)
                parts += r.process(input, at, n)
                at += n
            }
            parts += r.flush()
            val streamed = Pcm.concat(parts)
            assertEquals("rate $rate", whole.size, streamed.size)
            for (i in whole.indices) assertEquals("rate $rate sample $i", whole[i], streamed[i], 1e-5f)
        }
    }

    private fun row(label: String, personId: String? = null, confirmed: Boolean = false, speech: Long = 10_000, start: Long = 0, end: Long = 5_000, engine: String = "device:x") =
        RecordingSpeakerEntity("rec", label, personId, null, confirmed, speech, start, end, engine)

    @Test
    fun refreshAlwaysYieldsValidSampleWindows() {
        val rnd = Random(4)
        repeat(2000) { iter ->
            val turns = TurnPostProcessor.process(List(rnd.nextInt(0, 10)) { val s = rnd.nextLong(0, 90_000); SpeakerTurn("G${rnd.nextInt(0, 3)}", s, s + rnd.nextLong(100, 9_000)) }, relabel = true).turns
            val total = if (rnd.nextInt(3) == 0) 0L else (turns.maxOfOrNull { it.endMs } ?: 0L) + rnd.nextLong(0, 5_000)
            val rows = listOf("S1", "S2", "S3").map { row(it, start = rnd.nextLong(0, 50_000), end = rnd.nextLong(0, 50_000)) }
            val r = SpeakerRecords.refresh(rows, turns, setOf("S1"), total)
            for (u in r.upserts) {
                val has = turns.any { it.label == u.label }
                if (has) {
                    val d = u.sampleEndMs - u.sampleStartMs
                    assertTrue("iter $iter ${u.label} window $d total $total", d in 1..SampleWindows.MAX_MS)
                    assertTrue("iter $iter start>=0", u.sampleStartMs >= 0)
                    if (total > 0) assertTrue("iter $iter within total", u.sampleEndMs <= total)
                }
            }
        }
    }

    private fun seg(start: Long, end: Long, text: String, label: String? = null, speaker: String? = null) =
        TranscriptSegmentEntity(rowId = 7, recordingId = "rec", startMs = start, endMs = end, text = text, speaker = speaker, source = "s", speakerLabel = label)

    @Test
    fun relabelNeverLosesBlocksOrText() {
        val rnd = Random(8)
        val words = listOf("alpha", "beta.", "gamma,", "十三。", "😀", "x")
        repeat(1500) { iter ->
            val segs = ArrayList<TranscriptSegmentEntity>()
            var t = 0L
            repeat(rnd.nextInt(1, 8)) {
                val len = rnd.nextLong(0, 30_000)
                segs += seg(t, t + len, List(rnd.nextInt(0, 30)) { words[rnd.nextInt(words.size)] }.joinToString(" "), label = if (rnd.nextInt(4) == 0) "S1" else null)
                t += len + rnd.nextLong(-2_000, 3_000)
            }
            val turns = TurnPostProcessor.process(List(rnd.nextInt(0, 10)) { val s = rnd.nextLong(0, 90_000); SpeakerTurn("G${rnd.nextInt(0, 3)}", s, s + rnd.nextLong(100, 9_000)) }).turns
            val out = SpeakerRecords.relabel(segs, turns, mapOf("S1" to "Ana"), keepExistingLabels = rnd.nextBoolean()) ?: segs.sortedBy { it.startMs }
            assertEquals("iter $iter text", segs.sortedBy { it.startMs }.joinToString("") { it.text }.filterNot { it.isWhitespace() }, out.joinToString("") { it.text }.filterNot { it.isWhitespace() })
            assertTrue(out.size >= segs.size)
        }
    }

    @Test
    fun windowsCoverEveryAvailableSegmentExactlyOnce() {
        val rnd = Random(6)
        repeat(1000) {
            val d = List(rnd.nextInt(0, 12)) { if (rnd.nextInt(6) == 0) 0L else rnd.nextLong(1, 1_400_000) }
            val avail = d.map { rnd.nextInt(8) != 0 }
            val w = AudioPlan.windows(d, avail)
            val covered = HashMap<Int, Long>()
            for (win in w) {
                assertTrue(win.durationMs <= AudioPlan.MAX_WINDOW_MS)
                var g = win.globalStartMs
                for (p in win.pieces) {
                    assertEquals(g, p.globalStartMs)
                    g += p.durationMs
                    covered.merge(p.segment, p.durationMs, Long::plus)
                }
            }
            for ((i, dur) in d.withIndex()) {
                val expect = if (dur > 0 && avail[i]) dur else null
                assertEquals("segment $i", expect, covered[i])
            }
            val prefix = d.runningFold(0L) { acc, x -> acc + x }
            for (win in w) for (p in win.pieces) assertEquals(prefix[p.segment] + p.fromMs, p.globalStartMs)
        }
    }
}
