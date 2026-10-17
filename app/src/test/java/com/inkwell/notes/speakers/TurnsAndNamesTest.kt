package com.inkwell.notes.speakers

import com.inkwell.notes.core.model.SpeakerTurn
import com.inkwell.notes.speakers.logic.LabelCarryOver
import com.inkwell.notes.speakers.logic.SampleWindows
import com.inkwell.notes.speakers.logic.SpeakerColors
import com.inkwell.notes.speakers.logic.SpeakerNames
import com.inkwell.notes.speakers.logic.TurnEditor
import com.inkwell.notes.speakers.logic.TurnPostProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnsAndNamesTest {
    private fun t(label: String, start: Long, end: Long) = SpeakerTurn(label, start, end)

    @Test
    fun mergesGapsDropsIslandsAndRelabelsByFirstAppearance() {
        val raw = listOf(
            t("G3", 0, 2_000),
            t("G3", 2_300, 4_000), // 300 ms gap: merged
            t("G1", 4_000, 4_200), // 200 ms island: dropped
            t("G3", 4_400, 6_000), // joins the first turn once the island is gone
            t("G1", 7_000, 9_000),
            t("G0", 9_000, 9_100), // island
            t("G1", 9_800, 12_000), // 800 ms gap: kept apart
        )
        val r = TurnPostProcessor.process(raw)
        assertEquals(listOf(t("S1", 0, 6_000), t("S2", 7_000, 9_000), t("S2", 9_800, 12_000)), r.turns)
        assertEquals(mapOf("G3" to "S1", "G1" to "S2"), r.labels)
    }

    @Test
    fun overlapsGoToTheLaterStartingVoice() {
        val flat = TurnPostProcessor.flatten(listOf(t("A", 0, 10_000), t("B", 4_000, 6_000), t("C", 9_000, 12_000)))
        assertEquals(listOf(t("A", 0, 4_000), t("B", 4_000, 6_000), t("A", 6_000, 9_000), t("C", 9_000, 12_000)), flat)
        assertTrue(TurnPostProcessor.flatten(listOf(t("A", 5, 5), t("B", -3, 2))).isEmpty())
    }

    @Test
    fun outputIsSortedAndNonOverlapping() {
        val raw = (0 until 200).map { i -> t("G${i % 3}", (i * 7_919L) % 100_000, (i * 7_919L) % 100_000 + 300 + (i % 5) * 900) }
        val r = TurnPostProcessor.process(raw).turns
        for (i in 1 until r.size) assertTrue(r[i].startMs >= r[i - 1].endMs)
        assertTrue(r.all { it.endMs - it.startMs >= TurnPostProcessor.MIN_ISLAND_MS })
    }

    @Test
    fun assignRangeCutsOtherTurnsAndMergesTouching() {
        val turns = listOf(t("S1", 0, 10_000), t("S2", 10_000, 20_000))
        val out = TurnEditor.assignRange(turns, 8_000, 12_000, "S3")
        assertEquals(listOf(t("S1", 0, 8_000), t("S3", 8_000, 12_000), t("S2", 12_000, 20_000)), out)
        val back = TurnEditor.assignRange(out, 8_000, 12_000, "S1")
        assertEquals(listOf(t("S1", 0, 12_000), t("S2", 12_000, 20_000)), back)
        assertEquals(turns, TurnEditor.assignRange(turns, 5, 5, "S9"))
    }

    @Test
    fun mergeRelabelsAndJoins() {
        val out = TurnEditor.merge(listOf(t("S1", 0, 1_000), t("S2", 1_200, 3_000), t("S3", 3_000, 5_000)), "S2", "S1")
        assertEquals(listOf(t("S1", 0, 3_000), t("S3", 3_000, 5_000)), out)
        assertEquals(mapOf("S1" to 3_000L, "S3" to 2_000L), TurnPostProcessor.speechByLabel(out))
    }

    @Test
    fun sampleWindowComesFromTheLongestCleanTurn() {
        val turns = listOf(t("S1", 0, 3_000), t("S2", 3_000, 30_000), t("S1", 30_000, 36_000))
        val w = SampleWindows.pick(turns, "S1", totalMs = 36_000)!!
        // 6 s turn: 5.5 s clear of its edges, centred.
        assertEquals(5_500L, w.durationMs)
        assertEquals(30_250L, w.startMs)
        val w2 = SampleWindows.pick(turns, "S2")!!
        assertEquals(SampleWindows.TARGET_MS, w2.durationMs)
        assertTrue(w2.startMs >= 3_250 && w2.endMs <= 29_750)
        assertNull(SampleWindows.pick(turns, "S9"))
    }

    @Test
    fun shortTurnsGetATwoSecondWindowClampedToTheRecording() {
        val w = SampleWindows.pick(listOf(t("S1", 100, 900)), "S1", totalMs = 5_000)!!
        assertEquals(0L, w.startMs)
        assertEquals(2_000L, w.endMs)
        val tail = SampleWindows.pick(listOf(t("S1", 4_500, 5_000)), "S1", totalMs = 5_000)!!
        assertEquals(3_000L to 5_000L, tail.startMs to tail.endMs)
        val tiny = SampleWindows.pick(listOf(t("S1", 0, 500)), "S1", totalMs = 800)!!
        assertEquals(0L to 800L, tiny.startMs to tiny.endMs)
        assertEquals(500L, SampleWindows.speechIn(listOf(t("S1", 0, 500), t("S2", 500, 800)), "S1", tiny))
    }

    @Test
    fun carryOverFollowsTheLargestOverlap() {
        val old = listOf(t("S1", 0, 10_000), t("S2", 10_000, 20_000))
        val new = listOf(t("S1", 0, 9_000), t("S2", 9_000, 21_000), t("S3", 21_000, 30_000))
        assertEquals(mapOf("S1" to "S1", "S2" to "S2"), LabelCarryOver.map(old, new))
        // Renumbered voices still find their old label.
        val swapped = listOf(t("S1", 10_000, 20_000), t("S2", 0, 10_000))
        assertEquals(mapOf("S1" to "S2", "S2" to "S1"), LabelCarryOver.map(old, swapped))
        assertTrue(LabelCarryOver.map(emptyList(), new).isEmpty())
    }

    @Test
    fun displayNames() {
        assertEquals("Priya", SpeakerNames.display("S2", " Priya "))
        assertEquals("Speaker 2", SpeakerNames.display("S2", null))
        assertEquals("Speaker 2", SpeakerNames.display("S2", "   "))
        assertEquals("Speaker 12", SpeakerNames.unnamed("S12"))
        assertEquals("Speaker A", SpeakerNames.unnamed("A"))
        assertEquals("S4", SpeakerNames.nextLabel(listOf("S1", "S3", "X")))
        assertEquals("S1", SpeakerNames.nextLabel(emptyList()))
        assertNull(SpeakerNames.number("S0"))
        assertNull(SpeakerNames.number("Sx"))
    }

    @Test
    fun namesAreCleaned() {
        assertEquals("Mary Ann", SpeakerNames.clean("  Mary \n\t Ann​ "))
        assertNull(SpeakerNames.clean("\u0000 ‎"))
        val long = "a".repeat(79) + "😀" + "b"
        val cleaned = SpeakerNames.clean(long)!!
        assertEquals(79, cleaned.length)
        assertTrue(cleaned.all { !Character.isSurrogate(it) })
    }

    @Test
    fun colorsCycleThroughThePalette() {
        assertEquals(0, SpeakerColors.next(emptyList()))
        assertEquals(1, SpeakerColors.next(listOf(0, 2)))
        assertEquals(0, SpeakerColors.next((0 until 8).toList()))
        assertEquals(1, SpeakerColors.next((0 until 8).toList() + 0))
        assertEquals(4, SpeakerColors.next(listOf(0, 1, 2, 11)))
        val rec = SpeakerColors.forRecording(listOf("S1", "S2", "S3"), mapOf("S2" to 0))
        assertEquals(mapOf("S2" to 0, "S1" to 1, "S3" to 2), rec)
    }
}
