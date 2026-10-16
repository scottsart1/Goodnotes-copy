package com.inkwell.notes.ai

import com.inkwell.notes.ai.context.Citations
import com.inkwell.notes.ai.engine.AiEngine
import com.inkwell.notes.ai.engine.EngineEvent
import com.inkwell.notes.ai.engine.MemoryStore
import com.inkwell.notes.ai.engine.ModelRegistry
import com.inkwell.notes.ai.engine.MonthWindow
import com.inkwell.notes.ai.net.AiFailure
import com.inkwell.notes.ai.provider.TranscriptionParser
import com.inkwell.notes.core.model.AiMessage
import com.inkwell.notes.core.model.AiPart
import com.inkwell.notes.core.model.AiPrefs
import com.inkwell.notes.core.model.AiProviderId
import com.inkwell.notes.core.model.AiRequest
import com.inkwell.notes.core.model.AiRole
import com.inkwell.notes.core.model.AiTask
import com.inkwell.notes.core.model.ModelRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/** Provider wire-format corner cases and timeline/limit arithmetic. */
class ProviderEdgeCasesTest {
    @get:Rule val tmp = TemporaryFolder()
    private val h = EngineHarness()

    @After fun tearDown() = h.close()

    private fun request(model: ModelRef, task: AiTask = AiTask.CHAT, parts: List<AiPart> = listOf(AiPart.Text("Hello?"))) =
        AiRequest(task, listOf(AiMessage(AiRole.USER, parts)), system = "Be brief.", model = model)

    private val claudeOk = sseEvent("message_start", """{"type":"message_start","message":{"usage":{"input_tokens":10}}}""") +
        sseEvent("content_block_delta", """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"ok"}}""") +
        sseEvent("message_delta", """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":1}}""") +
        sseEvent("message_stop", """{"type":"message_stop"}""")

    /**
     * A Claude model that is not in the catalogue (typed into routing) gets the default 16k
     * output budget; older models cap lower and answer 400 "max_tokens: 16000 > 8192". That is
     * a request-shape problem the engine can fix itself, like OpenAI's renamed parameter.
     */
    @Test fun anthropicMaxTokensAboveTheModelLimitIsRetriedWithTheLimit() {
        val model = ModelRef(AiProviderId.ANTHROPIC, "claude-3-5-sonnet-latest")
        h.enqueueJson(
            """{"type":"error","error":{"type":"invalid_request_error","message":"max_tokens: 16000 > 8192, which is the maximum allowed number of output tokens for claude-3-5-sonnet-latest"}}""",
            code = 400,
        )
        h.enqueueSse(claudeOk)
        val events = h.events(request(model))
        assertTrue(events.last().toString(), events.last() is EngineEvent.Completed)
        assertEquals(2, h.server.requestCount)
        assertEquals(16_000, h.take().json()["max_tokens"]!!.jsonPrimitive.int)
        assertEquals(8_192, h.take().json()["max_tokens"]!!.jsonPrimitive.int)
        assertTrue("shape fix is not a backoff retry", h.sleeps.isEmpty())
    }

    @Test fun anthropicOtherBadRequestsAreNotRetried() {
        h.enqueueJson("""{"type":"error","error":{"type":"invalid_request_error","message":"messages: at least one message is required"}}""", code = 400)
        val failed = h.events(request(ModelRef(AiProviderId.ANTHROPIC, "claude-opus-5-5"))).last() as EngineEvent.Failed
        assertEquals(AiFailure.Kind.BAD_REQUEST, failed.failure.kind)
        assertEquals(1, h.server.requestCount)
    }

    /** Whisper's `language` takes ISO-639-1 (two letters); anything else is a 400, so it is better omitted and auto-detected. */
    @Test fun transcriptionLanguageIsOnlySentAsTwoLetterCode() {
        assertEquals("en", TranscriptionParser.isoLanguage("en-US"))
        assertEquals("zh", TranscriptionParser.isoLanguage("zh-Hans-CN"))
        assertEquals("de", TranscriptionParser.isoLanguage("DE"))
        assertNull(TranscriptionParser.isoLanguage("fil"))
        assertNull(TranscriptionParser.isoLanguage("english"))
        assertNull(TranscriptionParser.isoLanguage(" "))
        assertNull(TranscriptionParser.isoLanguage("e"))
    }

    /** Hallucinated timestamps past the end of a file must not spill into the next file's time range. */
    @Test fun whisperTimestampsAreClampedToTheFileAndOffsetsFollowDeclaredDurations() = runBlocking {
        fun seg(name: String) = tmp.newFile(name).apply { writeBytes(ByteArray(256)) }
        // First file is 30 s long but whisper reports a segment ending at 45 s.
        h.enqueueJson("""{"text":"A B","language":"english","segments":[{"start":1.0,"end":5.0,"text":" A"},{"start":28.0,"end":45.0,"text":" B"}]}""")
        h.enqueueJson("""{"text":"C","segments":[{"start":0.0,"end":2.0,"text":" C"}]}""")
        val t = h.engine.transcribe("r", listOf(seg("a.m4a"), seg("b.m4a")), listOf(30_000L, 30_000L), null)!!
        assertEquals(listOf(1_000L, 28_000L, 30_000L), t.segments.map { it.startMs })
        assertEquals(listOf(5_000L, 30_000L, 32_000L), t.segments.map { it.endMs })
        assertEquals("english", t.language)
        // Billed for the declared 60 s, not whisper's inflated timeline.
        assertEquals(0.006, h.usage.rows.single().estimatedCostUsd!!, 1e-9)
    }

