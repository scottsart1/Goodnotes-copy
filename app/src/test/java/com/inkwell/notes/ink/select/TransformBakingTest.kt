package com.inkwell.notes.ink.select

import com.inkwell.notes.core.model.ConnectorItem
import com.inkwell.notes.core.model.ImageItem
import com.inkwell.notes.core.model.InkPoints
import com.inkwell.notes.core.model.PageContent
import com.inkwell.notes.core.model.ShapeItem
import com.inkwell.notes.core.model.ShapeKind
import com.inkwell.notes.core.model.StickyItem
import com.inkwell.notes.core.model.Stroke
import com.inkwell.notes.core.model.TextItem
import com.inkwell.notes.ink.TestInk
import com.inkwell.notes.ink.geometry.ItemGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

class TransformBakingTest {
    private fun xy(s: Stroke, i: Int) = s.points[i * InkPoints.STRIDE] to s.points[i * InkPoints.STRIDE + 1]

    @Test fun affineComposesAndInverts() {
        val m = Affine.translate(10f, 5f).then(Affine.scale(2f, 3f, 0f, 0f)).then(Affine.rotate(0.3f, 4f, 4f))
        val inv = m.inverse()!!
        val x = 7f; val y = -3f
        val px = m.mapX(x, y); val py = m.mapY(x, y)
        assertEquals(x, inv.mapX(px, py), 1e-3f)
        assertEquals(y, inv.mapY(px, py), 1e-3f)
        // Translate then scale: (1,1) -> (11, 6) -> (22, 18).
        val ts = Affine.translate(10f, 5f).then(Affine.scale(2f, 3f, 0f, 0f))
        assertEquals(22f, ts.mapX(1f, 1f), 1e-4f); assertEquals(18f, ts.mapY(1f, 1f), 1e-4f)
        assertNull(Affine.scale(0f, 1f, 0f, 0f).inverse())
    }

    @Test fun strokeScaleMovesSamplesAndScalesWidth() {
        val s = TestInk.stroke("s", 10f, 10f, 20f, 30f, width = 2f)
        val m = Affine.scale(3f, 3f, 10f, 10f)
        val out = TransformBaker.bake(s, m) as Stroke
        assertEquals(10f to 10f, xy(out, 0))
        assertEquals(40f, xy(out, 1).first, 1e-4f); assertEquals(70f, xy(out, 1).second, 1e-4f)
        assertEquals(6f, out.width, 1e-4f)
        // Pressure and time are untouched; identity fields kept.
        assertEquals(s.points[InkPoints.STRIDE + InkPoints.PRESSURE], out.points[InkPoints.STRIDE + InkPoints.PRESSURE], 0f)
        assertEquals(s.points[InkPoints.STRIDE + InkPoints.TIME], out.points[InkPoints.STRIDE + InkPoints.TIME], 0f)
        assertEquals(s.id, out.id)
        // Non-uniform: width follows the geometric mean.
        val ns = TransformBaker.bake(s, Affine.scale(4f, 1f, 0f, 0f)) as Stroke
        assertEquals(4f, ns.width, 1e-4f)
        // A move keeps the width exactly.
        assertEquals(2f, (TransformBaker.bake(s, Affine.translate(5f, 5f)) as Stroke).width, 0f)
        assertSame(s, TransformBaker.bake(s, Affine.IDENTITY))
    }

    @Test fun strokeRotationAroundTheCentre() {
        val s = TestInk.stroke("s", 0f, 0f, 10f, 0f)
        val out = TransformBaker.bake(s, Affine.rotate((PI / 2).toFloat(), 5f, 0f)) as Stroke
        assertEquals(5f, xy(out, 0).first, 1e-4f); assertEquals(-5f, xy(out, 0).second, 1e-4f)
        assertEquals(5f, xy(out, 1).first, 1e-4f); assertEquals(5f, xy(out, 1).second, 1e-4f)
    }

    @Test fun boxShapesKeepTheirKindAndGainRotation() {
        val rect = ShapeItem("r", ShapeKind.RECTANGLE, floatArrayOf(0f, 0f, 100f, 50f), 0xFF000000.toInt(), strokeWidth = 2f)
        val rot = TransformBaker.bake(rect, Affine.rotate((PI / 6).toFloat(), 50f, 25f)) as ShapeItem
        assertEquals(30f, rot.rotation, 1e-3f)
        assertEquals(100f, rot.points[2] - rot.points[0], 1e-3f)
        assertEquals(50f, rot.points[3] - rot.points[1], 1e-3f)
        assertEquals(50f, (rot.points[0] + rot.points[2]) / 2f, 1e-3f)
        // Rotating twice adds up, scaling keeps the rotation.
        val twice = TransformBaker.bake(rot, Affine.rotate((PI / 6).toFloat(), 50f, 25f)) as ShapeItem
        assertEquals(60f, twice.rotation, 1e-3f)
        val scaled = TransformBaker.bake(twice, Affine.scale(2f, 2f, 50f, 25f)) as ShapeItem
        assertEquals(60f, scaled.rotation, 1e-3f)
        assertEquals(200f, scaled.points[2] - scaled.points[0], 1e-2f)
        assertEquals(4f, scaled.strokeWidth, 1e-4f)
        // Axis scale of an unrotated box is exact.
        val wide = TransformBaker.bake(rect, Affine.scale(2f, 1f, 0f, 0f)) as ShapeItem
        assertTrue(wide.points.contentEquals(floatArrayOf(0f, 0f, 200f, 50f)))
        assertEquals(0f, wide.rotation, 0f)
    }

