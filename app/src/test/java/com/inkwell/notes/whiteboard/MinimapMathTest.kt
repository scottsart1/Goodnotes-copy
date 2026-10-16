package com.inkwell.notes.whiteboard

import com.inkwell.notes.whiteboard.logic.Box
import com.inkwell.notes.whiteboard.logic.CropHandle
import com.inkwell.notes.whiteboard.logic.CropMath
import com.inkwell.notes.whiteboard.logic.ExportMath
import com.inkwell.notes.whiteboard.logic.MinimapMath
import com.inkwell.notes.whiteboard.logic.PixelRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MinimapMathTest {
    private val eps = 1e-3f

    @Test fun worldIsUnionOfContentAndViewportPadded() {
        val w = MinimapMath.world(Box(0f, 0f, 100f, 100f), Box(150f, 0f, 250f, 50f), padding = 0.1f)
        // union = 0..250 x 0..100, pad = 25
        assertEquals(Box(-25f, -25f, 275f, 125f), w)
        assertEquals(Box(-120f, -120f, 1320f, 1320f), MinimapMath.world(null, null, padding = 0.1f))
    }

    @Test fun transformFitsAndCentres() {
        val world = Box(0f, 0f, 400f, 100f)
        val t = MinimapMath.transform(world, 200f, 140f)
        assertEquals(0.5f, t.scale, eps)
        // 400x100 -> 200x50, centred vertically in 140
        assertEquals(0f, t.offsetX, eps)
        assertEquals(45f, t.offsetY, eps)
        val m = t.toMap(Box(0f, 0f, 400f, 100f))
        assertEquals(Box(0f, 45f, 200f, 95f), m)
    }

    @Test fun viewportRectangleIsRelativeToContent() {
        val content = Box(0f, 0f, 1000f, 1000f)
        val visible = Box(250f, 250f, 750f, 750f)
        val t = MinimapMath.transform(MinimapMath.world(content, visible, padding = 0f), 200f, 200f)
        val v = MinimapMath.viewportOnMap(t, visible)
        assertEquals(50f, v.left, eps)
        assertEquals(150f, v.right, eps)
        assertEquals(100f, v.width, eps)
    }

    @Test fun panTargetInvertsTheTransform() {
        val t = MinimapMath.transform(Box(-500f, -200f, 1500f, 800f), 200f, 140f, inset = 6f)
        val (x, y) = MinimapMath.panTarget(t, t.toMapX(321f), t.toMapY(-77f))
        assertEquals(321f, x, 0.01f)
        assertEquals(-77f, y, 0.01f)
    }

    @Test fun dragMovesCentreByScaledDelta() {
        val t = MinimapMath.transform(Box(0f, 0f, 2000f, 1000f), 200f, 100f)
        assertEquals(0.1f, t.scale, eps)
        val (cx, cy) = MinimapMath.dragCenter(t, 500f, 400f, 10f, -5f)
        assertEquals(600f, cx, eps)
        assertEquals(350f, cy, eps)
    }

    @Test fun fitZoomShowsAllContent() {
        // At zoom 1 the view shows 1000x600 points; content is 2000x600 -> needs half the zoom (plus margin).
        val z = MinimapMath.fitZoom(1f, Box(0f, 0f, 1000f, 600f), Box(0f, 0f, 2000f, 600f), marginFraction = 0f)
        assertEquals(0.5f, z, eps)
        // Tiny content is not magnified beyond the cap.
        assertEquals(2f, MinimapMath.fitZoom(1f, Box(0f, 0f, 1000f, 600f), Box(0f, 0f, 10f, 10f)), eps)
        assertEquals(150, MinimapMath.percent(1.5f))
    }

    @Test fun cropHandlesAndClamping() {
        val r = Box(0.2f, 0.2f, 0.8f, 0.8f)
        assertEquals(CropHandle.TOP_LEFT, CropMath.hitTest(r, 0.21f, 0.19f, 0.03f, 0.03f))
        assertEquals(CropHandle.RIGHT, CropMath.hitTest(r, 0.8f, 0.5f, 0.03f, 0.03f))
        assertEquals(CropHandle.MOVE, CropMath.hitTest(r, 0.5f, 0.5f, 0.03f, 0.03f))
        assertEquals(CropHandle.NONE, CropMath.hitTest(r, 0.05f, 0.95f, 0.03f, 0.03f))
        val moved = CropMath.drag(r, CropHandle.MOVE, 0.5f, -0.5f)
        assertEquals(0.4f, moved.left, eps)
        assertEquals(0f, moved.top, eps)
        assertEquals(1f, moved.right, eps)
        assertEquals(0.6f, moved.bottom, eps)
        val shrunk = CropMath.drag(r, CropHandle.BOTTOM_RIGHT, -1f, -1f, minSize = 0.1f)
        assertEquals(0.1f, shrunk.width, eps)
        assertEquals(0.1f, shrunk.height, eps)
        assertEquals(PixelRect(20, 40, 60, 120), CropMath.toPixels(r, 100, 200))
        assertEquals(PixelRect(99, 199, 1, 1), CropMath.toPixels(Box(1f, 1f, 1f, 1f), 100, 200))
    }

    @Test fun exportRegionMapsViewToBoardAndCapsSize() {
        val board = ExportMath.viewToBoard(Box(100f, 50f, 300f, 250f), 1000f, 500f, Box(0f, 0f, 2000f, 1000f))
        assertEquals(Box(200f, 100f, 600f, 500f), board)
        assertEquals(4f, ExportMath.outputScale(Box(0f, 0f, 100f, 100f), pxPerPoint = 2f), eps)
        val capped = ExportMath.outputScale(Box(0f, 0f, 10_000f, 100f), pxPerPoint = 2f, maxSide = 6000f)
        assertEquals(0.6f, capped, eps)
        assertTrue(ExportMath.outputScale(Box(0f, 0f, 10f, 10f), pxPerPoint = 0.1f) >= 1f)
    }
}
