package com.inkwell.notes.ai

import com.inkwell.notes.ai.engine.AiEngine
import com.inkwell.notes.ai.engine.EngineEvent
import com.inkwell.notes.ai.engine.MemoryStore
import com.inkwell.notes.ai.engine.ModelRegistry
import com.inkwell.notes.ai.engine.UsageSink
import com.inkwell.notes.ai.net.AiFailure
import com.inkwell.notes.ai.provider.ProviderRegistry
import com.inkwell.notes.core.model.AiException
import com.inkwell.notes.core.model.AiMessage
import com.inkwell.notes.core.model.AiPrefs
import com.inkwell.notes.core.model.AiProviderId
import com.inkwell.notes.core.model.AiRequest
import com.inkwell.notes.core.model.AiRole
import com.inkwell.notes.core.model.AiTask
import com.inkwell.notes.core.model.ModelRef
import com.inkwell.notes.core.storage.db.AiUsageEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/** Regression coverage for stream, retry and budget edge cases that had none. */
class EngineEdgeCasesTest {
    private val h = EngineHarness()
    private val gpt4o = ModelRef(AiProviderId.OPENAI, "gpt-4o")
    private val claude = ModelRef(AiProviderId.ANTHROPIC, "claude-opus-5-5")

    @After fun tearDown() = h.close()

    private fun request(model: ModelRef = gpt4o, temperature: Float? = null) =
        AiRequest(AiTask.CHAT, listOf(AiMessage(AiRole.USER, "Hello?")), system = "Be brief.", model = model, temperature = temperature, documentId = "d")

