package com.inkwell.notes.speakers

import com.inkwell.notes.core.model.SpeakerTurn
import com.inkwell.notes.core.model.TranscriptSegment
import com.inkwell.notes.speakers.logic.ClusterLinker
import com.inkwell.notes.speakers.logic.LabelCarryOver
import com.inkwell.notes.speakers.logic.Resampler
import com.inkwell.notes.speakers.logic.TurnAligner
import com.inkwell.notes.speakers.logic.TurnEditor
import com.inkwell.notes.speakers.logic.TurnPostProcessor
import com.inkwell.notes.speakers.logic.VectorMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Randomised invariants of the pure engine pieces: whatever the turns and text, transcript
 * text is never lost or duplicated, turns stay sorted and non-overlapping, carry-over is
 * one-to-one, every embedded voice is linked, and the resampler's lengths add up.
 */
class EngineInvariantsTest {
    private fun nonSpace(s: String) = s.filterNot { it.isWhitespace() }

    private val words = listOf(
        "alpha", "beta", "gamma,", "delta.", "epsilon", "zeta!", "eta", "theta;", "iota", "kappa?", "lambda", "mu",
        "十三", "。", "😀", "ok", "a", ".", ",", "\"quoted.\"", "e.g.", "…", "—", "x y", "\t", "  ",
    )

    @Test
    fun alignerKeepsEveryCharacterAndContiguousTimes() {
        val rnd = Random(11)
        repeat(6000) { iter ->
            val start = if (rnd.nextInt(4) == 0) rnd.nextLong(-5_000, 200_000) else 0L
            val dur = rnd.nextLong(0, 130_000)
            var t = start + rnd.nextLong(-2_000, 3_000)
            val turns = ArrayList<SpeakerTurn>()
            repeat(rnd.nextInt(0, 9)) {
                val len = rnd.nextLong(100, 25_000)
                turns += SpeakerTurn("S${rnd.nextInt(1, 4)}", t, t + len)
                // Mostly consecutive; sometimes overlapping or far apart.
                t += len + if (rnd.nextInt(5) == 0) rnd.nextLong(-len, 0) else rnd.nextLong(0, 3_000)
            }
            val sep = when (rnd.nextInt(3)) { 0 -> " "; 1 -> ""; else -> "  " }
            val text = List(rnd.nextInt(0, 60)) { words[rnd.nextInt(words.size)] }.joinToString(sep)
            val out = TurnAligner.align(listOf(TranscriptSegment(start, start + dur, text, "old")), turns)
            assertTrue("iter $iter: no pieces", out.isNotEmpty())
            assertEquals("iter $iter text", nonSpace(text), nonSpace(out.joinToString("") { it.text }))
            assertEquals("iter $iter start", start, out.first().startMs)
            assertEquals("iter $iter end", start + dur, out.last().endMs)
            for (i in 1 until out.size) {
                assertEquals("iter $iter contiguous", out[i - 1].endMs, out[i].startMs)
                assertTrue("iter $iter monotonic", out[i].startMs > out[i - 1].startMs)
                assertTrue("iter $iter adjacent labels differ", out[i].speakerLabel != out[i - 1].speakerLabel)
            }
            if (out.size > 1) assertTrue("iter $iter empty piece", out.all { it.text.isNotBlank() })
        }
    }

    @Test
    fun turnsStaySortedAndNonOverlappingThroughProcessingAndEdits() {
        val rnd = Random(5)
        repeat(2000) { iter ->
            val raw = List(rnd.nextInt(0, 30)) {
                val s = rnd.nextLong(0, 60_000)
                SpeakerTurn("G${rnd.nextInt(0, 4)}", s, s + rnd.nextLong(0, 5_000))
            }
            val r = TurnPostProcessor.process(raw).turns
            for (i in 1 until r.size) assertTrue("iter $iter sorted", r[i].startMs >= r[i - 1].endMs)
            assertTrue(r.all { it.endMs - it.startMs >= TurnPostProcessor.MIN_ISLAND_MS })
            val seen = LinkedHashSet<String>()
            r.forEach { seen += it.label }
            assertEquals("labels by first appearance", seen.indices.map { "S${it + 1}" }, seen.toList())
            val a = rnd.nextLong(0, 60_000)
            val e = TurnEditor.assignRange(r, a, a + rnd.nextLong(1, 10_000), "S9")
            for (i in 1 until e.size) assertTrue("iter $iter edit sorted", e[i].startMs >= e[i - 1].endMs)
            assertTrue(e.all { it.endMs > it.startMs })
            assertTrue("the range belongs to S9", e.any { it.label == "S9" && it.startMs <= a && it.endMs > a })
            val m = TurnEditor.merge(e, "S1", "S2")
            for (i in 1 until m.size) assertTrue("iter $iter merge sorted", m[i].startMs >= m[i - 1].endMs)
            assertTrue(m.none { it.label == "S1" })
            // Other voices are untouched; the merged voice keeps all its speech (short gaps between
            // its turns close, as the post-processor would have closed them for one voice).
            val before = TurnPostProcessor.speechByLabel(e)
            val after = TurnPostProcessor.speechByLabel(m)
            assertEquals(before.filterKeys { it != "S1" && it != "S2" }, after.filterKeys { it != "S2" })
            assertTrue("iter $iter merged speech", (after["S2"] ?: 0L) >= (before["S1"] ?: 0L) + (before["S2"] ?: 0L))
        }
    }

    @Test
    fun carryOverPairsLabelsOneToOne() {
        val rnd = Random(9)
        repeat(500) {
            fun turns() = List(rnd.nextInt(0, 12)) { val s = rnd.nextLong(0, 60_000); SpeakerTurn("S${rnd.nextInt(1, 5)}", s, s + rnd.nextLong(1, 8_000)) }
            val m = LabelCarryOver.map(turns(), turns())
            assertEquals(m.values.size, m.values.toSet().size)
        }
    }

    @Test
    fun linkerAssignsEveryEmbeddedVoiceExactlyOnce() {
        val rnd = Random(3)
        repeat(300) {
            val voices = List(rnd.nextInt(1, 15)) { i ->
                val emb = if (rnd.nextInt(10) == 0) null else VectorMath.normalize(FloatArray(8) { rnd.nextFloat() - 0.5f })
                ClusterLinker.LocalVoice("w${i / 3}:${i % 3}", rnd.nextLong(0, 5_000), emb, i * 1000L)
            }
            val expected = rnd.nextInt(0, 4)
            val r = ClusterLinker.link(voices, expectedSpeakers = expected)
            val usable = voices.filter { it.embedding != null }
            assertEquals(usable.size, r.assignment.size)
            assertTrue(r.assignment.values.all { it in r.voices.indices })
            assertEquals(usable.size, r.voices.sumOf { it.members.size })
            if (expected > 0 && usable.isNotEmpty()) assertTrue(r.voices.size <= expected)
            assertEquals(usable.sumOf { it.speechMs }, r.voices.sumOf { it.speechMs })
        }
    }

    @Test
    fun resamplerOutputLengthHoldsForOddInputSizes() {
        for (rate in listOf(8_000, 11_025, 22_050, 44_100, 48_000, 96_000, 44_056)) {
            for (n in listOf(0, 1, 2, 5, 17, 100, 1_000, 44_101)) {
                val r = Resampler(rate, 16_000)
                val out = Resampler.convert(FloatArray(n) { 0.3f }, rate, 16_000)
                assertEquals("rate $rate n $n", r.outputLength(n.toLong()), out.size.toLong())
            }
        }
    }
}
