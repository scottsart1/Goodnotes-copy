package com.inkwell.notes.ink.engine

import com.inkwell.notes.core.model.PageMeta
import com.inkwell.notes.core.model.PaperSpec
import com.inkwell.notes.core.model.PaperStyle
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The host re-sends the page list whenever a page row changes. An autosave 400 ms after a stroke
 * bumps modifiedAt; treating that as a page change made the canvas flush (commit and end) the
 * stroke being written at that moment, truncating it.
 */
class PageChangesTest {
    private fun page(i: Int, modifiedAt: Long = 0L) =
        PageMeta(id = "p$i", documentId = "d", index = i, widthPt = 600f, heightPt = 800f, modifiedAt = modifiedAt)

    private val pages = listOf(page(0), page(1), page(2))

    @Test fun autosaveTimestampsAndThumbnailsAreBookkeepingOnly() {
        val saved = listOf(page(0, 5000L), page(1), page(2))
        assertTrue(PageChanges.isBookkeepingOnly(pages, saved))
        val thumbs = saved.map { it.copy(thumbnailPath = "/t/${it.id}.png", bookmarked = it.index == 1, bookmarkTitle = "x") }
        assertTrue(PageChanges.isBookkeepingOnly(pages, thumbs))
        assertTrue(PageChanges.isBookkeepingOnly(pages, pages))
    }

    @Test fun geometryPaperAndStructureChangesAreNot() {
        assertFalse(PageChanges.isBookkeepingOnly(pages, pages.map { if (it.index == 1) it.copy(rotation = 90) else it }))
        assertFalse(PageChanges.isBookkeepingOnly(pages, pages.map { if (it.index == 2) it.copy(heightPt = 1000f) else it }))
        assertFalse(PageChanges.isBookkeepingOnly(pages, pages.map { if (it.index == 0) it.copy(paper = PaperSpec(style = PaperStyle.GRID)) else it }))
        assertFalse(PageChanges.isBookkeepingOnly(pages, pages.map { if (it.index == 0) it.copy(pdfPageIndex = 3) else it }))
        assertFalse(PageChanges.isBookkeepingOnly(pages, pages + page(3)))
        assertFalse(PageChanges.isBookkeepingOnly(pages, pages.dropLast(1)))
        assertFalse(PageChanges.isBookkeepingOnly(pages, listOf(pages[1], pages[0], pages[2])))
    }

    @Test fun renderingChangeDetection() {
        assertFalse(PageChanges.affectsRendering(page(0), page(0, 99L)))
        assertTrue(PageChanges.affectsRendering(page(0), page(0).copy(widthPt = 500f)))
        assertTrue(PageChanges.affectsRendering(page(0), page(0).copy(rotation = 180)))
    }
}
