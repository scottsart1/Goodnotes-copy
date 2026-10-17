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
import com.inkwell.notes.ink.TestInk
import com.inkwell.notes.ink.model.Items
import com.inkwell.notes.ink.select.Affine
import com.inkwell.notes.ink.select.CrossPageMove
import com.inkwell.notes.ink.select.SelectionClipboard
import com.inkwell.notes.ink.select.TransformBaker
import com.inkwell.notes.whiteboard.logic.AnchorSearch
import com.inkwell.notes.whiteboard.logic.AnchoredItem
import com.inkwell.notes.whiteboard.logic.BoardAnchors
import com.inkwell.notes.whiteboard.logic.BoardHighlight
import com.inkwell.notes.whiteboard.logic.Box
import com.inkwell.notes.whiteboard.logic.PlaybackHighlighter
import com.inkwell.notes.whiteboard.logic.RevealMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression coverage for the audio-anchor paths of the board that have no other tests. */
class BoardAudioEdgeCasesTest {
    private val rec = "rec"
    private val a = AudioAnchor(rec, 12_000)

    private val stroke = TestInk.stroke("s", 0f, 0f, 10f, 10f, audio = a)
    private val shape = ShapeItem("r", ShapeKind.RECTANGLE, floatArrayOf(0f, 0f, 10f, 10f), 0, audio = a)
    private val text = TextItem("t", 0f, 0f, 10f, 10f, "x", audio = a)
    private val image = ImageItem("i", "asset", 0f, 0f, 10f, 10f, audio = a)
    private val sticky = StickyItem("n", 0f, 0f, audio = a)
    private val connector = ConnectorItem("c")

    // ------------------------------------------------------------------ anchors on every item type

    @Test fun everyAnchorableTypeExposesAndReplacesItsAnchor() {
        for (item in listOf<Any>(stroke, shape, text, image, sticky)) {
            assertEquals(Items.type(item).name, a, Items.audio(item))
            val moved = Items.withAudio(item, AudioAnchor(rec, 99))
            assertEquals(AudioAnchor(rec, 99), Items.audio(moved))
            assertNull(Items.audio(Items.withAudio(item, null)))
        }
        // Connectors carry none and come back unchanged.
        assertNull(Items.audio(connector))
        assertSame(connector, Items.withAudio(connector, a))
    }

    @Test fun movingResizingAndCopyingWithinTheBoardKeepsAnchors() {
        val content = PageContent(strokes = listOf(stroke), shapes = listOf(shape), texts = listOf(text), images = listOf(image), stickies = listOf(sticky))
        val transform = Affine.translate(50f, 20f)
        for (item in listOf<Any>(stroke, shape, text, image, sticky)) {
            assertEquals(a, Items.audio(TransformBaker.bake(item, transform)))
            assertEquals(a, Items.audio(Items.translate(item, 5f, 5f)))
            assertEquals(a, Items.audio(Items.withId(item, "copy")))
            assertEquals(a, Items.audio(Items.withZ(item, 7)))
        }
        val copied = SelectionClipboard.copy(content, setOf("s", "r", "t", "i", "n"))!!
        assertEquals(5, BoardAnchors.items(copied).size)
        assertTrue(BoardAnchors.items(copied).all { it.anchor == a })
    }

    @Test fun draggingASelectionToAnotherPageOfTheSameDocumentKeepsAnchors() {
        val before = PageContent(stickies = listOf(sticky), texts = listOf(text))
        val items = listOf<Any>(sticky, text)
        val drag = Affine.translate(0f, 600f)
        val r = CrossPageMove.compute(before, PageContent.EMPTY, items, items.map { TransformBaker.bake(it, drag) }, emptyList(), drag, Affine.translate(0f, -600f), firstZ = 0)
        assertEquals(2, BoardAnchors.items(r.target).size)
        assertTrue(BoardAnchors.items(r.target).all { it.anchor == a })
        assertTrue(BoardAnchors.items(r.source).isEmpty())
    }

    // ------------------------------------------------------------------ show notes written here

    @Test fun singlePointStrokesAndHugeCoordinatesStillAnchor() {
        val dot = Stroke("dot", PenType.BALLPOINT, 0, 3f, floatArrayOf(1e5f, -1e5f, 0.5f, 0f, 0f), 0L, audio = AudioAnchor(rec, 5_000))
        val far = TextItem("far", 3e8f, 3e8f, 100f, 20f, "far", audio = AudioAnchor(rec, 6_000))
        val items = BoardAnchors.items(PageContent(strokes = listOf(dot), texts = listOf(far)))
        assertEquals(2, items.size)
        val dotBounds = items.first { it.anchor.offsetMs == 5_000L }.bounds
        assertEquals(1e5f - 1.5f, dotBounds.left, 0.01f)
        assertEquals(3f, dotBounds.width, 0.01f)
        // Written within 4 s of each other: one cluster spanning the whole distance, finite bounds.
        val found = BoardAnchors.find(items, rec, 7_000, 4_000, 30_000) as AnchorSearch.Found
        assertEquals(2, found.hit.itemCount)
        assertTrue(found.hit.bounds.width.isFinite() && found.hit.bounds.width > 2e8f)
        assertEquals(1_000L, found.hit.spanMs)
    }

