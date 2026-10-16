package com.inkwell.notes.ink.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TileBookTest {
    private val book = TileBook<String>()

    private fun render(t: TileBook.Tile<String>, current: Long, result: String): Boolean {
        val need = book.need(t, current)
        val ticket = book.begin(t, need, current)
        return book.finish(t, ticket, result, current)
    }

    @Test fun newTilesNeedAFullRender() {
        val t = book.getOrCreate(0, 0)
        assertEquals(TileBook.Need.FULL, book.need(t, 5))
        assertTrue(render(t, 5, "v5"))
        assertEquals(5, t.version)
        assertEquals(TileBook.Need.NONE, book.need(t, 5))
        assertTrue(book.covers(0, 0, 5))
        assertFalse(book.covers(1, 0, 5))
    }

    @Test fun editsElsewhereAdvanceWithoutRerender() {
        val a = book.getOrCreate(0, 0); val b = book.getOrCreate(1, 0)
        render(a, 1, "a1"); render(b, 1, "b1")
        book.onEdit(1, 2, null) { it === a }
        assertEquals(TileBook.Need.FULL, book.need(a, 2))
        assertEquals(TileBook.Need.NONE, book.need(b, 2))
        assertEquals(2, b.version)
        // The touched tile keeps showing its old bitmap until re-rendered.
        assertEquals("a1", a.bitmap)
    }

    @Test fun itemsAddedOnTopRenderIncrementally() {
        val t = book.getOrCreate(0, 0)
        render(t, 1, "base")
        val stroke = Any()
        book.onEdit(1, 2, listOf(stroke)) { true }
        assertEquals(TileBook.Need.INCREMENT, book.need(t, 2))
        val ticket = book.begin(t, TileBook.Need.INCREMENT, 2)
        assertFalse(ticket.full)
        assertEquals(listOf(stroke), ticket.items)
        assertEquals(1, ticket.baseVersion)
        assertTrue(book.finish(t, ticket, "base+stroke", 2))
        assertEquals(2, t.version)
        assertTrue(t.increments.isEmpty())
    }

    @Test fun incrementsChainWhileARenderIsInFlight() {
        val t = book.getOrCreate(0, 0)
        render(t, 1, "base")
        val s1 = Any(); val s2 = Any()
        book.onEdit(1, 2, listOf(s1)) { true }
        val ticket = book.begin(t, TileBook.Need.INCREMENT, 2)
        // A second stroke lands while the first increment renders.
        book.onEdit(2, 3, listOf(s2)) { true }
        assertEquals(TileBook.Need.NONE, book.need(t, 3)) // in flight
        book.finish(t, ticket, "base+s1", 3)
        assertEquals(2, t.version)
        assertEquals(TileBook.Need.INCREMENT, book.need(t, 3))
        val second = book.begin(t, TileBook.Need.INCREMENT, 3)
        assertEquals(listOf(s2), second.items)
        book.finish(t, second, "base+s1+s2", 3)
        assertEquals(3, t.version)
    }

    @Test fun untouchedTilesKeepTheirIncrementChainContiguous() {
        val t = book.getOrCreate(0, 0)
        render(t, 1, "base")
        val s1 = Any()
        book.onEdit(1, 2, listOf(s1)) { true }
        // An unrelated edit elsewhere.
        book.onEdit(2, 3, null) { false }
        val ticket = book.begin(t, TileBook.Need.INCREMENT, 3)
        assertEquals(3, ticket.version)
        book.finish(t, ticket, "base+s1", 3)
        assertEquals(TileBook.Need.NONE, book.need(t, 3))
    }

    @Test fun anOverlappingEditDuringAFullRenderLeavesTheTileStale() {
        val t = book.getOrCreate(0, 0)
        val ticket = book.begin(t, TileBook.Need.FULL, 4)
        book.onEdit(4, 5, null) { true }
        book.finish(t, ticket, "v4", 5)
        assertSame("v4", t.bitmap)
        assertEquals(4, t.version)
        assertEquals(TileBook.Need.FULL, book.need(t, 5))
    }

    @Test fun nonOverlappingEditDuringAFullRenderStillCounts() {
        val t = book.getOrCreate(0, 0)
        val ticket = book.begin(t, TileBook.Need.FULL, 4)
        book.onEdit(4, 5, null) { false }
        book.finish(t, ticket, "v4", 5)
        assertEquals(5, t.version)
        assertEquals(TileBook.Need.NONE, book.need(t, 5))
    }

    @Test fun nonAddEditsDropIncrementChains() {
        val t = book.getOrCreate(0, 0)
        render(t, 1, "base")
        book.onEdit(1, 2, listOf(Any())) { true }
        book.onEdit(2, 3, null) { true } // e.g. an erase over the new stroke
        assertTrue(t.increments.isEmpty())
        assertEquals(TileBook.Need.FULL, book.need(t, 3))
    }

    @Test fun incrementsNeedABaseBitmap() {
        val t = book.getOrCreate(0, 0)
        book.onEdit(0, 1, listOf(Any())) { true }
        assertEquals(TileBook.Need.FULL, book.need(t, 1))
    }

    @Test fun failedRendersCountFailures() {
        val t = book.getOrCreate(0, 0)
        val ticket = book.begin(t, TileBook.Need.FULL, 1)
        assertFalse(book.finish(t, ticket, null, 1))
        assertEquals(1, t.failures)
        assertFalse(t.inFlight)
    }

    @Test fun invalidateAllMakesInFlightRendersStale() {
        // Protocol: the page's visual version is bumped (1 -> 2), then every tile is invalidated.
        val t = book.getOrCreate(0, 0)
        render(t, 1, "a")
        val other = book.getOrCreate(1, 0)
        val ticket = book.begin(other, TileBook.Need.FULL, 1)
        book.invalidateAll()
        assertEquals(TileBook.Need.FULL, book.need(t, 2))
        book.finish(other, ticket, "x", 2)
        // Rendered before the invalidation: shown, but still marked as needing a new render.
        assertEquals("x", other.bitmap)
        assertEquals(1, other.version)
        assertEquals(TileBook.Need.FULL, book.need(other, 2))
    }
}
