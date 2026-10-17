package com.inkwell.notes.speakers

import com.inkwell.notes.speakers.logic.AudioPlan
import com.inkwell.notes.speakers.logic.DiarizationAssembler
import com.inkwell.notes.speakers.logic.DiarizationCheckpoint
import com.inkwell.notes.speakers.logic.LocalTurn
import com.inkwell.notes.speakers.logic.LocalVoiceInfo
import com.inkwell.notes.speakers.logic.RawSegment
import com.inkwell.notes.speakers.logic.SpeakerModelSpecs
import com.inkwell.notes.speakers.logic.VectorMath
import com.inkwell.notes.speakers.logic.VoiceEngine
import com.inkwell.notes.speakers.logic.WindowAnalyzer
import com.inkwell.notes.speakers.logic.WindowResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PipelineTest {
    private val TEN_MINUTES = 10L * 60 * 1000

    /**
     * Fake models over synthetic audio: each sample's value encodes who is speaking (person
     * k speaks at amplitude k/10, 0 is silence). Diarization reports runs of one value with a
     * window-local speaker number in order of appearance; the embedding of a stretch is the
     * one-hot vector of its dominant person, slightly perturbed per call.
     */
    private class FakeEngine : VoiceEngine {
        override val modelId = "fake"
        var embedCalls = 0
        var embeddedSamples = 0L

        override fun diarize(samples: FloatArray, progress: (Float) -> Unit): List<RawSegment> {
            val out = ArrayList<RawSegment>()
            val local = LinkedHashMap<Int, Int>()
            var i = 0
            while (i < samples.size) {
                val p = person(samples[i])
                var j = i
                while (j < samples.size && person(samples[j]) == p) j++
                if (p > 0) out += RawSegment(i / 16_000f, j / 16_000f, local.getOrPut(p) { local.size })
                i = j
            }
            progress(1f)
            return out
        }

        override fun embed(samples: FloatArray): FloatArray? {
            embedCalls++
            embeddedSamples += samples.size
            if (samples.size < 8_000) return null
            val counts = IntArray(8)
            for (s in samples) counts[person(s)]++
            val p = (1 until 8).maxBy { counts[it] }
            val v = FloatArray(8) { 0.02f * ((embedCalls + it) % 3) }
            v[p] = 1f
            return VectorMath.normalize(v)
        }

        override fun close() = Unit

        private fun person(x: Float) = Math.round(x * 10).coerceIn(0, 7)
    }

    /** 16 kHz audio of (person, seconds) runs. */
    private fun audio(vararg runs: Pair<Int, Double>): FloatArray {
        val parts = runs.map { (p, s) -> FloatArray((s * 16_000).toInt()) { p / 10f } }
        return com.inkwell.notes.speakers.logic.Pcm.concat(parts)
    }

    private fun analyze(index: Int, start: Long, samples: FloatArray, engine: VoiceEngine): WindowResult =
        WindowAnalyzer.analyze(index, start, samples, engine)

    @Test
    fun linksVoicesAcrossWindowsIntoRecordingVoices() {
        val engine = FakeEngine()
        // Window 0: person 1 then person 2; window 1 (starting at 20 s): person 2 then person 1.
        val w0 = analyze(0, 0, audio(1 to 8.0, 0 to 1.0, 2 to 6.0, 0 to 5.0), engine)
        val w1 = analyze(1, 20_000, audio(2 to 5.0, 1 to 9.0, 0 to 1.0, 3 to 1.0), engine)
        assertEquals(listOf("w0:0", "w0:1"), w0.voices.map { it.id })
        assertEquals(20_000L, w1.turns.first().startMs)
        val result = DiarizationAssembler.assemble(
            "rec", SpeakerModelSpecs.ENGINE_ID,
            w0.turns + w1.turns, w0.voices + w1.voices, w0.embeddings + w1.embeddings, totalMs = 36_000,
        )
        assertEquals("device:wespeaker_resnet34_lm", result.engine)
        // Person 3's single second joins the most similar voice instead of founding a new one.
        assertEquals(listOf("S1", "S2"), result.speakers.map { it.label })
        val labels = result.turns.map { it.label }
        assertEquals("S1", labels.first())
        // S1 = person 1 (8 + 9 s), S2 = person 2 (6 + 5 s); person 3's second went to one of them.
        val s1 = result.speakers.first { it.label == "S1" }
        val s2 = result.speakers.first { it.label == "S2" }
        assertTrue(s1.speechMs in 17_000L..18_000L)
        assertTrue(s2.speechMs in 11_000L..12_000L)
        assertEquals(29_000L, s1.speechMs + s2.speechMs)
        assertNotNull(s1.centroid)
        assertTrue(s1.sampleEndMs - s1.sampleStartMs in 2_000L..10_000L)
        // Sorted, non-overlapping turns.
        for (i in 1 until result.turns.size) assertTrue(result.turns[i].startMs >= result.turns[i - 1].endMs)
    }

    @Test
    fun voicesWithOnlyShortTurnsArePooledAndTinyOnesDropped() {
        val engine = FakeEngine()
        // Person 1 speaks in short bursts (0.7 s each), person 2 once for 0.3 s.
        val w = analyze(0, 0, audio(1 to 0.7, 0 to 0.6, 1 to 0.7, 0 to 0.6, 3 to 4.0, 0 to 0.5, 2 to 0.3), engine)
        val byId = w.voices.associateBy { it.id }
        assertTrue(byId.getValue("w0:0").hasEmbedding)
        assertFalse(byId.getValue("w0:2").hasEmbedding)
        assertNull(w.embeddings["w0:2"])
    }

    @Test
    fun embeddingWorkIsBoundedPerVoice() {
        val engine = FakeEngine()
        analyze(0, 0, audio(1 to 300.0), engine)
        // 300 s of one voice: only MAX_EMBED_MS of it is embedded, in chunks.
        assertEquals(WindowAnalyzer.MAX_EMBED_MS * 16, engine.embeddedSamples)
        assertEquals(4, engine.embedCalls)
        assertEquals(listOf(0L to 15_000L, 15_000L to 30_000L, 30_000L to 40_500L), WindowAnalyzer.chunks(0, 40_500))
        assertEquals(listOf(0L to 15_500L), WindowAnalyzer.chunks(0, 15_500))
    }

    @Test
    fun silentOrTinyWindowsYieldNothing() {
        val engine = FakeEngine()
        assertTrue(analyze(0, 0, FloatArray(100), engine).voices.isEmpty())
        assertTrue(analyze(0, 0, audio(0 to 10.0), engine).voices.isEmpty())
        val r = DiarizationAssembler.assemble("rec", "device:x", emptyList(), emptyList(), emptyMap(), 0)
        assertTrue(r.turns.isEmpty() && r.speakers.isEmpty())
    }

    @Test
    fun checkpointRoundTripsAndValidates() {
        val c = DiarizationCheckpoint(
            modelId = "wespeaker_resnet34_lm",
            files = listOf("/a/seg_000.m4a", "/a/seg_001.m4a"),
            durations = listOf(120_000, 60_000),
            windowsDone = 1,
            turns = listOf(LocalTurn("w0:0", 0, 1_000)),
            voices = listOf(LocalVoiceInfo("w0:0", 1_000, 0, true), LocalVoiceInfo("w0:1", 200, 500, false)),
        )
        val back = DiarizationCheckpoint.decode(DiarizationCheckpoint.encode(c))
        assertEquals(c, back)
        assertTrue(c.matches("wespeaker_resnet34_lm", c.files, c.durations, windows = 1))
        assertFalse(c.matches("other", c.files, c.durations, 1))
        assertFalse(c.matches(c.modelId, c.files.reversed(), c.durations, 1))
        assertFalse(c.matches(c.modelId, c.files, listOf(120_000, 61_000), 1))
        assertFalse(c.copy(windowsDone = 3).matches(c.modelId, c.files, c.durations, 2))
        assertTrue(c.consistentWith(setOf("w0:0")))
        assertFalse(c.consistentWith(emptySet()))
        assertNull(DiarizationCheckpoint.decode("{not json"))
    }

    @Test
    fun progressCountsPieces() {
        val windows = AudioPlan.windows(listOf(120_000L, 120_000L, 120_000L, 120_000L, 120_000L, 120_000L), List(6) { true }, maxWindowMs = TEN_MINUTES)
        assertEquals(2, windows.size)
        assertEquals(0 to 6, DiarizationAssembler.progress(windows, 0, 0f))
        assertEquals(2 to 6, DiarizationAssembler.progress(windows, 0, 0.5f))
        assertEquals(5 to 6, DiarizationAssembler.progress(windows, 1, 0.99f))
        assertEquals(6 to 6, DiarizationAssembler.progress(windows, 2, 0f))
    }

    @Test
    fun windowsPackWholeSegmentsAndSkipHoles() {
        val d = listOf(120_000L, 120_000L, 120_000L, 120_000L, 120_000L, 120_000L, 30_000L)
        val w = AudioPlan.windows(d, List(d.size) { true }, maxWindowMs = TEN_MINUTES)
        assertEquals(listOf(5, 2), w.map { it.pieces.size })
        assertEquals(600_000L, w[1].globalStartMs)
        assertTrue(w.all { it.durationMs <= TEN_MINUTES })

        // The default window keeps the uninterruptible native pass short: two 120 s segments.
        val short = AudioPlan.windows(d, List(d.size) { true })
        assertEquals(listOf(2, 2, 3), short.map { it.pieces.size })
        assertEquals(listOf(0L, 240_000L, 480_000L), short.map { it.globalStartMs })
        assertTrue(short.all { it.durationMs <= AudioPlan.MAX_WINDOW_MS })

        val holes = AudioPlan.windows(listOf(60_000L, 60_000L, 0L, 60_000L), listOf(true, false, true, true))
        assertEquals(listOf(0L, 120_000L), holes.map { it.globalStartMs })
        assertEquals(listOf(listOf(0), listOf(3)), holes.map { win -> win.pieces.map { it.segment } })
    }

    @Test
    fun longSegmentsAreCutIntoEqualWindows() {
        val w = AudioPlan.windows(listOf(30_000L, 1_500_000L), listOf(true, true), maxWindowMs = TEN_MINUTES)
        assertEquals(4, w.size)
        assertEquals(30_000L, w[1].globalStartMs)
        assertEquals(listOf(500_000L, 500_000L, 500_000L), w.drop(1).map { it.durationMs })
        assertEquals(listOf(0L, 500_000L, 1_000_000L), w.drop(1).map { it.pieces.single().fromMs })
        assertEquals((0 until 4).toList(), w.map { it.index })
    }

    @Test
    fun sliceMapsTimelineRangesOntoSegments() {
        val d = listOf(120_000L, 120_000L, 45_000L)
        val s = AudioPlan.slice(d, 115_000, 125_000)
        assertEquals(2, s.size)
        assertEquals(Triple(0, 115_000L, 120_000L), Triple(s[0].segment, s[0].fromMs, s[0].toMs))
        assertEquals(Triple(1, 0L, 5_000L), Triple(s[1].segment, s[1].fromMs, s[1].toMs))
        assertEquals(120_000L, s[1].globalStartMs)
        assertTrue(AudioPlan.slice(d, 10, 10).isEmpty())
        assertEquals(1, AudioPlan.slice(d, 280_000, 400_000).size)
        assertTrue(AudioPlan.slice(d, 300_000, 400_000).isEmpty())
    }
}
