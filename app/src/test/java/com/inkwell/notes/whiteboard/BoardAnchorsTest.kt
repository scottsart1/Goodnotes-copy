package com.inkwell.notes.whiteboard

import com.inkwell.notes.core.model.AudioAnchor
import com.inkwell.notes.core.model.ConnectorItem
import com.inkwell.notes.core.model.ImageItem
import com.inkwell.notes.core.model.PageContent
import com.inkwell.notes.core.model.PenType
import com.inkwell.notes.core.model.ShapeItem
import com.inkwell.notes.core.model.ShapeKind
import com.inkwell.notes.core.model.StickyItem
import com.inkwell.notes.core.model.Stroke
import com.inkwell.notes.core.model.TextItem
import com.inkwell.notes.whiteboard.logic.AnchorSearch
import com.inkwell.notes.whiteboard.logic.AnchoredItem
import com.inkwell.notes.whiteboard.logic.BoardAnchors
import com.inkwell.notes.whiteboard.logic.Box
import com.inkwell.notes.whiteboard.logic.FragmentOps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class BoardAnchorsTest {
    private val eps = 1e-3f
    private val rec = "rec-1"

    private fun a(ms: Long, id: String = rec) = AudioAnchor(id, ms)

    private fun stroke(id: String, x: Float, y: Float, anchor: AudioAnchor?, createdAt: Long = 0L) =
        Stroke(id, PenType.BALLPOINT, 0, 2f, floatArrayOf(x, y, 0.5f, 0f, 0f, x + 50f, y + 10f, 0.5f, 0f, 5f), createdAt, audio = anchor)

    private fun item(ms: Long, box: Box, id: String = rec) = AnchoredItem(AudioAnchor(id, ms), box)

    @Test fun itemsCoverEveryAnchoredTypeAndSkipTheRest() {
        val content = PageContent(
            strokes = listOf(stroke("s1", 0f, 0f, a(1_000)), stroke("s2", 0f, 0f, null)),
            shapes = listOf(ShapeItem("r1", ShapeKind.RECTANGLE, floatArrayOf(100f, 100f, 200f, 150f), 0, strokeWidth = 4f, audio = a(2_000))),
            texts = listOf(TextItem("t1", 300f, 300f, 80f, 20f, "Hi", audio = a(3_000))),
            images = listOf(ImageItem("i1", "x.png", 500f, 500f, 40f, 40f, audio = a(4_000))),
            // Stickies and connectors have no anchors in the model.
            stickies = listOf(StickyItem("k1", 0f, 0f)),
            connectors = listOf(ConnectorItem("c1")),
        )
        val items = BoardAnchors.items(content)
        assertEquals(listOf(1_000L, 2_000L, 3_000L, 4_000L), items.map { it.anchor.offsetMs }.sorted())
        val stroke = items.first { it.anchor.offsetMs == 1_000L }.bounds
        assertEquals(-1f, stroke.left, eps) // half the 2pt width
        assertEquals(51f, stroke.right, eps)
        val shape = items.first { it.anchor.offsetMs == 2_000L }.bounds
        assertEquals(Box(98f, 98f, 202f, 152f), shape)
    }

    @Test fun rotatedBoxesGrowAroundTheirCentre() {
        val r = BoardAnchors.rotated(Box(0f, 0f, 100f, 20f), 90f)
        assertEquals(50f, r.centerX, eps)
        assertEquals(10f, r.centerY, eps)
        assertEquals(20f, r.width, 0.01f)
        assertEquals(100f, r.height, 0.01f)
        val box = Box(0f, 0f, 10f, 10f)
        assertSame(box, BoardAnchors.rotated(box, 0f))
    }

    @Test fun findsTheNotesWrittenJustBeforeThePosition() {
        val items = listOf(
            item(9_000, Box(0f, 0f, 10f, 10f)),
            item(10_500, Box(100f, 0f, 110f, 10f)),
            item(30_000, Box(5_000f, 5_000f, 5_010f, 5_010f)),
            item(10_000, Box(0f, 0f, 1f, 1f), id = "other"),
        )
        val r = BoardAnchors.find(items, rec, positionMs = 11_000, windowMs = 4_000, nearMs = 30_000) as AnchorSearch.Found
        assertEquals(Box(0f, 0f, 110f, 10f), r.hit.bounds)
        assertEquals(9_000L, r.hit.firstOffsetMs)
        assertEquals(10_500L, r.hit.lastOffsetMs)
        assertEquals(2, r.hit.itemCount)
        assertEquals(1_500L, r.hit.spanMs)
    }

    @Test fun windowIncludesItsEdgesAndExcludesTheFuture() {
        val items = listOf(item(6_000, Box(0f, 0f, 1f, 1f)), item(10_000, Box(2f, 2f, 3f, 3f)), item(10_001, Box(9f, 9f, 10f, 10f)))
        val r = BoardAnchors.find(items, rec, 10_000, windowMs = 4_000, nearMs = 0) as AnchorSearch.Found
        assertEquals(2, r.hit.itemCount)
        assertEquals(Box(0f, 0f, 3f, 3f), r.hit.bounds)
    }

    @Test fun nearbyLaterNotesAreFoundWithTheirOwnCluster() {
        // Nothing in the 4 s before 60 s; writing starts 5 s later and continues for 3 s.
        val items = listOf(
            item(65_000, Box(0f, 0f, 10f, 10f)),
            item(68_000, Box(20f, 0f, 30f, 10f)),
            item(70_000, Box(500f, 500f, 510f, 510f)), // outside the 4 s cluster
            item(10_000, Box(-900f, -900f, -890f, -890f)),
        )
        val r = BoardAnchors.find(items, rec, 60_000, windowMs = 4_000, nearMs = 30_000) as AnchorSearch.Found
        assertEquals(Box(0f, 0f, 30f, 10f), r.hit.bounds)
        assertEquals(65_000L, r.hit.firstOffsetMs)
        assertEquals(68_000L, r.hit.lastOffsetMs)
    }

    @Test fun nearbyEarlierNotesAreFoundWithTheClusterEndingThere() {
        val items = listOf(item(40_000, Box(0f, 0f, 10f, 10f)), item(43_000, Box(10f, 10f, 20f, 20f)), item(30_000, Box(90f, 90f, 99f, 99f)))
        val r = BoardAnchors.find(items, rec, 55_000, windowMs = 4_000, nearMs = 30_000) as AnchorSearch.Found
        assertEquals(Box(0f, 0f, 20f, 20f), r.hit.bounds)
        assertEquals(40_000L, r.hit.firstOffsetMs)
    }

    @Test fun tiesPreferTheNotesWrittenBefore() {
        val items = listOf(item(40_000, Box(0f, 0f, 1f, 1f)), item(60_000, Box(50f, 50f, 51f, 51f)))
        val r = BoardAnchors.find(items, rec, 50_000, windowMs = 4_000, nearMs = 30_000) as AnchorSearch.Found
        assertEquals(40_000L, r.hit.firstOffsetMs)
    }

    @Test fun distantNotesAreOnlyOffered() {
        val items = listOf(item(10_000, Box(0f, 0f, 1f, 1f)))
        val r = BoardAnchors.find(items, rec, 600_000, windowMs = 4_000, nearMs = 30_000) as AnchorSearch.OnlyFar
        assertEquals(590_000L, r.distanceMs)
        assertEquals(10_000L, r.nearest.firstOffsetMs)
    }

    @Test fun boardsWithoutNotesOfThatRecordingReportIt() {
        assertSame(AnchorSearch.NoNotes, BoardAnchors.find(emptyList(), rec, 0, 4_000, 30_000))
        assertSame(AnchorSearch.NoNotes, BoardAnchors.find(listOf(item(1_000, Box(0f, 0f, 1f, 1f), id = "other")), rec, 1_000, 4_000, 30_000))
        assertSame(AnchorSearch.NoNotes, BoardAnchors.find(PageContent.EMPTY, rec, 0))
    }

    @Test fun negativeWindowBehavesLikeAnExactMoment() {
        val items = listOf(item(5_000, Box(0f, 0f, 1f, 1f)))
        val r = BoardAnchors.find(items, rec, 5_000, windowMs = -10, nearMs = 0) as AnchorSearch.Found
        assertEquals(1, r.hit.itemCount)
    }

    @Test fun firstAnchorIsTheFirstWrittenItem() {
        val content = PageContent(
            strokes = listOf(stroke("late", 0f, 0f, a(1_000), createdAt = 900L), stroke("early", 0f, 0f, a(8_000), createdAt = 100L)),
            texts = listOf(TextItem("t", 0f, 0f, 1f, 1f, "x", createdAt = 50L)),
        )
        assertEquals(a(8_000), BoardAnchors.firstAnchor(content))
        assertNull(BoardAnchors.firstAnchor(PageContent(texts = content.texts)))
    }

    @Test fun anchorsAreDroppedOnlyWhenContentLeavesItsDocument() {
        val content = PageContent(
            strokes = listOf(stroke("s", 0f, 0f, a(1))),
            shapes = listOf(ShapeItem("r", ShapeKind.LINE, floatArrayOf(0f, 0f, 1f, 1f), 0, audio = a(2))),
            texts = listOf(TextItem("t", 0f, 0f, 1f, 1f, "x", audio = a(3))),
            images = listOf(ImageItem("i", "x.png", 0f, 0f, 1f, 1f, audio = a(4))),
        )
        val stripped = FragmentOps.withoutAudio(content)
        assertTrue(BoardAnchors.items(stripped).isEmpty())
        assertEquals(content.strokes.single().points.toList(), stripped.strokes.single().points.toList())
        assertEquals("x", stripped.texts.single().text)
        assertSame(content, FragmentOps.forDocument(content, "board", "board"))
        assertTrue(BoardAnchors.items(FragmentOps.forDocument(content, "notebook", "board")).isEmpty())
        assertTrue(BoardAnchors.items(FragmentOps.forDocument(content, null, "board")).isEmpty())
        // Nothing to drop: the same instance comes back.
        assertSame(stripped, FragmentOps.withoutAudio(stripped))
    }

    @Test fun freshIdsAndTransformsKeepAnchors() {
        val content = PageContent(strokes = listOf(stroke("s", 10f, 10f, a(1_234))), texts = listOf(TextItem("t", 0f, 0f, 10f, 10f, "x", audio = a(99))))
        val moved = FragmentOps.translate(FragmentOps.compactZ(FragmentOps.withFreshIds(content)), 100f, 0f)
        assertEquals(listOf(99L, 1_234L), BoardAnchors.items(moved).map { it.anchor.offsetMs }.sorted())
        val fitted = FragmentOps.fitInto(content, Box(0f, 0f, 5f, 5f))
        assertEquals(2, BoardAnchors.items(fitted).size)
    }
}
