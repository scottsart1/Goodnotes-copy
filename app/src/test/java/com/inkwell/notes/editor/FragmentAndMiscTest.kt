package com.inkwell.notes.editor

import com.inkwell.notes.core.model.AudioAnchor
import com.inkwell.notes.core.model.ConnectorItem
import com.inkwell.notes.core.model.ImageItem
import com.inkwell.notes.core.model.PageContent
import com.inkwell.notes.core.model.PenType
import com.inkwell.notes.core.model.ShapeItem
import com.inkwell.notes.core.model.ShapeKind
import com.inkwell.notes.core.model.Stroke
import com.inkwell.notes.core.model.TextItem
import com.inkwell.notes.editor.logic.AnchorLocator
import com.inkwell.notes.editor.logic.ColorMath
import com.inkwell.notes.editor.logic.EditorAction
import com.inkwell.notes.editor.logic.EditorShortcuts
import com.inkwell.notes.editor.logic.FragmentOps
import com.inkwell.notes.editor.logic.ImageMath
import com.inkwell.notes.editor.logic.SessionTabs
import com.inkwell.notes.editor.logic.ShortcutKey
import com.inkwell.notes.editor.logic.Throttler
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FragmentAndMiscTest {
    private fun stroke(id: String, vararg xy: Float, audio: AudioAnchor? = null, group: String? = null): Stroke {
        val pts = FloatArray(xy.size / 2 * 5)
        for (i in 0 until xy.size / 2) {
            pts[i * 5] = xy[i * 2]; pts[i * 5 + 1] = xy[i * 2 + 1]; pts[i * 5 + 2] = 0.5f
        }
        return Stroke(id, PenType.BALLPOINT, 0xFF000000.toInt(), 2f, pts, 0L, audio = audio, groupId = group)
    }

    private val fragment = PageContent(
        strokes = listOf(stroke("s1", 100f, 100f, 140f, 120f, group = "g"), stroke("s2", 150f, 90f, 160f, 130f, group = "g")),
        shapes = listOf(ShapeItem("sh", ShapeKind.RECTANGLE, floatArrayOf(110f, 140f, 200f, 180f), 0xFF000000.toInt(), strokeWidth = 2f)),
        texts = listOf(TextItem("t", 120f, 200f, 80f, 20f, "Hello")),
        images = listOf(ImageItem("i", "asset-1", 90f, 80f, 10f, 10f)),
        connectors = listOf(ConnectorItem("c", fromItemId = "sh", toItemId = "t", fromX = 1f, fromY = 2f, toX = 3f, toY = 4f)),
    )

    @Test
    fun `bounds cover every item including stroke width`() {
        val b = FragmentOps.bounds(fragment)!!
        assertEquals(1f, b.left, 1e-4f) // connector fallback coordinates
        assertEquals(2f, b.top, 1e-4f)
        assertEquals(201f, b.right, 1e-4f)
        assertEquals(220f, b.bottom, 1e-4f)
        assertNull(FragmentOps.bounds(PageContent.EMPTY))
    }

    @Test
    fun `normalising moves the fragment to the origin`() {
        val noConnector = fragment.copy(connectors = emptyList())
        val (moved, size) = FragmentOps.normalizeToOrigin(noConnector)
        val b = FragmentOps.bounds(moved)!!
        assertEquals(0f, b.left, 1e-4f)
        assertEquals(0f, b.top, 1e-4f)
        assertEquals(size.width, b.width, 1e-4f)
        // Original untouched.
        assertEquals(100f, noConnector.strokes[0].points[0])
        // The image at x = 90 is the leftmost item.
        assertEquals(100f - 90f, moved.strokes[0].points[0], 1e-4f)
        assertEquals(0.5f, moved.strokes[0].points[2])
    }

    @Test
    fun `fresh ids remap groups and connector links consistently`() {
        var n = 0
        val copy = FragmentOps.withFreshIds(fragment) { "n${n++}" }
        val allIds = copy.strokes.map { it.id } + copy.shapes.map { it.id } + copy.texts.map { it.id } + copy.images.map { it.id } + copy.connectors.map { it.id }
        assertEquals(allIds.size, allIds.toSet().size)
        assertTrue(allIds.none { it in setOf("s1", "s2", "sh", "t", "i", "c") })
        assertEquals(copy.strokes[0].groupId, copy.strokes[1].groupId)
        assertNotEquals("g", copy.strokes[0].groupId)
        assertEquals(copy.shapes[0].id, copy.connectors[0].fromItemId)
        assertEquals(copy.texts[0].id, copy.connectors[0].toItemId)
        assertEquals(setOf("asset-1"), FragmentOps.assetIds(copy))
    }

    @Test
    fun `fresh ids can drop audio anchors`() {
        val withAudio = PageContent(strokes = listOf(stroke("a", 0f, 0f, 1f, 1f, audio = AudioAnchor("r", 5))))
        assertEquals(AudioAnchor("r", 5), FragmentOps.withFreshIds(withAudio).strokes[0].audio)
        assertNull(FragmentOps.withFreshIds(withAudio, keepAudio = false).strokes[0].audio)
    }

    @Test
    fun `group detection and typed text`() {
        assertTrue(FragmentOps.canGroup(fragment))
        assertTrue(FragmentOps.canUngroup(fragment))
        val grouped = PageContent(strokes = fragment.strokes)
        assertFalse(FragmentOps.canGroup(grouped))
        val texts = PageContent(texts = listOf(TextItem("b", 10f, 50f, 1f, 1f, "second"), TextItem("a", 10f, 10f, 1f, 1f, " first ")))
        assertEquals("first\nsecond", FragmentOps.typedText(texts))
    }

    @Test
    fun `first audio anchor is the earliest`() {
        val c = PageContent(
            strokes = listOf(stroke("a", 0f, 0f, audio = AudioAnchor("r", 9_000)), stroke("b", 0f, 0f, audio = AudioAnchor("r", 3_000))),
            texts = listOf(TextItem("t", 0f, 0f, 1f, 1f, "x", audio = AudioAnchor("r", 5_000))),
        )
        assertEquals(3_000L, FragmentOps.firstAudioAnchor(c)!!.offsetMs)
        assertNull(FragmentOps.firstAudioAnchor(fragment))
    }

    @Test
    fun `anchor locator finds the first page written at a playback position`() {
        val p0 = PageContent(strokes = listOf(stroke("a", 0f, 0f, 10f, 10f, audio = AudioAnchor("r", 1_000))))
        val p1 = PageContent(strokes = listOf(stroke("b", 50f, 50f, 60f, 60f, audio = AudioAnchor("r", 20_000)), stroke("c", 70f, 70f, 80f, 80f, audio = AudioAnchor("r", 21_000))))
        val p2 = PageContent(strokes = listOf(stroke("d", 0f, 0f, 1f, 1f, audio = AudioAnchor("other", 21_000))))
        val pages = listOf(2 to p2, 0 to p0, 1 to p1)
        val hit = AnchorLocator.find(pages, "r", 22_000)!!
        assertEquals(1, hit.pageIndex)
        assertEquals(20_000L, hit.offsetMs)
        assertEquals(49f, hit.bounds.left, 1e-4f)
        assertEquals(81f, hit.bounds.bottom, 1e-4f)
        // Outside every window: closest in time.
        assertEquals(0, AnchorLocator.find(pages, "r", 8_000)!!.pageIndex)
        assertNull(AnchorLocator.find(pages, "missing", 1_000))
    }

    @Test
    fun `throttler lets one event through per interval`() {
        val t = Throttler(150)
        assertTrue(t.tryAcquire(0))
        assertFalse(t.tryAcquire(100))
        assertTrue(t.tryAcquire(150))
        assertFalse(t.tryAcquire(200))
        t.reset()
        assertTrue(t.tryAcquire(201))
    }

    @Test
    fun `session tabs add, rename, cap and close to a neighbour`() {
        val tabs = SessionTabs(maxTabs = 3)
        tabs.open("a", "A")
        tabs.open("b", "B")
        tabs.open("a", null)
        assertEquals(listOf("a", "b"), tabs.tabs.value.map { it.docId })
        tabs.rename("b", "Bee")
        assertEquals("Bee", tabs.tabs.value[1].title)
        tabs.open("c", "C")
        tabs.open("d", "D")
        assertEquals(listOf("b", "c", "d"), tabs.tabs.value.map { it.docId })
        assertEquals("d", tabs.close("c"))
        assertEquals("b", tabs.close("d"))
        assertNull(tabs.close("b"))
        assertNull(tabs.close("zzz"))
    }

    @Test
    fun `keyboard shortcuts`() {
        assertEquals(EditorAction.UNDO, EditorShortcuts.resolve(ShortcutKey.Z, ctrl = true, shift = false, alt = false))
        assertEquals(EditorAction.REDO, EditorShortcuts.resolve(ShortcutKey.Z, ctrl = true, shift = true, alt = false))
        assertEquals(EditorAction.FIND, EditorShortcuts.resolve(ShortcutKey.F, ctrl = true, shift = false, alt = false))
        assertEquals(EditorAction.FOCUS_MODE, EditorShortcuts.resolve(ShortcutKey.F, ctrl = true, shift = true, alt = false))
        assertEquals(EditorAction.ZOOM_IN, EditorShortcuts.resolve(ShortcutKey.EQUALS, ctrl = true, shift = false, alt = false))
        assertEquals(EditorAction.ZOOM_OUT, EditorShortcuts.resolve(ShortcutKey.MINUS, ctrl = true, shift = false, alt = false))
        assertEquals(EditorAction.ADD_PAGE, EditorShortcuts.resolve(ShortcutKey.N, ctrl = true, shift = false, alt = false))
        assertEquals(EditorAction.BOOKMARK, EditorShortcuts.resolve(ShortcutKey.B, ctrl = true, shift = false, alt = false))
        assertEquals(EditorAction.TOOL_PEN, EditorShortcuts.resolve(ShortcutKey.P, ctrl = false, shift = false, alt = false))
        assertEquals(EditorAction.TOOL_ERASER, EditorShortcuts.resolve(ShortcutKey.E, ctrl = false, shift = false, alt = false))
        assertEquals(EditorAction.TOOL_HIGHLIGHTER, EditorShortcuts.resolve(ShortcutKey.H, ctrl = false, shift = false, alt = false))
        assertEquals(EditorAction.TOOL_LASSO, EditorShortcuts.resolve(ShortcutKey.L, ctrl = false, shift = false, alt = false))
        assertNull(EditorShortcuts.resolve(ShortcutKey.P, ctrl = false, shift = true, alt = false))
        assertNull(EditorShortcuts.resolve(ShortcutKey.Z, ctrl = true, shift = false, alt = true))
    }

    @Test
    fun `image sampling and downscaling`() {
        assertEquals(1, ImageMath.sampleSize(4000, 3000, 4096))
        assertEquals(2, ImageMath.sampleSize(9000, 6000, 4096))
        assertEquals(4, ImageMath.sampleSize(12000, 9000, 2048))
        assertEquals(1, ImageMath.sampleSize(0, 0, 2048))
        assertEquals(4096 to 3072, ImageMath.fitWithin(8000, 6000, 4096))
        assertEquals(100 to 50, ImageMath.fitWithin(100, 50, 4096))
        assertEquals(1 to 4096, ImageMath.fitWithin(1, 20000, 4096))
    }

    @Test
    fun `colour conversions`() {
        assertEquals("#2952CC", ColorMath.toHex(0xFF2952CC.toInt()))
        assertEquals(0xFF2952CC.toInt(), ColorMath.parseHex("#2952cc"))
        assertEquals(0xFFFFAA00.toInt(), ColorMath.parseHex("fa0"))
        assertEquals(0x802952CC.toInt(), ColorMath.parseHex("802952CC"))
        assertNull(ColorMath.parseHex("#12"))
        assertNull(ColorMath.parseHex("zzzzzz"))
        val hsv = ColorMath.argbToHsv(0xFF2952CC.toInt())
        assertEquals(0xFF2952CC.toInt(), ColorMath.hsvToArgb(hsv[0], hsv[1], hsv[2]))
        assertEquals(0xFFFF0000.toInt(), ColorMath.hsvToArgb(0f, 1f, 1f))
        assertEquals(0xFF00FF00.toInt(), ColorMath.hsvToArgb(120f, 1f, 1f))
        assertEquals(0xFF0000FF.toInt(), ColorMath.hsvToArgb(240f, 1f, 1f))
        assertArrayEquals(floatArrayOf(0f, 0f, 1f), ColorMath.argbToHsv(0xFFFFFFFF.toInt()), 1e-4f)
        assertTrue(ColorMath.isLight(0xFFF2D04B.toInt()))
        assertFalse(ColorMath.isLight(0xFF1A1916.toInt()))
    }
}
