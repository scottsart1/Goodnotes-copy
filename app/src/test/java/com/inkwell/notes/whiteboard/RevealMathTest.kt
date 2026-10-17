package com.inkwell.notes.whiteboard

import com.inkwell.notes.whiteboard.logic.Box
import com.inkwell.notes.whiteboard.logic.RevealMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RevealMathTest {
    private val eps = 1e-3f

    // At zoom 1 a 1000 x 600 px canvas shows 1000 x 600 points.
    private val visible = Box(0f, 0f, 1000f, 600f)

    @Test fun smallTargetKeepsAReadableZoomAndCentres() {
        val p = RevealMath.plan(visible, 1000f, 600f, 1f, Box(2000f, 3000f, 2100f, 3040f))
        assertEquals(1f, p.zoom, eps)
        assertFalse(p.changesZoom(1f))
        assertEquals(2050f, p.centerX, eps)
        assertEquals(3020f, p.centerY, eps)
    }

    @Test fun largeTargetZoomsOutToFitWithMargin() {
        // 2000 x 300 points with 15% margins each side -> 2600 wide; fits at 1000/2600.
        val p = RevealMath.plan(visible, 1000f, 600f, 1f, Box(0f, 0f, 2000f, 300f))
        assertEquals(1000f / 2600f, p.zoom, eps)
        assertTrue(p.changesZoom(1f))
        assertEquals(1000f, p.centerX, eps)
    }

    @Test fun heightCanBeTheLimit() {
        val p = RevealMath.plan(visible, 1000f, 600f, 1f, Box(0f, 0f, 100f, 1000f))
        assertEquals(600f / 1300f, p.zoom, eps)
    }

    @Test fun farZoomedOutBoardsComeUpToReadableSize() {
        // At zoom 0.1 the canvas shows 10000 x 6000 points.
        val far = Box(0f, 0f, 10_000f, 6_000f)
        val p = RevealMath.plan(far, 1000f, 600f, 0.1f, Box(0f, 0f, 50f, 20f))
        assertEquals(RevealMath.READABLE_ZOOM, p.zoom, eps)
        // ...but never further than the target still fits.
        val wide = RevealMath.plan(far, 1000f, 600f, 0.1f, Box(0f, 0f, 3_000f, 100f))
        assertEquals(0.1f * 10_000f / 3_900f, wide.zoom, eps)
    }

    @Test fun zoomedInBeyondReadableIsKeptWhenItFits() {
        val v = Box(0f, 0f, 500f, 300f) // zoom 2
        val p = RevealMath.plan(v, 1000f, 600f, 2f, Box(0f, 0f, 100f, 50f))
        assertEquals(2f, p.zoom, eps)
    }

    @Test fun tinyZoomChangesAreIgnored() {
        // Target needs 1% less zoom: keep the current one.
        val p = RevealMath.plan(visible, 1000f, 600f, 1f, Box(0f, 0f, 1000f / 1.3f / 0.99f, 10f))
        assertEquals(1f, p.zoom, eps)
    }

    @Test fun limitsAreRespected() {
        val p = RevealMath.plan(visible, 1000f, 600f, 1f, Box(0f, 0f, 1_000_000f, 10f), minZoom = 0.05f)
        assertEquals(0.05f, p.zoom, eps)
    }

    @Test fun overlayPanelShiftsTheCentreIntoTheFreeArea() {
        // 400 px of 1000 covered: free area 600 px; its centre is 200 px left of the canvas centre.
        val p = RevealMath.plan(visible, 1000f, 600f, 1f, Box(0f, 0f, 100f, 100f), obscuredRightPx = 400f)
        assertEquals(1f, p.zoom, eps)
        assertEquals(50f + 200f, p.centerX, eps)
        assertEquals(50f, p.centerY, eps)
    }

    @Test fun overlayPanelReducesTheFittingWidth() {
        val p = RevealMath.plan(visible, 1000f, 600f, 1f, Box(0f, 0f, 1000f, 100f), obscuredRightPx = 400f)
        assertEquals(600f / 1300f, p.zoom, eps)
        // The shift is measured at the new zoom: 200 px / (600/1300 px per point).
        assertEquals(500f + 200f / (600f / 1300f), p.centerX, 0.01f)
    }

    @Test fun coverIsCappedSoTheTargetStillShows() {
        val p = RevealMath.plan(visible, 1000f, 600f, 1f, Box(0f, 0f, 10f, 10f), obscuredRightPx = 5_000f)
        assertEquals(5f + 300f, p.centerX, eps)
    }

    @Test fun missingLayoutJustCentres() {
        val p = RevealMath.plan(Box(0f, 0f, 0f, 0f), 0f, 0f, 1f, Box(10f, 20f, 30f, 40f))
        assertEquals(1f, p.zoom, eps)
        assertEquals(20f, p.centerX, eps)
        assertEquals(30f, p.centerY, eps)
    }
}
