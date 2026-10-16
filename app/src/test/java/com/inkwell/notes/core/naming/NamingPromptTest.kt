package com.inkwell.notes.core.naming

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NamingPromptTest {

    @Test fun headingsAndKeyTermsComeFirst() {
        val content = NamingContent(
            listOf(
                NamingSegment("first body line", SegmentKind.LEAD),
                NamingSegment("Thermodynamics", SegmentKind.HEADING),
                NamingSegment("second body line", SegmentKind.BODY),
            ),
        )
        val msg = NamingPrompt.userMessage(content, listOf("Entropy", "Heat Engines"))
        assertTrue(msg.startsWith("Headings:\n- Thermodynamics"))
        assertTrue(msg.indexOf("Repeated terms: Entropy, Heat Engines") < msg.indexOf("Notes:"))
        assertTrue(msg.indexOf("first body line") < msg.indexOf("second body line"))
    }

    @Test fun longContentIsCutAtAWordBoundaryWithinTheLimit() {
        val long = (1..3000).joinToString(" ") { "word$it" }
        val msg = NamingPrompt.userMessage(NamingContent.fromPlainText(long), listOf("Word"))
        assertTrue(msg.length <= NamingPrompt.MAX_CONTENT_CHARS)
        assertTrue(msg.length > NamingPrompt.MAX_CONTENT_CHARS - 50)
        assertTrue(Regex("word\\d+$").containsMatchIn(msg))
    }

    @Test fun duplicateHeadingsAreListedOnce() {
        val content = NamingContent(
            listOf(NamingSegment("Genetics", SegmentKind.HEADING), NamingSegment("genetics", SegmentKind.HEADING)),
        )
        assertEquals(1, Regex("- ").findAll(NamingPrompt.userMessage(content, emptyList())).count())
    }

    @Test fun parsesReplies() {
        assertEquals(NamingPrompt.Reply.Topic("Monetary Policy"), NamingPrompt.parseReply("Monetary Policy"))
        assertEquals(NamingPrompt.Reply.Topic("Monetary Policy"), NamingPrompt.parseReply("  **monetary policy**  "))
        assertEquals(NamingPrompt.Reply.Topic("Monetary Policy"), NamingPrompt.parseReply("Title: Monetary Policy"))
        assertEquals(NamingPrompt.Reply.Topic("Monetary Policy"), NamingPrompt.parseReply("2026-10-15 Monetary Policy"))
        assertEquals(NamingPrompt.Reply.Topic("Bank of England"), NamingPrompt.parseReply("bank of england"))
        assertEquals(NamingPrompt.Reply.Topic("Photosynthesis"), NamingPrompt.parseReply("<think>plants</think>\nPhotosynthesis"))
        assertEquals(NamingPrompt.Reply.Insufficient, NamingPrompt.parseReply("INSUFFICIENT"))
        assertEquals(NamingPrompt.Reply.Unusable, NamingPrompt.parseReply("Notes"))
        assertEquals(NamingPrompt.Reply.Unusable, NamingPrompt.parseReply("   "))
    }

    @Test fun topicsAreLimitedToFiveWords() {
        val reply = NamingPrompt.parseReply("Effects of rising interest rates on housing markets in Europe")
        assertEquals(NamingPrompt.Reply.Topic("Effects of Rising Interest Rates"), reply)
    }
}
