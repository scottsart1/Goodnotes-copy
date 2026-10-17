package com.inkwell.notes.editor

import com.inkwell.notes.core.model.AudioAnchor
import com.inkwell.notes.core.model.PageContent
import com.inkwell.notes.core.model.StickyItem
import com.inkwell.notes.core.model.TextItem
import com.inkwell.notes.editor.logic.FragmentOps
import com.inkwell.notes.ink.TestInk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Sticky notes carry audio anchors like every other item, so the notebook's fragment helpers
 * (paste between documents, "play from here") must treat them the same way.
 */
class StickyAnchorPropagationTest {
    private val foreign = AudioAnchor("rec-of-another-document", 42_000)

    @Test
    fun `pasting into another document drops sticky anchors too`() {
        val content = PageContent(
            strokes = listOf(TestInk.stroke("s", 0f, 0f, 10f, 10f, audio = foreign)),
            stickies = listOf(StickyItem("n", 0f, 0f, text = "Budget", audio = foreign)),
        )
        val pasted = FragmentOps.withFreshIds(content, keepAudio = false) { "fresh" }
        assertNull(pasted.strokes.single().audio)
        // A sticky pasted into another notebook must not point at a recording that notebook does not have.
        assertNull(pasted.stickies.single().audio)
    }

    @Test
    fun `fresh ids keep sticky anchors within the same document`() {
        val content = PageContent(stickies = listOf(StickyItem("n", 0f, 0f, audio = foreign)))
        assertEquals(foreign, FragmentOps.withFreshIds(content, keepAudio = true).stickies.single().audio)
    }

    @Test
    fun `play from here works for a selection of only sticky notes`() {
        val early = AudioAnchor("rec", 3_000)
        val content = PageContent(
            texts = listOf(TextItem("t", 0f, 0f, 1f, 1f, "x", audio = AudioAnchor("rec", 9_000))),
            stickies = listOf(StickyItem("n", 0f, 0f, audio = early)),
        )
        assertEquals(early, FragmentOps.firstAudioAnchor(content))
        assertEquals(early, FragmentOps.firstAudioAnchor(PageContent(stickies = content.stickies)))
    }
}
