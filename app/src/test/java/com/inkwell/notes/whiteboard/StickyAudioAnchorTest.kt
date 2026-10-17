package com.inkwell.notes.whiteboard

import com.inkwell.notes.core.model.AudioAnchor
import com.inkwell.notes.core.model.PageContent
import com.inkwell.notes.core.model.StickyItem
import com.inkwell.notes.core.storage.PageContentStore
import com.inkwell.notes.core.storage.PageOrdering
import com.inkwell.notes.editor.logic.AnchorLocator
import com.inkwell.notes.ink.model.Items
import com.inkwell.notes.whiteboard.logic.BoardAnchors
import com.inkwell.notes.whiteboard.logic.FragmentOps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Sticky notes added while recording keep their place on the recording timeline. */
class StickyAudioAnchorTest {
    private val anchor = AudioAnchor("rec-1", 61_000)
    private val sticky = StickyItem(id = "n1", x = 100f, y = 200f, width = 180f, height = 180f, text = "Budget", createdAt = 1, audio = anchor)
    private val content = PageContent(stickies = listOf(sticky))

    @Test
    fun itemsExposeAndReplaceTheStickyAnchor() {
        assertEquals(anchor, Items.audio(sticky))
        assertNull(Items.audio(Items.withAudio(sticky, null)))
    }

    @Test
    fun boardAnchorsFindStickiesWrittenAtThePosition() {
        val found = BoardAnchors.items(content)
        assertEquals(1, found.size)
        assertEquals(anchor, found.single().anchor)
        assertNotNull(AnchorLocator.find(listOf(0 to content), "rec-1", 61_500))
    }

    @Test
    fun anchorsAreDroppedWhenContentLeavesTheDocument() {
        assertNull(FragmentOps.withoutAudio(content).stickies.single().audio)
        assertNull(PageOrdering.withoutAudio(content).stickies.single().audio)
    }

    @Test
    fun theAnchorSurvivesProtoBufRoundTrip() {
        val decoded = PageContentStore.decodeOrNull(PageContentStore.encode(content))!!
        assertEquals(anchor, decoded.stickies.single().audio)
    }
}