    @Test fun polygonVerticesTransform() {
        val tri = ShapeItem("t", ShapeKind.TRIANGLE, floatArrayOf(0f, 0f, 10f, 0f, 0f, 10f), 0)
        val out = TransformBaker.bake(tri, Affine.translate(5f, 5f)) as ShapeItem
        assertTrue(out.points.contentEquals(floatArrayOf(5f, 5f, 15f, 5f, 5f, 15f)))
    }

    @Test fun textScalesItsFontOnlyOnUniformScale() {
        val t = TextItem("t", 10f, 10f, 100f, 20f, "Hello", fontSizePt = 14f)
        val uniform = TransformBaker.bake(t, Affine.scale(2f, 2f, 10f, 10f)) as TextItem
        assertEquals(28f, uniform.fontSizePt, 1e-4f)
        assertEquals(200f, uniform.width, 1e-3f)
        assertEquals(10f, uniform.x, 1e-3f); assertEquals(10f, uniform.y, 1e-3f)
        val wider = TransformBaker.bake(t, Affine.scale(1.5f, 1f, 10f, 10f)) as TextItem
        assertEquals(14f, wider.fontSizePt, 0f)
        assertEquals(150f, wider.width, 1e-3f)
        val turned = TransformBaker.bake(t, Affine.rotate((PI / 2).toFloat(), 60f, 20f)) as TextItem
        assertEquals(90f, turned.rotation, 1e-3f)
        assertEquals(60f, turned.x + turned.width / 2f, 1e-3f)
        assertEquals(20f, turned.y + turned.height / 2f, 1e-3f)
    }

    @Test fun imagesKeepAspectUnderUniformScaleAndRotate() {
        val img = ImageItem("i", "a", 0f, 0f, 80f, 40f, rotation = 10f, cropLeft = 0.1f)
        val out = TransformBaker.bake(img, Affine.scale(0.5f, 0.5f, 0f, 0f)) as ImageItem
        assertEquals(40f, out.width, 1e-3f); assertEquals(20f, out.height, 1e-3f)
        assertEquals(10f, out.rotation, 1e-3f)
        assertEquals(0.1f, out.cropLeft, 0f)
        // The rotated corners scale with the transform too.
        val before = ItemGeometry.boxCorners(img.x, img.y, img.width, img.height, img.rotation)
        val after = ItemGeometry.boxCorners(out.x, out.y, out.width, out.height, out.rotation)
        for (k in 0 until 4) {
            assertEquals(before[k * 2] * 0.5f, after[k * 2], 1e-3f)
            assertEquals(before[k * 2 + 1] * 0.5f, after[k * 2 + 1], 1e-3f)
        }
    }

    @Test fun stickiesStayUprightAndConnectorsMoveFreeEnds() {
        val st = StickyItem("s", 0f, 0f, 100f, 100f)
        val rot = TransformBaker.bake(st, Affine.rotate((PI / 2).toFloat(), 150f, 50f)) as StickyItem
        assertEquals(100f, rot.width, 1e-3f)
        assertEquals(150f, rot.x + 50f, 1e-3f); assertEquals(-50f, rot.y + 50f, 1e-3f)
        val c = ConnectorItem("c", fromItemId = "s", fromX = 1f, fromY = 2f, toX = 10f, toY = 20f, width = 2f)
        val moved = TransformBaker.bake(c, Affine.translate(5f, 5f)) as ConnectorItem
        assertEquals(15f, moved.toX, 0f); assertEquals(25f, moved.toY, 0f)
        assertEquals("s", moved.fromItemId)
    }

    @Test fun clipboardFragmentsStartAtTheOriginAndDetachOutsideConnectors() {
        val a = StickyItem("a", 100f, 100f, 50f, 50f)
        val b = StickyItem("b", 300f, 100f, 50f, 50f)
        val c = ConnectorItem("c", fromItemId = "a", toItemId = "b")
        val page = PageContent(stickies = listOf(a, b), connectors = listOf(c))
        val frag = SelectionClipboard.copy(page, setOf("a", "c"))
        assertNotNull(frag)
        val bounds = SelectionClipboard.boundsOf(frag!!)!!
        assertEquals(0f, bounds.left, 1e-3f); assertEquals(0f, bounds.top, 1e-3f)
        val conn = frag.connectors.single()
        assertEquals("a", conn.fromItemId)
        assertNull(conn.toItemId)
        // The detached end sits where b's edge was, moved with the fragment.
        val sticky = frag.stickies.single()
        assertEquals(300f - (100f - sticky.x), conn.toX, 1e-3f)
        assertNull(SelectionClipboard.copy(page, emptySet()))
    }
}
