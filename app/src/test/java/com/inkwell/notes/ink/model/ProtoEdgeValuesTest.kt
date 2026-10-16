package com.inkwell.notes.ink.model

import com.inkwell.notes.core.model.ConnectorItem
import com.inkwell.notes.core.model.PageContent
import com.inkwell.notes.core.model.PenType
import com.inkwell.notes.core.model.ShapeItem
import com.inkwell.notes.core.model.ShapeKind
import com.inkwell.notes.core.model.Stroke
import com.inkwell.notes.core.model.TextItem
import com.inkwell.notes.core.storage.PageContentStore
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Odd but possible page data must survive the ProtoBuf round trip bit for bit. */
class ProtoEdgeValuesTest {
    private fun roundTrip(c: PageContent): PageContent = PageContentStore.decodeOrNull(PageContentStore.encode(c)) ?: error("undecodable")

    @Test fun malformedSampleArraysNonFiniteValuesAndEmptyStringsSurvive() {
        val odd = Stroke("o", PenType.PENCIL, 0, 2f, floatArrayOf(1f, 2f, 0.5f, 0f, 0f, 3f, 4f), 0L, groupId = "")
        val nan = Stroke("n", PenType.BRUSH, -1, Float.NaN, floatArrayOf(Float.NaN, Float.POSITIVE_INFINITY, -0f, Float.MIN_VALUE, Float.MAX_VALUE), 0L, pressureSensitivity = 0f)
        val c = PageContent(version = 2, strokes = listOf(odd, nan))
        val back = roundTrip(c)
        assertEquals(2, back.version)
        assertEquals("", back.strokes[0].groupId)
        assertEquals(7, back.strokes[0].points.size)
        assertArrayEquals(odd.points, back.strokes[0].points, 0f)
        assertTrue(back.strokes[1].width.isNaN())
        assertTrue(back.strokes[1].points[0].isNaN())
        assertEquals(Float.POSITIVE_INFINITY, back.strokes[1].points[1], 0f)
        assertEquals(1f / back.strokes[1].points[2], Float.NEGATIVE_INFINITY, 0f) // -0f kept its sign
        assertEquals(Float.MIN_VALUE, back.strokes[1].points[3], 0f)
        assertEquals(Float.MAX_VALUE, back.strokes[1].points[4], 0f)
        assertEquals(0f, back.strokes[1].pressureSensitivity, 0f)
        assertEquals(-1, back.strokes[1].color)
    }

    @Test fun nonDefaultValuesEqualToZeroOrFalseAreNotLostToDefaults() {
        // Fields whose default is non-zero: a zero/false value must come back as zero/false.
        val shape = ShapeItem("s", ShapeKind.ROUNDED_RECTANGLE, floatArrayOf(0f, 0f, 10f, 10f), 0, strokeWidth = 0f, cornerRadius = 0f)
        val text = TextItem("t", 0f, 0f, 1f, 1f, "", fontSizePt = 0f, color = 0)
        val conn = ConnectorItem("c", arrowEnd = false, width = 0f, color = 0)
        val stroke = Stroke("k", PenType.BALLPOINT, 0, 0f, FloatArray(0), 0L, pressureSensitivity = 0f)
        val back = roundTrip(PageContent(strokes = listOf(stroke), shapes = listOf(shape), texts = listOf(text), connectors = listOf(conn)))
        assertEquals(0f, back.shapes.single().strokeWidth, 0f)
        assertEquals(0f, back.shapes.single().cornerRadius, 0f)
        assertEquals(0f, back.texts.single().fontSizePt, 0f)
        assertEquals(0, back.texts.single().color)
        assertEquals("", back.texts.single().text)
        assertEquals(false, back.connectors.single().arrowEnd)
        assertEquals(0f, back.connectors.single().width, 0f)
        assertEquals(0, back.connectors.single().color)
        assertEquals(0f, back.strokes.single().pressureSensitivity, 0f)
        assertEquals(0, back.strokes.single().points.size)
    }

    @Test fun manyItemsWithIdenticalIdsAndExtremeZRoundTripInOrder() {
        val strokes = (0 until 300).map { i -> Stroke("same", PenType.FOUNTAIN, i, 1f, floatArrayOf(i.toFloat(), 0f, 0f, 0f, 0f), i.toLong(), z = if (i % 2 == 0) Int.MAX_VALUE else Int.MIN_VALUE) }
        val back = roundTrip(PageContent(strokes = strokes))
        assertEquals(300, back.strokes.size)
        for (i in 0 until 300) {
            assertEquals(i, back.strokes[i].color)
            assertEquals(strokes[i].z, back.strokes[i].z)
            assertEquals(i.toLong(), back.strokes[i].createdAt)
        }
        // Paint order with extreme z values does not overflow.
        val ordered = com.inkwell.notes.ink.render.RenderOrder.order(back)
        assertEquals(300, ordered.size)
        assertTrue((ordered.first() as Stroke).z == Int.MIN_VALUE && (ordered.last() as Stroke).z == Int.MAX_VALUE)
    }
}
