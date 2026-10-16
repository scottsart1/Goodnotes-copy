package com.inkwell.notes.ai

import com.inkwell.notes.ai.net.SseEvent
import com.inkwell.notes.ai.net.SseParser
import com.inkwell.notes.ai.net.readSse
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SseParserTest {
    private fun parseAll(text: String): List<SseEvent> {
        val p = SseParser()
        return p.feed(text) + p.finish()
    }

    @Test fun parsesSimpleEvents() {
        val events = parseAll("data: one\n\ndata: two\n\n")
        assertEquals(listOf("one", "two"), events.map { it.data })
        assertTrue(events.all { it.event == null })
    }

    @Test fun joinsMultiLineData() {
        val events = parseAll("data: first\ndata: second\ndata:third\n\n")
        assertEquals(1, events.size)
        assertEquals("first\nsecond\nthird", events[0].data)
    }

    @Test fun ignoresCommentsAndKeepAlives() {
        val events = parseAll(": keep-alive\n\n:ping\ndata: x\n\n: another\n\n")
        assertEquals(listOf("x"), events.map { it.data })
    }

    @Test fun handlesCrLfAndLoneCr() {
        assertEquals(listOf("a", "b"), parseAll("data: a\r\n\r\ndata: b\r\n\r\n").map { it.data })
        assertEquals(listOf("a", "b"), parseAll("data: a\r\rdata: b\r\r").map { it.data })
    }

    @Test fun handlesChunksSplitAnywhere() {
        val text = "event: message_start\r\ndata: {\"a\":1}\r\n\r\nevent: ping\ndata: {}\n\n"
        for (cut in 1 until text.length) {
            val p = SseParser()
            val events = p.feed(text.substring(0, cut)) + p.feed(text.substring(cut)) + p.finish()
            assertEquals("cut at $cut", listOf("message_start", "ping"), events.map { it.event })
            assertEquals("cut at $cut", "{\"a\":1}", events[0].data)
        }
    }

    @Test fun eventNamesIdsAndFieldsWithoutValue() {
        val events = parseAll("id: 7\nevent: content_block_delta\ndata: {}\n\ndata\n\n")
        assertEquals("content_block_delta", events[0].event)
        assertEquals("7", events[0].id)
        assertEquals("", events[1].data)
        assertEquals(null, events[1].event)
    }

    @Test fun stripsOnlyOneLeadingSpace() {
        assertEquals("  indented", parseAll("data:   indented\n\n")[0].data)
    }

    @Test fun recognisesDoneSentinel() {
        val events = parseAll("data: {\"x\":1}\n\ndata: [DONE]\n\n")
        assertTrue(events.last().isDone)
    }

    @Test fun flushesFinalEventWithoutBlankLine() {
        assertEquals(listOf("tail"), parseAll("data: tail").map { it.data })
    }

    @Test fun eventsWithoutDataAreNotDispatched() {
        assertEquals(0, parseAll("event: ping\n\n").size)
    }

    @Test fun readsFromBufferedSource() {
        val buffer = Buffer().writeUtf8("data: a\r\n\r\n: c\ndata: b\ndata: c\n\ndata: [DONE]\n\n")
        val seen = mutableListOf<String>()
        readSse(buffer) { seen += it.data; !it.isDone }
        assertEquals(listOf("a", "b\nc", "[DONE]"), seen)
    }

    @Test fun feedLineSplitsEmbeddedCarriageReturns() {
        val p = SseParser()
        val events = p.feedLine("data: a\r") + p.feedLine("data: b") + p.feedLine("") + p.finish()
        assertEquals(listOf("a", "b"), events.map { it.data })
    }
}
