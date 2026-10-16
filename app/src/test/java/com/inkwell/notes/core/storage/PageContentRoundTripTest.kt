package com.inkwell.notes.core.storage

import com.inkwell.notes.core.model.AudioAnchor
import com.inkwell.notes.core.model.ConnectorItem
import com.inkwell.notes.core.model.ImageItem
import com.inkwell.notes.core.model.InkPoints
import com.inkwell.notes.core.model.PageContent
import com.inkwell.notes.core.model.PenType
import com.inkwell.notes.core.model.ShapeItem
import com.inkwell.notes.core.model.ShapeKind
import com.inkwell.notes.core.model.StickyItem
import com.inkwell.notes.core.model.Stroke
import com.inkwell.notes.core.model.TextItem
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** ProtoBuf round trips of page content through [PageContentStore.encode] / [PageContentStore.decodeOrNull]. */
class PageContentRoundTripTest {
    private fun roundTrip(c: PageContent): PageContent {
        val bytes = PageContentStore.encode(c)
        return PageContentStore.decodeOrNull(bytes) ?: error("decode failed for ${bytes.size} bytes")
    }

    private fun stroke(id: String, n: Int, pen: PenType = PenType.BALLPOINT, width: Float = 2f, color: Int = 0xFF1A1916.toInt()): Stroke {
        val p = FloatArray(n * InkPoints.STRIDE)
        for (i in 0 until n) {
            p[i * InkPoints.STRIDE] = i * 1.5f
            p[i * InkPoints.STRIDE + 1] = 100f - i * 0.25f
            p[i * InkPoints.STRIDE + 2] = (i % 10) / 10f
            p[i * InkPoints.STRIDE + 3] = 0.3f
            p[i * InkPoints.STRIDE + 4] = i * 8f
        }
        return Stroke(id, pen, color, width, p, createdAt = 1_700_000_000_000L, z = n % 7, pressureSensitivity = 0.6f)
    }

    @Test fun emptyContentEncodesToNothingAndDecodesEmpty() {
        val bytes = PageContentStore.encode(PageContent.EMPTY)
        assertEquals(0, bytes.size)
        val back = PageContentStore.decodeOrNull(bytes)
        assertNotNull(back)
        assertTrue(back!!.isEmpty)
        assertEquals(1, back.version)
        assertNull(PageContentStore.decodeOrNull(null))
        // Garbage is reported as unreadable, never as an empty page.
        assertNull(PageContentStore.decodeOrNull(byteArrayOf(0x7f, 0x7f, 0x7f, 0x7f, 0x7f, 0x7f, 0x7f, 0x7f, 0x7f, 0x7f, 0x7f)))
    }

    @Test fun everyItemTypeSurvivesWithAllFields() {
        val anchor = AudioAnchor("rec-1", 123_456L)
        val s = stroke("s1", 40, PenType.FOUNTAIN, 3.5f, 0xFF0000FF.toInt()).copy(audio = anchor, groupId = "g1", pressureSensitivity = 0.25f, z = -3)
        val hl = stroke("s2", 5, PenType.HIGHLIGHTER, 12f, 0x66FFE000)
        val shape = ShapeItem("sh", ShapeKind.ROUNDED_RECTANGLE, floatArrayOf(10f, 20f, 110f, 80f), 0xFF112233.toInt(), fillColor = 0x40FF0000, strokeWidth = 1.25f, rotation = 33.3f, z = 5, createdAt = 42L, audio = anchor, groupId = "g1", cornerRadius = 3f)
        val text = TextItem("t", 1f, 2f, 300f, 40f, "Hello\nWorld é中😀", fontSizePt = 18f, color = 0xFF010203.toInt(), bold = true, italic = true, rotation = -12f, z = 2, createdAt = 7L, background = 0x22FFFFFF, audio = anchor, groupId = "g2", origin = "handwriting")
        val img = ImageItem("i", "asset.png", 5f, 6f, 70f, 80f, rotation = 90f, opacity = 0.5f, cropLeft = 0.1f, cropTop = 0.2f, cropRight = 0.3f, cropBottom = 0.05f, z = 9, createdAt = 1L, groupId = "g2", audio = anchor)
        val sticky = StickyItem("st", -50f, -60f, 180f, 120f, "note", color = 0xFFFFE89A.toInt(), z = 1, createdAt = 2L, groupId = "g3")
        val conn = ConnectorItem("c", fromItemId = "st", toItemId = null, fromX = 1f, fromY = 2f, toX = 3f, toY = 4f, color = 0xFF000000.toInt(), width = 2.5f, arrowEnd = false, arrowStart = true, z = 3, createdAt = 9L, label = "goes to")
        val c = PageContent(strokes = listOf(s, hl), shapes = listOf(shape), texts = listOf(text), images = listOf(img), stickies = listOf(sticky), connectors = listOf(conn))
        val back = roundTrip(c)
        assertEquals(c, back)
        // Stroke.equals ignores some fields: check them explicitly.
        val bs = back.strokes[0]
        assertEquals(anchor, bs.audio)
        assertEquals(0.25f, bs.pressureSensitivity, 0f)
        assertEquals(1_700_000_000_000L, bs.createdAt)
        assertArrayEquals(s.points, bs.points, 0f)
        assertEquals(hl.color, back.strokes[1].color)
        val bsh = back.shapes[0]
        assertEquals(3f, bsh.cornerRadius, 0f); assertEquals(42L, bsh.createdAt); assertEquals(anchor, bsh.audio)
        assertEquals(text, back.texts[0])
        assertEquals(img, back.images[0])
        assertEquals(sticky, back.stickies[0])
        assertEquals(conn, back.connectors[0])
    }