    /** Multi-byte UTF-8 (CJK, emoji, combining marks) split at arbitrary byte boundaries must decode intact. */
    @Test fun multiByteUtf8SplitAcrossChunksIsDecodedIntact() {
        val text = "日本語のテキスト 🚀 café naïve – “quotes” ∑x²"
        val body = openAiSse(
            """{"choices":[{"index":0,"delta":{"content":${kotlinx.serialization.json.JsonPrimitive(text.substring(0, 7))}}}]}""",
            """{"choices":[{"index":0,"delta":{"content":${kotlinx.serialization.json.JsonPrimitive(text.substring(7))}}}]}""",
            """{"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}""",
        )
        // One byte per HTTP chunk: every multi-byte character crosses a chunk boundary.
        h.server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setChunkedBody(body, 1))
        val events = h.events(request())
        val done = events.last() as EngineEvent.Completed
        assertEquals(text, done.text)
        assertEquals(text, events.filterIsInstance<EngineEvent.Text>().joinToString("") { it.delta })
    }

    /** Anthropic streams interleave pings and comments; events without data must not disturb decoding. */
    @Test fun pingsCommentsAndDatalessEventsAreIgnored() {
        val body = buildString {
            append(": keep-alive\n\n")
            append(sseEvent("message_start", """{"type":"message_start","message":{"usage":{"input_tokens":10}}}"""))
            append("event: ping\n\n") // event name without data: must not attach to the next event
            append(sseEvent("content_block_start", """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}"""))
            append(": another comment\n")
            append(sseEvent("content_block_delta", """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hi"}}"""))
            append(sseEvent("content_block_stop", """{"type":"content_block_stop","index":0}"""))
            append(sseEvent("message_delta", """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":1}}"""))
            append(sseEvent("message_stop", """{"type":"message_stop"}"""))
        }
        h.enqueueSse(body)
        val done = h.events(request(claude)).last() as EngineEvent.Completed
        assertEquals("Hi", done.text)
        assertEquals(10, done.usage.inputTokens)
        assertEquals(1, done.usage.outputTokens)
        assertFalse(done.usageEstimated)
    }

    /** A 400 that names an unsupported temperature is retried once without it (variant), not surfaced. */
    @Test fun unsupportedTemperatureIsDroppedAndRetriedOnce() {
        h.enqueueJson(
            """{"error":{"message":"Unsupported value: 'temperature' does not support 0.2 with this model. Only the default (1) value is supported.","type":"invalid_request_error","param":"temperature","code":"unsupported_value"}}""",
            code = 400,
        )
        h.enqueueSse(openAiSse("""{"choices":[{"index":0,"delta":{"content":"ok"},"finish_reason":"stop"}]}"""))
        val events = h.events(request(temperature = 0.2f))
        assertTrue(events.last() is EngineEvent.Completed)
        assertEquals(2, h.server.requestCount)
        val first = h.take().json()
        val second = h.take().json()
        assertEquals(0.2, first["temperature"]!!.jsonPrimitive.content.toDouble(), 1e-6)
        assertNull("second attempt omits temperature", second["temperature"])
        assertTrue("variant switch is not a backoff retry", h.sleeps.isEmpty())
    }

    /** Retry-After may be an HTTP date; it is honoured as a wait when within the cap. */
    @Test fun retryAfterHttpDateIsHonoured() {
        val at = ZonedDateTime.now(ZoneId.of("GMT")).plusSeconds(6)
        h.enqueueJson("""{"error":{"message":"slow down","type":"rate_limit_error"}}""", code = 429, headers = mapOf("Retry-After" to DateTimeFormatter.RFC_1123_DATE_TIME.format(at)))
        h.enqueueSse(openAiSse("""{"choices":[{"index":0,"delta":{"content":"ok"},"finish_reason":"stop"}]}"""))
        val events = h.events(request())
        assertTrue(events.last() is EngineEvent.Completed)
        assertEquals(1, h.sleeps.size)
        assertTrue("waited about 6 s, got ${h.sleeps[0]}", h.sleeps[0] in 3_000L..6_000L)
    }

    /** The monthly limit is a calendar month in the device zone, not a UTC month or a rolling 30 days. */
    @Test fun monthlyLimitUsesLocalCalendarMonth() = runBlocking {
        val zone = ZoneId.of("America/New_York")
        // Just after midnight on the 1st, local time (which is still the previous day in UTC).
        val now = ZonedDateTime.of(2026, 10, 1, 0, 0, 30, 0, zone).toInstant().toEpochMilli()
        val lateLastMonth = ZonedDateTime.of(2026, 9, 30, 22, 0, 0, 0, zone).toInstant().toEpochMilli() // Oct 1 02:00 UTC
        val rows = mutableListOf(AiUsageEntity(timestamp = lateLastMonth, provider = "OPENAI", model = "gpt-4o", task = "CHAT", inputTokens = 1, outputTokens = 1, estimatedCostUsd = 100.0, documentId = null))
        val sink = object : UsageSink {
            override suspend fun record(row: AiUsageEntity) { rows += row }
            override suspend fun costSince(sinceMs: Long): Double = rows.filter { it.timestamp >= sinceMs }.sumOf { it.estimatedCostUsd ?: 0.0 }
        }
        val prefs = AiPrefs(monthlyHardLimitUsd = 10.0)
        val engine = AiEngine(
            http = OkHttpClient.Builder().readTimeout(10, TimeUnit.SECONDS).build(),
            prefs = { prefs }, keys = h.keys, usage = sink,
            models = ModelRegistry(h.registry, MemoryStore(), MemoryStore()), providers = h.registry,
            io = Dispatchers.IO, sleep = {}, clock = { now }, zone = { zone }, requireHttps = false,
            officialBaseOverride = AiProviderId.entries.associateWith { h.base },
        )
        assertEquals(ZonedDateTime.of(2026, 10, 1, 0, 0, 0, 0, zone).toInstant().toEpochMilli(), engine.monthStart())

        // $100 spent 2.5 hours ago but last month: this month's spend is zero, the request goes out.
        h.enqueueSse(openAiSse("""{"choices":[{"index":0,"delta":{"content":"ok"},"finish_reason":"stop"}]}"""))
        val ok = engine.streamDetailed(request()).toList()
        assertTrue(ok.last().toString(), ok.last() is EngineEvent.Completed)
        assertEquals(1, h.server.requestCount)

        // Spend at 00:00:10 today counts: the next request is refused before any network call.
        rows += AiUsageEntity(timestamp = now - 20_000, provider = "OPENAI", model = "gpt-4o", task = "CHAT", inputTokens = 1, outputTokens = 1, estimatedCostUsd = 12.0, documentId = null)
        val refused = engine.streamDetailed(request()).toList()
        val failed = refused.single() as EngineEvent.Failed
        assertEquals(AiFailure.Kind.LIMIT, failed.failure.kind)
        assertEquals(1, h.server.requestCount)
    }

    /** The hard limit also gates transcription and embeddings, which bill without a chat stream. */
    @Test fun hardLimitGatesTranscriptionAndEmbeddings() = runBlocking {
        h.prefs = AiPrefs(monthlyHardLimitUsd = 1.0, semanticSearch = true)
        h.usage.spentThisMonth = 1.5
        val audio = File.createTempFile("seg", ".m4a").apply { writeBytes(ByteArray(2048)); deleteOnExit() }
        try {
            h.engine.transcribe("rec1", listOf(audio), listOf(5_000L), null)
            fail("expected the limit to refuse transcription")
        } catch (e: AiException) {
            assertTrue(e.message!!, e.message!!.contains("limit"))
        }
        try {
            h.engine.embed(listOf("hello"))
            fail("expected the limit to refuse embeddings")
        } catch (e: AiException) {
            assertTrue(e.message!!, e.message!!.contains("limit"))
        }
        assertEquals("no network call was made", 0, h.server.requestCount)
    }
}
