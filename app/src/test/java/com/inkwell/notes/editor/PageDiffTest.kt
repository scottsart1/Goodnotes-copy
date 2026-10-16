package com.inkwell.notes.editor

import com.inkwell.notes.core.model.PageMeta
import com.inkwell.notes.core.model.PaperSpec
import com.inkwell.notes.core.model.PaperStyle
import com.inkwell.notes.editor.logic.PageDiff
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The controller must not push autosave bookkeeping into the canvas as a page-list change. */
class PageDiffTest {
    private fun page(id: String, index: Int, modifiedAt: Long = 0, thumb: String? = null) =
        PageMeta(id, "d", index, 595f, 842f, modifiedAt = modifiedAt, thumbnailPath = thumb)

    private val pages = listOf(page("a", 0), page("b", 1), page("c", 2))

    @Test
    fun `autosave and thumbnails are bookkeeping`() {
        val after = listOf(page("a", 0, modifiedAt = 1_000, thumb = "/t/a.webp"), page("b", 1), page("c", 2, modifiedAt = 5))
        assertTrue(PageDiff.isBookkeepingOnly(pages, after))
        assertTrue(PageDiff.isBookkeepingOnly(pages, pages))
        assertTrue(PageDiff.isBookkeepingOnly(emptyList(), emptyList()))
    }

    @Test
    fun `structural changes are not`() {
        assertFalse(PageDiff.isBookkeepingOnly(pages, pages.drop(1)))
        assertFalse(PageDiff.isBookkeepingOnly(pages, listOf(pages[1], pages[0], pages[2])))
        assertFalse(PageDiff.isBookkeepingOnly(pages, pages + page("d", 3)))
        assertFalse(PageDiff.isBookkeepingOnly(pages, pages.map { if (it.id == "b") it.copy(rotation = 90) else it }))
        assertFalse(PageDiff.isBookkeepingOnly(pages, pages.map { if (it.id == "b") it.copy(paper = PaperSpec(style = PaperStyle.GRID)) else it }))
        assertFalse(PageDiff.isBookkeepingOnly(pages, pages.map { if (it.id == "c") it.copy(widthPt = 612f, heightPt = 792f) else it }))
        assertFalse(PageDiff.isBookkeepingOnly(pages, pages.map { if (it.id == "a") it.copy(pdfPageIndex = 3) else it }))
        // Bookmarks are shown on the canvas edge, so they count as a visible change too.
        assertFalse(PageDiff.isBookkeepingOnly(pages, pages.map { if (it.id == "a") it.copy(bookmarked = true) else it }))
    }

    @Test
    fun `a replaced page with the same index is a change`() {
        assertFalse(PageDiff.isBookkeepingOnly(pages, listOf(pages[0], page("x", 1), pages[2])))
    }
}