    @Test fun strokesWithNoSamplesRoundTrip() {
        // A stroke whose points array is empty (e.g. after a bad split) must not make the whole page unreadable.
        val empty = Stroke("e", PenType.BALLPOINT, 0xFF000000.toInt(), 2f, FloatArray(0), createdAt = 1L)
        val back = roundTrip(PageContent(strokes = listOf(empty, stroke("ok", 3))))
        assertEquals(2, back.strokes.size)
        assertEquals(0, back.strokes[0].pointCount)
        assertEquals(3, back.strokes[1].pointCount)
        val shape = ShapeItem("sh", ShapeKind.TRIANGLE, FloatArray(0), 0)
        assertEquals(0, roundTrip(PageContent(shapes = listOf(shape))).shapes[0].points.size)
    }

    @Test fun extremeValuesRoundTrip() {
        val p = floatArrayOf(
            Float.MAX_VALUE, -Float.MAX_VALUE, Float.MIN_VALUE, 0f, 1e-30f,
            Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, -0f, 123456.789f,
        )
        val s = Stroke("x", PenType.PENCIL, Int.MIN_VALUE, Float.MAX_VALUE, p, createdAt = Long.MIN_VALUE, z = Int.MAX_VALUE)
        val back = roundTrip(PageContent(strokes = listOf(s), version = Int.MAX_VALUE))
        val bp = back.strokes[0].points
        assertEquals(p.size, bp.size)
        for (i in p.indices) assertEquals(p[i].toRawBits(), bp[i].toRawBits())
        assertEquals(Int.MIN_VALUE, back.strokes[0].color)
        assertEquals(Long.MIN_VALUE, back.strokes[0].createdAt)
        assertEquals(Int.MAX_VALUE, back.strokes[0].z)
        assertEquals(Int.MAX_VALUE, back.version)
        // Empty strings and default values.
        val t = TextItem("t", 0f, 0f, 0f, 0f, "")
        val st = StickyItem("st", 0f, 0f, text = "")
        val cn = ConnectorItem("c")
        val back2 = roundTrip(PageContent(texts = listOf(t), stickies = listOf(st), connectors = listOf(cn)))
        assertEquals(t, back2.texts[0]); assertEquals(st, back2.stickies[0]); assertEquals(cn, back2.connectors[0])
    }

    @Test fun largePagesRoundTripExactly() {
        val strokes = (0 until 600).map { stroke("s$it", 400 + it % 50, PenType.entries[it % 5]) }
        val c = PageContent(strokes = strokes)
        val bytes = PageContentStore.encode(c)
        assertTrue(bytes.size > 600 * 400 * 5 * 4)
        val back = PageContentStore.decodeOrNull(bytes)!!
        assertEquals(600, back.strokes.size)
        for (i in strokes.indices) {
            assertEquals(strokes[i].id, back.strokes[i].id)
            assertArrayEquals(strokes[i].points, back.strokes[i].points, 0f)
            assertEquals(strokes[i].pen, back.strokes[i].pen)
        }
    }

    @Test fun truncatedBytesAreUnreadableNotEmpty() {
        val bytes = PageContentStore.encode(PageContent(strokes = listOf(stroke("s", 50))))
        // Cut inside the packed float array: the decoder must signal corruption so the store falls back to its backup.
        val cut = bytes.copyOf(bytes.size / 2)
        assertNull(PageContentStore.decodeOrNull(cut))
    }

    @Test fun emptyInstanceIsSharedButDecodedEmptyPagesAreEqual() {
        assertSame(PageContent.EMPTY, PageContent.EMPTY)
        assertEquals(PageContent.EMPTY, roundTrip(PageContent()))
    }
}
