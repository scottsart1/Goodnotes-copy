package com.inkwell.notes.ai.net

import okio.BufferedSource

/** One dispatched server-sent event. [data] lines are joined with '\n'. */
data class SseEvent(val event: String?, val data: String, val id: String? = null) {
    /** OpenAI-style end-of-stream sentinel. */
    val isDone: Boolean get() = data.trim() == "[DONE]"
}

/**
 * Line-based parser for `text/event-stream` (WHATWG HTML §9.2). Accepts LF, CRLF and lone CR
 * line endings (also split across chunks), comment lines (`:`), multi-line `data:` fields,
 * fields without a value, and a final event without a trailing blank line.
 *
 * Not thread-safe; use one instance per stream.
 */
class SseParser {
    private val data = StringBuilder()
    private var hasData = false
    private var event: String? = null
    private var lastId: String? = null
    private val pendingLine = StringBuilder()
    private var lastWasCr = false

    /** Feeds raw text (any chunking) and returns the events completed by it. */
    fun feed(chunk: String): List<SseEvent> {
        val out = ArrayList<SseEvent>(2)
        for (ch in chunk) {
            when (ch) {
                '\r' -> {
                    lineComplete(pendingLine.toString(), out)
                    pendingLine.setLength(0)
                    lastWasCr = true
                }
                '\n' -> {
                    if (!lastWasCr) {
                        lineComplete(pendingLine.toString(), out)
                        pendingLine.setLength(0)
                    }
                    lastWasCr = false
                }
                else -> {
                    lastWasCr = false
                    pendingLine.append(ch)
                }
            }
        }
        return out
    }

    /** Feeds one complete line (without its terminator). */
    fun feedLine(line: String): List<SseEvent> {
        val out = ArrayList<SseEvent>(1)
        // A "line" from a LF-based reader may still contain lone CRs, which also end lines.
        // Each part is a full line: the ones before a CR, and the last one before the LF.
        if (line.indexOf('\r') >= 0) {
            for (p in line.split('\r')) lineComplete(p, out)
        } else {
            lineComplete(line, out)
        }
        return out
    }

    /** End of stream: flushes a trailing line and a final event that had no blank line after it. */
    fun finish(): List<SseEvent> {
        val out = ArrayList<SseEvent>(1)
        if (pendingLine.isNotEmpty()) {
            lineComplete(pendingLine.toString(), out)
            pendingLine.setLength(0)
        }
        dispatch(out)
        return out
    }

    private fun lineComplete(line: String, out: MutableList<SseEvent>) {
        if (line.isEmpty()) {
            dispatch(out)
            return
        }
        if (line[0] == ':') return // comment / keep-alive
        val colon = line.indexOf(':')
        val field: String
        var value: String
        if (colon < 0) {
            field = line
            value = ""
        } else {
            field = line.substring(0, colon)
            value = line.substring(colon + 1)
            if (value.startsWith(' ')) value = value.substring(1)
        }
        when (field) {
            "data" -> {
                if (hasData) data.append('\n')
                data.append(value)
                hasData = true
            }
            "event" -> event = value
            "id" -> if (value.indexOf('\u0000') < 0) lastId = value
            else -> Unit // "retry" and unknown fields are ignored
        }
    }

    private fun dispatch(out: MutableList<SseEvent>) {
        if (hasData) out += SseEvent(event?.takeIf { it.isNotEmpty() }, data.toString(), lastId)
        data.setLength(0)
        hasData = false
        event = null
    }
}

/**
 * Reads [source] line by line and invokes [onEvent] for every event. Returns when the stream
 * ends or [onEvent] returns false.
 */
inline fun readSse(source: BufferedSource, parser: SseParser = SseParser(), onEvent: (SseEvent) -> Boolean) {
    while (true) {
        val line = source.readUtf8Line() ?: break
        for (e in parser.feedLine(line)) if (!onEvent(e)) return
    }
    for (e in parser.finish()) if (!onEvent(e)) return
}