    @Test fun citationLabelsAreNormalisedAcrossSpellings() {
        val c = Citations.parse("See [P. 3], [Page 4], [pages 5–6], [D2 p.7, 9], [rec 0:05], [recording 12:30] and [D3 rec 1:00:00].")
        assertEquals(listOf("p.3", "p.4", "p.5", "p.6", "D2 p.7", "D2 p.9", "rec 0:05", "rec 12:30", "D3 rec 1:00:00"), c.map { it.label })
        assertEquals(5_000L, c[6].recordingMs)
        assertEquals("D3", c[8].docTag)
        // Wide or descending ranges are not expanded into dozens of chips.
        assertEquals(listOf("p.3"), Citations.parse("[pp. 3-99]").map { it.label })
        assertEquals(listOf("p.9"), Citations.parse("[pp. 9-3]").map { it.label })
    }

    /** The first of the month in Auckland is still the previous year in UTC on New Year's Day. */
    @Test fun monthWindowHandlesYearRollOverInAheadOfUtcZones() {
        val zone = ZoneId.of("Pacific/Auckland")
        val now = ZonedDateTime.of(2027, 1, 1, 0, 0, 5, 0, zone).toInstant().toEpochMilli()
        val expected = ZonedDateTime.of(2027, 1, 1, 0, 0, 0, 0, zone).toInstant().toEpochMilli()
        // In UTC it is still 2026, so a UTC-based month (or year) window would be wrong here.
        assertEquals(2026, ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(now), ZoneId.of("UTC")).year)
        assertEquals(expected, MonthWindow.startOfMonth(now, zone))
        // Spend logged at 23:59 local on Dec 31 belongs to the old month.
        val lateDec = ZonedDateTime.of(2026, 12, 31, 23, 59, 0, 0, zone).toInstant().toEpochMilli()
        assertTrue(lateDec < expected)
        // And the mirror image: Honolulu (UTC-10) is still on the last day of the month when UTC has moved on.
        val hnl = ZoneId.of("Pacific/Honolulu")
        val hnlNow = ZonedDateTime.of(2026, 11, 30, 20, 0, 0, 0, hnl).toInstant().toEpochMilli() // Dec 1 06:00 UTC
        assertEquals(ZonedDateTime.of(2026, 11, 1, 0, 0, 0, 0, hnl).toInstant().toEpochMilli(), MonthWindow.startOfMonth(hnlNow, hnl))
    }

    @Test fun hardLimitExactlyReachedRefusesAndEqualToZeroIsOff() = runBlocking {
        h.prefs = AiPrefs(monthlyHardLimitUsd = 5.0)
        h.usage.spentThisMonth = 5.0
        val refused = h.events(request(ModelRef(AiProviderId.OPENAI, "gpt-4o"))).single() as EngineEvent.Failed
        assertEquals(AiFailure.Kind.LIMIT, refused.failure.kind)
        assertEquals(0, h.server.requestCount)

        h.prefs = AiPrefs(monthlyHardLimitUsd = 0.0)
        h.usage.spentThisMonth = 1_000.0
        h.enqueueSse(openAiSse("""{"choices":[{"delta":{"content":"ok"},"finish_reason":"stop"}]}"""))
        assertTrue(h.events(request(ModelRef(AiProviderId.OPENAI, "gpt-4o"))).last() is EngineEvent.Completed)
    }

    /** Images are stripped for OpenAI's text-only reasoning model too, so no image_url part ever reaches it. */
    @Test fun openAiTextOnlyReasoningModelNeverReceivesImages() {
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()) + ByteArray(28)
        h.enqueueSse(openAiSse("""{"choices":[{"delta":{"content":"ok"},"finish_reason":"stop"}]}"""))
        val events = h.events(request(ModelRef(AiProviderId.OPENAI, "o3-mini"), parts = listOf(AiPart.Image(png), AiPart.Text("What is this?"))))
        val started = events.first() as EngineEvent.Started
        assertTrue(started.notes.single().contains("does not accept images"))
        val body = h.take().json()
        val sent = body.toString()
        assertFalse(sent, sent.contains("image_url"))
        assertFalse(sent, sent.contains("base64"))
        val user = body["messages"]!!.jsonArray.last().jsonObject
        assertTrue(user["content"]!!.jsonPrimitive.content.contains("What is this?"))
        assertEquals("developer", body["messages"]!!.jsonArray.first().jsonObject["role"]!!.jsonPrimitive.content)
        assertTrue(body.containsKey("max_completion_tokens"))
        assertFalse(body.containsKey("temperature"))
    }

    @Test fun visionTaskWithImagesOffFailsBeforeSending() {
        h.prefs = AiPrefs(allowImages = false)
        val png = ByteArray(32)
        val failed = h.events(request(ModelRef(AiProviderId.OPENAI, "gpt-4o"), task = AiTask.OCR_ASSIST, parts = listOf(AiPart.Image(png), AiPart.Text("Transcribe")))).single() as EngineEvent.Failed
        assertEquals(AiFailure.Kind.CONFIG, failed.failure.kind)
        assertTrue(failed.failure.message, failed.failure.message.contains("turned off"))
        assertEquals(0, h.server.requestCount)
    }

    /** OpenAI's first chunk carries `"refusal": null` and usage chunks carry `"usage": null`; neither is a refusal or a usage report. */
    @Test fun nullRefusalAndNullUsageFieldsAreIgnored() {
        h.enqueueSse(
            openAiSse(
                """{"choices":[{"index":0,"delta":{"role":"assistant","content":"","refusal":null},"finish_reason":null}],"usage":null}""",
                """{"choices":[{"index":0,"delta":{"content":"Fine."},"finish_reason":null}],"usage":null}""",
                """{"choices":[{"index":0,"delta":{},"finish_reason":"stop"}],"usage":null}""",
                """{"choices":[],"usage":{"prompt_tokens":7,"completion_tokens":2}}""",
            ),
        )
        val done = h.events(request(ModelRef(AiProviderId.OPENAI, "gpt-4o"))).last() as EngineEvent.Completed
        assertEquals("Fine.", done.text)
        assertEquals(com.inkwell.notes.ai.provider.StopReason.END, done.stop)
        assertEquals(7, done.usage.inputTokens)
        assertEquals(2, done.usage.outputTokens)
        assertFalse(done.usageEstimated)
    }

    /** Anthropic bills cache reads/writes as input: they count towards the input total. */
    @Test fun anthropicCacheTokensCountAsInput() {
        h.enqueueSse(
            sseEvent("message_start", """{"type":"message_start","message":{"usage":{"input_tokens":5,"cache_creation_input_tokens":100,"cache_read_input_tokens":40,"output_tokens":1}}}""") +
                sseEvent("content_block_delta", """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"ok"}}""") +
                sseEvent("message_delta", """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":3}}""") +
                sseEvent("message_stop", """{"type":"message_stop"}"""),
        )
        val done = h.events(request(ModelRef(AiProviderId.ANTHROPIC, "claude-opus-5-5"))).last() as EngineEvent.Completed
        assertEquals(145, done.usage.inputTokens)
        assertEquals(3, done.usage.outputTokens)
        assertFalse(done.usageEstimated)
    }

    /** A clock that moves into a new month between requests re-opens the budget without a restart. */
    @Test fun budgetReopensWhenTheMonthTurns() = runBlocking {
        val zone = ZoneId.of("UTC")
        var now = ZonedDateTime.of(2026, 11, 30, 23, 0, 0, 0, zone).toInstant().toEpochMilli()
        val usage = FakeUsage()
        val engine = AiEngine(
            http = OkHttpClient.Builder().readTimeout(10, TimeUnit.SECONDS).build(),
            prefs = { AiPrefs(monthlyHardLimitUsd = 1.0) }, keys = h.keys,
            usage = object : com.inkwell.notes.ai.engine.UsageSink {
                override suspend fun record(row: com.inkwell.notes.core.storage.db.AiUsageEntity) { usage.rows += row }
                override suspend fun costSince(sinceMs: Long) = usage.rows.filter { it.timestamp >= sinceMs }.sumOf { it.estimatedCostUsd ?: 0.0 }
            },
            models = ModelRegistry(h.registry, MemoryStore(), MemoryStore()), providers = h.registry,
            io = Dispatchers.IO, sleep = {}, clock = { now }, zone = { zone }, requireHttps = false,
            officialBaseOverride = AiProviderId.entries.associateWith { h.base },
        )
        usage.rows += com.inkwell.notes.core.storage.db.AiUsageEntity(timestamp = now - 1, provider = "OPENAI", model = "gpt-4o", task = "CHAT", inputTokens = 1, outputTokens = 1, estimatedCostUsd = 2.0, documentId = null)
        val req = request(ModelRef(AiProviderId.OPENAI, "gpt-4o"))
        assertTrue(engine.streamDetailed(req).toList().single() is EngineEvent.Failed)
        now += 2 * 60 * 60 * 1000 // 01:00 on Dec 1
        h.enqueueSse(openAiSse("""{"choices":[{"delta":{"content":"ok"},"finish_reason":"stop"}]}"""))
        assertTrue(engine.streamDetailed(req).toList().last() is EngineEvent.Completed)
        assertEquals(1, h.server.requestCount)
    }

    @Test fun missingAudioFilesAreSkippedWithoutBilling() = runBlocking {
        val missing = File(tmp.root, "none.m4a")
        val t = h.engine.transcribe("r", listOf(missing), listOf(10_000L), null)!!
        assertTrue(t.segments.isEmpty())
        assertEquals(0, h.server.requestCount)
        assertEquals(0.0, h.usage.rows.single().estimatedCostUsd!!, 0.0)
    }
}
