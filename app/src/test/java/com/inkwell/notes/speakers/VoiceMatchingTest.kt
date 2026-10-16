package com.inkwell.notes.speakers

import com.inkwell.notes.speakers.logic.ClusterLinker
import com.inkwell.notes.speakers.logic.PersonMatcher
import com.inkwell.notes.speakers.logic.PersonPrint
import com.inkwell.notes.speakers.logic.ReferencePolicy
import com.inkwell.notes.speakers.logic.VectorMath
import com.inkwell.notes.speakers.logic.VoiceScores
import com.inkwell.notes.speakers.logic.VoiceSamples
import com.inkwell.notes.speakers.logic.VoiceVector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class VoiceMatchingTest {
    private val dim = 16

    /** A unit vector [angle] radians from axis [axis] towards axis [axis]+1. */
    private fun dir(axis: Int, angle: Double = 0.0): FloatArray =
        FloatArray(dim).also { it[axis] = cos(angle).toFloat(); it[(axis + 1) % dim] = sin(angle).toFloat() }

    /** A vector whose cosine with [base] is [c] (moving towards axis [toward]). */
    private fun near(base: FloatArray, c: Double, toward: Int): FloatArray {
        val other = FloatArray(dim).also { it[toward] = 1f }
        val s = kotlin.math.sqrt(1 - c * c)
        return VectorMath.normalize(FloatArray(dim) { (base[it] * c + other[it] * s).toFloat() })
    }

    @Test
    fun vectorMathBasics() {
        assertEquals(1f, VectorMath.cosine(dir(0), dir(0)), 1e-6f)
        assertEquals(0f, VectorMath.cosine(dir(0), dir(3)), 1e-6f)
        assertEquals(0f, VectorMath.cosine(FloatArray(dim), dir(0)), 0f)
        assertEquals(0f, VectorMath.cosine(FloatArray(3), FloatArray(4)), 0f)
        assertEquals(1f, VectorMath.norm(VectorMath.normalize(floatArrayOf(3f, 4f))), 1e-6f)
        val m = VectorMath.weightedMean(listOf(dir(0), dir(1)), listOf(3.0, 1.0))!!
        assertTrue(VectorMath.cosine(m, dir(0)) > VectorMath.cosine(m, dir(1)))
        assertEquals(null, VectorMath.weightedMean(listOf(dir(0)), listOf(0.0)))
    }

    @Test
    fun calibratedSimilarityMapsDefaultThresholdToMeasuredBoundary() {
        assertEquals(0.82f, VoiceScores.rawFor(0.55f), 1e-4f)
        assertEquals(0f, VoiceScores.similarity(0.3f), 0f)
        assertEquals(1f, VoiceScores.similarity(1f), 1e-6f)
        assertEquals(0.75f, VoiceScores.similarity(0.9f), 1e-4f)
        assertEquals(0f, VoiceScores.similarity(Float.NaN), 0f)
    }

    // ------------------------------------------------------------------ linking

    private fun local(id: String, speech: Long, emb: FloatArray?, first: Long = 0) = ClusterLinker.LocalVoice(id, speech, emb, first)

    @Test
    fun linksTheSameVoiceAcrossWindows() {
        val a = dir(0)
        val b = dir(5)
        val voices = listOf(
            local("w0:0", 60_000, near(a, 0.97, 9), 0),
            local("w0:1", 40_000, near(b, 0.97, 10), 5_000),
            local("w1:0", 30_000, near(b, 0.96, 11), 600_000),
            local("w1:1", 50_000, near(a, 0.95, 12), 601_000),
        )
        val r = ClusterLinker.link(voices)
        assertEquals(2, r.voices.size)
        assertEquals(r.assignment["w0:0"], r.assignment["w1:1"])
        assertEquals(r.assignment["w0:1"], r.assignment["w1:0"])
        assertNotEquals(r.assignment["w0:0"], r.assignment["w0:1"])
        // Ordered by first appearance.
        assertEquals(0, r.assignment["w0:0"])
        assertEquals(110_000L, r.voices[0].speechMs)
    }

    @Test
    fun keepsDifferentVoicesApartBelowThreshold() {
        val a = dir(0)
        // Similar room, different people: cosine ~0.8 < 0.86.
        val voices = listOf(local("w0:0", 20_000, a), local("w0:1", 20_000, near(a, 0.8, 4), 1))
        assertEquals(2, ClusterLinker.link(voices).voices.size)
    }

    @Test
    fun shortVoicesJoinTheirNearestVoice() {
        val a = dir(0)
        val b = dir(5)
        val voices = listOf(
            local("w0:0", 30_000, a),
            local("w0:1", 30_000, b, 1),
            // 1.2 s that does not match anyone well still joins instead of founding a voice.
            local("w1:2", 1_200, near(b, 0.5, 7), 700_000),
            local("w1:3", 900, null, 701_000),
        )
        val r = ClusterLinker.link(voices)
        assertEquals(2, r.voices.size)
        assertEquals(r.assignment["w0:1"], r.assignment["w1:2"])
        assertFalse("no embedding: left out", "w1:3" in r.assignment)
    }

    @Test
    fun expectedSpeakersMergesClosestVoices() {
        val a = dir(0)
        val voices = listOf(
            local("w0:0", 30_000, a),
            local("w0:1", 30_000, near(a, 0.7, 4), 1),
            local("w0:2", 30_000, dir(8), 2),
        )
        assertEquals(3, ClusterLinker.link(voices).voices.size)
        val r = ClusterLinker.link(voices, expectedSpeakers = 2)
        assertEquals(2, r.voices.size)
        assertEquals(r.assignment["w0:0"], r.assignment["w0:1"])
    }

    @Test
    fun onlyShortVoicesStillProduceVoices() {
        val r = ClusterLinker.link(listOf(local("w0:0", 1_000, dir(0)), local("w0:1", 600, dir(0), 1)))
        assertEquals(1, r.voices.size)
        assertEquals(2, r.assignment.size)
        assertTrue(ClusterLinker.link(emptyList()).voices.isEmpty())
    }

    // ------------------------------------------------------------------ people

    private fun person(id: String, base: FloatArray, isMe: Boolean = false, n: Int = 5, c: Double = 0.95): PersonPrint =
        PersonPrint(id, isMe, List(n) { near(base, c, (it + 7) % dim) })

    @Test
    fun scoreUsesMeanOrBestSamples() {
        val a = dir(0)
        val p = person("p", a)
        val s = PersonMatcher.score(a, p)
        assertTrue(s > 0.8f)
        // A person who sounded different once: the best samples still find them.
        val mixed = PersonPrint("m", false, listOf(a, a, a) + List(10) { near(dir(5), 0.95, (it + 7) % dim) })
        assertTrue(PersonMatcher.score(a, mixed) >= VoiceScores.similarity(0.99f))
        assertEquals(0f, PersonMatcher.score(a, PersonPrint("e", false, emptyList())), 0f)
    }

    @Test
    fun matchesEachVoiceToOnePersonAndViceVersa() {
        val a = dir(0)
        val b = dir(5)
        val people = listOf(person("alice", a), person("bob", b))
        val voices = mapOf("S1" to near(a, 0.97, 12), "S2" to near(b, 0.96, 13), "S3" to near(a, 0.93, 14))
        val m = PersonMatcher.match(voices, people, threshold = 0.55f)
        assertEquals(listOf("S1" to "alice", "S2" to "bob"), m.map { it.label to it.personId })
        assertTrue(m.all { it.score in 0.55f..1f })
    }

    @Test
    fun rejectsScoresBelowThresholdAndTakenPeople() {
        val a = dir(0)
        val people = listOf(person("alice", a))
        assertTrue(PersonMatcher.match(mapOf("S1" to near(a, 0.75, 9)), people, 0.55f).isEmpty())
        assertTrue(PersonMatcher.match(mapOf("S1" to a), people, 0.55f, taken = setOf("alice")).isEmpty())
        assertTrue(PersonMatcher.match(mapOf("S1" to FloatArray(4)), people, 0.55f).isEmpty())
    }

    @Test
    fun ownerWinsTies() {
        val a = dir(0)
        val samples = List(3) { near(a, 0.95, 9 + it) }
        val people = listOf(PersonPrint("zed", false, samples), PersonPrint("me", true, samples))
        val m = PersonMatcher.match(mapOf("S1" to a), people, 0.5f)
        assertEquals("me", m.single().personId)
    }

    // ------------------------------------------------------------------ samples

    private fun vv(tag: String, v: FloatArray) = VoiceVector(tag, 1_000f, v)

    @Test
    fun samplesSkipNearDuplicatesAndReplaceSameTag() {
        val a = dir(0)
        var s = VoiceSamples.add(emptyList(), vv("rec:1:S1", a))
        s = VoiceSamples.add(s, vv("rec:2:S1", near(a, 0.99, 4)))
        assertEquals(1, s.size)
        s = VoiceSamples.add(s, vv("rec:3:S2", near(a, 0.9, 4)))
        assertEquals(2, s.size)
        // Same tag: replaced even when nearly identical.
        s = VoiceSamples.add(s, vv("rec:3:S2", near(a, 0.91, 4)))
        assertEquals(2, s.size)
        assertEquals(0.91f, VectorMath.cosine(s.last().vector, a), 1e-4f)
        assertEquals(1, VoiceSamples.removeTagged(s, VoiceSamples.recordingPrefix("3")).size)
        assertEquals("rec:r:S2", VoiceSamples.recordingTag("r", "S2"))
    }

    @Test
    fun capDropsTheMostRedundantSample() {
        val rnd = Random(4)
        val base = List(30) { i -> vv("s$i", VectorMath.normalize(FloatArray(dim) { rnd.nextFloat() - 0.5f })) }
        val twin = vv("twin", near(base[3].vector, 0.96, 0))
        val capped = VoiceSamples.cap(base + twin, 30)
        assertEquals(30, capped.size)
        // One of the two near-identical samples went; the oldest of them on the tie order.
        assertTrue(capped.count { it.tag == "s3" || it.tag == "twin" } == 1)
        assertTrue(VoiceSamples.add(base, vv("new", VectorMath.normalize(FloatArray(dim) { rnd.nextFloat() - 0.5f }))).size == 30)
    }

    @Test
    fun referencePolicyPrefersAboutEightSecondsOfSpeech() {
        assertTrue(ReferencePolicy.shouldReplace(null, null, 3_000, 3_000))
        assertFalse(ReferencePolicy.shouldReplace(null, null, 1_500, 2_000))
        assertFalse(ReferencePolicy.shouldReplace(null, null, 11_000, 11_000))
        assertTrue(ReferencePolicy.shouldReplace(4_000, 4_000, 8_000, 8_000))
        assertFalse(ReferencePolicy.shouldReplace(8_000, 8_000, 10_000, 10_000))
        assertFalse("small gain keeps the stored clip", ReferencePolicy.shouldReplace(7_800, 8_000, 8_000, 8_000))
        assertTrue(ReferencePolicy.score(8_000, 8_000) > ReferencePolicy.score(9_500, 10_000))
    }
}
