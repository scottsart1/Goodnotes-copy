package com.inkwell.notes.core.storage

import com.inkwell.notes.core.model.AudioAnchor
import com.inkwell.notes.core.model.ImageItem
import com.inkwell.notes.core.model.PageContent
import com.inkwell.notes.core.model.PenType
import com.inkwell.notes.core.model.Stroke
import com.inkwell.notes.core.model.TextItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class PageOrderingTest {

    @Test fun insertClampsAndAppends() {
        val pages = listOf("a", "b", "c")
        assertEquals(listOf("a", "x", "b", "c"), PageOrdering.insertAll(pages, listOf("x"), 1))
        assertEquals(listOf("a", "b", "c", "x", "y"), PageOrdering.insertAll(pages, listOf("x", "y"), null))
        assertEquals(listOf("x", "a", "b", "c"), PageOrdering.insertAll(pages, listOf("x"), -4))
        assertEquals(listOf("a", "b", "c", "x"), PageOrdering.insertAll(pages, listOf("x"), 99))
    }

    @Test fun moveKeepsEveryPage() {
        val pages = listOf("a", "b", "c", "d")
        assertEquals(listOf("b", "c", "a", "d"), PageOrdering.move(pages, 0, 2))
        assertEquals(listOf("d", "a", "b", "c"), PageOrdering.move(pages, 3, 0))
        assertEquals(listOf("b", "c", "d", "a"), PageOrdering.move(pages, 0, 50))
        assertSame(pages, PageOrdering.move(pages, 1, 1))
        assertSame(pages, PageOrdering.move(pages, -1, 2))
    }

    @Test fun reorderIgnoresUnknownAndKeepsUnmentionedPages() {
        val current = listOf("a", "b", "c", "d")
        assertEquals(listOf("d", "c", "b", "a"), PageOrdering.reorder(current, listOf("d", "c", "b", "a")))
        assertEquals(listOf("c", "a", "b", "d"), PageOrdering.reorder(current, listOf("c", "zzz", "a", "c")))
        assertEquals(current, PageOrdering.reorder(current, emptyList()))
    }

    @Test fun rotationNormalisesToQuarterTurns() {
        assertEquals(90, PageOrdering.normalizeRotation(90))
        assertEquals(0, PageOrdering.normalizeRotation(360))
        assertEquals(270, PageOrdering.normalizeRotation(-90))
        assertEquals(180, PageOrdering.normalizeRotation(540))
        assertEquals(90, PageOrdering.normalizeRotation(80))
        assertEquals(0, PageOrdering.normalizeRotation(-720))
    }

    @Test fun audioLinksAreDroppedForOtherDocuments() {
        val anchor = AudioAnchor("rec", 1200)
        val content = PageContent(
            strokes = listOf(Stroke("s", PenType.BALLPOINT, 0, 1f, floatArrayOf(0f, 0f, 1f, 0f, 0f), 0, audio = anchor)),
            texts = listOf(TextItem("t", 0f, 0f, 10f, 10f, "hi", audio = anchor)),
        )
        val stripped = PageOrdering.withoutAudio(content)
        assertNull(stripped.strokes.single().audio)
        assertNull(stripped.texts.single().audio)
        assertEquals("hi", stripped.texts.single().text)
    }

    @Test fun backgroundGoesBeneathEverything() {
        val content = PageContent(
            strokes = listOf(Stroke("s", PenType.BALLPOINT, 0, 1f, floatArrayOf(), 0, z = -3)),
            images = listOf(ImageItem("i", "photo.png", 0f, 0f, 5f, 5f, z = 4)),
        )
        val withBg = PageOrdering.withBackground(content, ImageItem("bg", "pdfpage.png", 0f, 0f, 595f, 842f))
        assertEquals("bg", withBg.images.first().id)
        assertEquals(-4, withBg.images.first().z)
        assertEquals(setOf("pdfpage.png", "photo.png"), PageOrdering.referencedAssets(withBg))
    }
}