    @Test fun positionBeforeTheFirstNotesOffersThem() {
        val items = listOf(AnchoredItem(AudioAnchor(rec, 20_000), Box(0f, 0f, 1f, 1f)), AnchoredItem(AudioAnchor(rec, 23_000), Box(5f, 5f, 6f, 6f)))
        val r = BoardAnchors.find(items, rec, 0, 4_000, 30_000) as AnchorSearch.Found
        assertEquals(Box(0f, 0f, 6f, 6f), r.hit.bounds)
        val far = BoardAnchors.find(items, rec, 0, 4_000, 10_000) as AnchorSearch.OnlyFar
        assertEquals(20_000L, far.distanceMs)
        assertEquals(20_000L, far.nearest.firstOffsetMs)
    }

    @Test fun nearBoundaryIsInclusive() {
        val items = listOf(AnchoredItem(AudioAnchor(rec, 10_000), Box(0f, 0f, 1f, 1f)))
        assertTrue(BoardAnchors.find(items, rec, 40_000, 4_000, 30_000) is AnchorSearch.Found)
        assertTrue(BoardAnchors.find(items, rec, 40_001, 4_000, 30_000) is AnchorSearch.OnlyFar)
    }

    @Test fun rotatedTextAndImageBoundsCoverTheirCorners() {
        // A 100 x 20 box rotated 45 degrees spans (100 + 20) / sqrt(2) in both directions.
        val r = BoardAnchors.rotated(Box(0f, 0f, 100f, 20f), 45f)
        val expected = 120f / kotlin.math.sqrt(2f)
        assertEquals(expected, r.width, 0.01f)
        assertEquals(expected, r.height, 0.01f)
        assertEquals(50f, r.centerX, 1e-3f)
        assertEquals(10f, r.centerY, 1e-3f)
        val content = PageContent(
            texts = listOf(TextItem("t", 0f, 0f, 100f, 20f, "x", rotation = 90f, audio = a)),
            images = listOf(ImageItem("i", "asset", 0f, 0f, 100f, 20f, rotation = -90f, audio = a)),
        )
        for (item in BoardAnchors.items(content)) {
            assertEquals(20f, item.bounds.width, 0.01f)
            assertEquals(100f, item.bounds.height, 0.01f)
        }
    }

    @Test fun firstAnchorPrefersTheFirstWrittenStickyToo() {
        val content = PageContent(
            texts = listOf(TextItem("t", 0f, 0f, 1f, 1f, "x", createdAt = 500L, audio = AudioAnchor(rec, 50_000))),
            stickies = listOf(StickyItem("n", 0f, 0f, createdAt = 100L, audio = AudioAnchor(rec, 70_000))),
        )
        assertEquals(AudioAnchor(rec, 70_000), BoardAnchors.firstAnchor(content))
    }

    // ------------------------------------------------------------------ reveal math

    @Test fun hugeTargetsClampToTheMinimumZoomAndStayFinite() {
        val p = RevealMath.plan(Box(0f, 0f, 1000f, 600f), 1000f, 600f, 1f, Box(-1e8f, -1e8f, 1e8f, 1e8f), obscuredRightPx = 400f)
        assertEquals(0.05f, p.zoom, 1e-6f)
        assertTrue(p.centerX.isFinite() && p.centerY.isFinite())
        assertEquals(0f, p.centerY, 1e-3f)
        // The free-area shift is 200 px at zoom 0.05 (0.05 px per point): 4000 points.
        assertEquals(4_000f, p.centerX, 0.5f)
    }

    @Test fun degenerateTargetIsTreatedAsAPoint() {
        val p = RevealMath.plan(Box(0f, 0f, 1000f, 600f), 1000f, 600f, 1f, Box(300f, 300f, 300f, 300f))
        assertEquals(1f, p.zoom, 1e-6f)
        assertEquals(300f, p.centerX, 1e-3f)
        assertEquals(300f, p.centerY, 1e-3f)
    }

    // ------------------------------------------------------------------ highlight state

    @Test fun switchingRecordingsWhileARevealIsHeldTakesOverAfterTheHold() {
        val h = PlaybackHighlighter(throttleMs = 150, holdMs = 1_000)
        h.onPlayback("A", true, 1_000, true, 0)
        assertTrue(h.onReveal(BoardHighlight("A", 30_000, 2_000), 100))
        // Another recording of this board starts playing during the hold.
        assertFalse(h.onPlayback("B", true, 500, true, 300))
        assertEquals("A", h.current!!.recordingId)
        assertTrue(h.onTick(1_100))
        assertEquals(BoardHighlight("B", 500), h.current)
    }

    @Test fun leavingTheBoardClearsAHeldRevealAndAStaleTickIsIgnored() {
        val h = PlaybackHighlighter(holdMs = 6_000)
        h.onPlayback(rec, true, 1_000, true, 0)
        h.onReveal(BoardHighlight(rec, 9_000, 0), 10)
        assertTrue(h.clear())
        assertNull(h.current)
        assertFalse(h.onTick(10_000))
        // Playback that is still running elsewhere does not resurrect anything on its own...
        assertTrue(h.onPlayback(rec, true, 2_000, true, 20_000))
        assertEquals(BoardHighlight(rec, 2_000), h.current)
    }

    @Test fun playbackOfAnotherDocumentDuringAHoldEndsInAClearHighlight() {
        val h = PlaybackHighlighter(holdMs = 1_000)
        h.onReveal(BoardHighlight(rec, 9_000, 0), 0)
        assertFalse(h.onPlayback(null, true, 5_000, true, 500))
        assertEquals(BoardHighlight(rec, 9_000, 0), h.current)
        assertTrue(h.onTick(1_000))
        assertNull(h.current)
    }
}
