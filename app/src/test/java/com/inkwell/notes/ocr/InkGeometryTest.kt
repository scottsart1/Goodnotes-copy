package com.inkwell.notes.ocr

import com.inkwell.notes.core.model.InkPoints
import com.inkwell.notes.core.model.PenType
import com.inkwell.notes.core.model.Stroke
import com.inkwell.notes.ocr.ink.InkGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InkGeometryTest {
    private fun stroke(id: String, pen: PenType, createdAt: Long, vararg pts: Float): Stroke {
        // pts: x, y, t triples
        val n = pts.size / 3
        val packed = FloatArray(n * InkPoints.STRIDE)
        for (i in 0 until n) {
            packed[i * 5 + InkPoints.X] = pts[i * 3]
            packed[i * 5 + InkPoints.Y] = pts[i * 3 + 1]
            packed[i * 5 + InkPoints.PRESSURE] = 0.5f
            packed[i * 5 + InkPoints.TIME] = pts[i * 3 + 2]
        }
        return Stroke(id, pen, 0xFF000000.toInt(), 1.5f, packed, createdAt)
    }

    @Test
    fun highlighterAndEmptyStrokesAreNotWriting() {
        val pen = stroke("a", PenType.FOUNTAIN, 0, 1f, 1f, 0f)
        val hl = stroke("b", PenType.HIGHLIGHTER, 0, 1f, 1f, 0f)
        val empty = stroke("c", PenType.PENCIL, 0)
        assertEquals(listOf("a"), InkGeometry.writingStrokes(listOf(pen, hl, empty)).map { it.id })
    }

    @Test
    fun boxCoversAllPoints() {
        val s = stroke("a", PenType.BALLPOINT, 500, 10f, 20f, 0f, 30f, 5f, 8f, 15f, 40f, 16f)
        val b = InkGeometry.box(3, s)!!
        assertEquals(3, b.index)
        assertEquals(10f, b.left)
        assertEquals(5f, b.top)
        assertEquals(30f, b.right)
        assertEquals(40f, b.bottom)
        assertEquals(500L, b.startTime)
        assertNull(InkGeometry.box(0, stroke("e", PenType.BALLPOINT, 0)))
    }

    @Test
    fun timestampsAreCreatedAtPlusOffsetsAndNeverDecrease() {
        val s = stroke("a", PenType.BALLPOINT, 1_000, 0f, 0f, 0f, 1f, 1f, 8f, 2f, 2f, 5f, 3f, 3f, 16f)
        val t = InkGeometry.timestamps(s, Long.MIN_VALUE)
        assertEquals(listOf(1_000L, 1_008L, 1_008L, 1_016L), t.toList())
    }

    @Test
    fun strokesFedOutOfOrderAreShiftedAfterThePreviousOne() {
        val s = stroke("a", PenType.BALLPOINT, 1_000, 0f, 0f, 0f, 1f, 1f, 10f)
        val t = InkGeometry.timestamps(s, notBefore = 5_000)
        assertEquals(listOf(5_001L, 5_011L), t.toList())
        // Already later: untouched.
        assertEquals(listOf(1_000L, 1_010L), InkGeometry.timestamps(s, notBefore = 500).toList())
    }

    @Test
    fun confidenceFromCandidateScores() {
        assertNull(InkGeometry.confidenceFromScores(emptyList()))
        assertNull(InkGeometry.confidenceFromScores(listOf(null, 2f)))
        assertNull(InkGeometry.confidenceFromScores(listOf(1f)))
        // Equal scores: an even split.
        assertEquals(0.5f, InkGeometry.confidenceFromScores(listOf(1f, 1f))!!, 1e-6f)
        // A clear winner (lower score is better).
        val clear = InkGeometry.confidenceFromScores(listOf(0.5f, 6f, 7f))!!
        assertTrue(clear > 0.95f)
        val close = InkGeometry.confidenceFromScores(listOf(2.0f, 2.1f, 2.2f))!!
        assertTrue(close in 0.3f..0.4f)
    }

    @Test
    fun meanConfidenceWeighsByLengthAndSkipsUnknown() {
        assertNull(InkGeometry.meanConfidence(listOf("abc" to null)))
        assertEquals(0.75f, InkGeometry.meanConfidence(listOf("aaa" to 1.0f, "a" to 0f, "zz" to null))!!, 1e-6f)
    }

    @Test
    fun signatureChangesWhenALineChanges() {
        val a = stroke("a", PenType.BALLPOINT, 0, 0f, 0f, 0f, 5f, 5f, 5f)
        val b = stroke("b", PenType.BALLPOINT, 0, 10f, 0f, 0f)
        val moved = stroke("a", PenType.BALLPOINT, 0, 1f, 0f, 0f, 5f, 5f, 5f)
        assertEquals(InkGeometry.signature(listOf(a, b)), InkGeometry.signature(listOf(a, b)))
        assertNotEquals(InkGeometry.signature(listOf(a, b)), InkGeometry.signature(listOf(a)))
        assertNotEquals(InkGeometry.signature(listOf(a, b)), InkGeometry.signature(listOf(moved, b)))
    }
}
