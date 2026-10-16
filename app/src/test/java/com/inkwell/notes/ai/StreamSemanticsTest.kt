package com.inkwell.notes.ai

import com.inkwell.notes.ai.engine.AiEngine
import com.inkwell.notes.ai.engine.EngineEvent
import com.inkwell.notes.ai.provider.StopReason
import com.inkwell.notes.core.model.AiEvent
import com.inkwell.notes.core.model.AiMessage
import com.inkwell.notes.core.model.AiProviderId
import com.inkwell.notes.core.model.AiRequest
import com.inkwell.notes.core.model.AiRole
import com.inkwell.notes.core.model.AiTask
import com.inkwell.notes.core.model.ModelRef
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression coverage for stop reasons, retry boundaries and decoder fallbacks (behaviour that had no tests). */
class StreamSemanticsTest {
    private val h = EngineHarness()
    private val gpt4o = ModelRef(AiProviderId.OPENAI, "gpt-4o")
    private val reasoner = ModelRef(AiProviderId.DEEPSEEK, "deepseek-reasoner")

    @After fun tearDown() = h.close()

    private fun request(model: ModelRef = gpt4o) =
        AiRequest(AiTask.CHAT, listOf(AiMessage(AiRole.USER, "Hello?")), system = "Be brief.", model = model)

    @Test fun contentFilterStopIsAnErrorNotAnAnswer() = runBlocking {
        h.enqueueSse(openAiSse("""{"choices":[{"delta":{"content":"Some"},"finish_reason":"content_filter"}]}"""))
        val events = h.engine.toAiEvents(h.engine.streamDetailed(request())).toList()
        assertEquals(AiEngine.FILTER_MESSAGE, (events.last() as AiEvent.Error).message)
        assertTrue(events.none { it is AiEvent.Done })
    }

    @Test fun cutOffAnswerIsStillReturnedByComplete() = runBlocking {
        h.enqueueSse(openAiSse("""{"choices":[{"delta":{"content":"Partial"},"finish_reason":"length"}]}"""))
        val r = h.engine.complete(request())
        assertEquals("Partial", r.text)
        assertEquals(1, h.usage.rows.size)
    }

    /** DeepSeek streams reasoning first; a failure after it must not be retried (the provider already billed it). */
    @Test fun noRetryAfterReasoningTokensWereStreamed() {
        h.enqueueSse(
            "data: " + """{"choices":[{"delta":{"reasoning_content":"Thinking..."}}]}""" + "\n\n" +
                "data: " + """{"error":{"message":"The server had an error","type":"server_error"}}""" + "\n\n",
        )
        val events = h.events(request(reasoner))
        val failed = events.last() as EngineEvent.Failed
        assertEquals("", failed.partialText)
        assertTrue(events.any { it is EngineEvent.Reasoning })
        assertTrue(events.none { it is EngineEvent.Retrying })
        assertEquals(1, h.server.requestCount)
        // The reasoning tokens are accounted for.
        assertEquals(1, h.usage.rows.size)
        assertTrue(h.usage.rows.single().outputTokens > 0)
    }

    /** A request-shape fix (variant) and a rate-limit retry compose: both are bounded and the final attempt succeeds. */
    @Test fun variantSwitchFollowedByRateLimitStillSucceeds() {
        h.enqueueJson("""{"error":{"message":"Unsupported parameter: 'max_tokens' is not supported with this model. Use 'max_completion_tokens' instead."}}""", code = 400)
        h.enqueueJson("""{"error":{"message":"slow down"}}""", code = 429, headers = mapOf("Retry-After" to "1"))
        h.enqueueSse(openAiSse("""{"choices":[{"delta":{"content":"ok"},"finish_reason":"stop"}]}"""))
        val events = h.events(request())
        assertTrue(events.last() is EngineEvent.Completed)
        assertEquals(3, h.server.requestCount)
        assertEquals(listOf(1_000L), h.sleeps)
        h.take()
        assertTrue(h.take().json().containsKey("max_completion_tokens"))
        assertTrue("the fix survives the retry", h.take().json().containsKey("max_completion_tokens"))
    }

    /** Only one Started is emitted even when the first attempt is replaced. */
    @Test fun startedIsEmittedOncePerRequest() {
        h.enqueueJson("""{"error":{"message":"boom"}}""", code = 503)
        h.enqueueSse(openAiSse("""{"choices":[{"delta":{"content":"ok"},"finish_reason":"stop"}]}"""))
        val events = h.events(request())
        assertEquals(1, events.count { it is EngineEvent.Started })
        assertEquals(1, events.count { it is EngineEvent.Retrying })
    }

    /** Anthropic may name the event only in the SSE `event:` field; the data still decodes. */
    @Test fun anthropicEventNameFallsBackToSseEventField() {
        h.enqueueSse(
            sseEvent("message_start", """{"message":{"usage":{"input_tokens":4}}}""") +
                sseEvent("content_block_delta", """{"index":0,"delta":{"type":"text_delta","text":"Hi"}}""") +
                sseEvent("message_delta", """{"delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":1}}""") +
                sseEvent("message_stop", """{}"""),
        )
        val done = h.events(request(ModelRef(AiProviderId.ANTHROPIC, "claude-opus-5-5"))).last() as EngineEvent.Completed
        assertEquals("Hi", done.text)
        assertEquals(StopReason.END, done.stop)
        assertEquals(4, done.usage.inputTokens)
    }

    /** Moonshot reports usage inside the final choice rather than a trailing usage chunk. */
    @Test fun moonshotUsageInsideFinalChoiceIsRecorded() {
        h.enqueueSse(
            openAiSse(
                """{"choices":[{"index":0,"delta":{"content":"你好"}}]}""",
                """{"choices":[{"index":0,"delta":{},"finish_reason":"stop","usage":{"prompt_tokens":9,"completion_tokens":2,"total_tokens":11}}]}""",
            ),
        )
        val done = h.events(request(ModelRef(AiProviderId.MOONSHOT, "kimi-k2-0905-preview"))).last() as EngineEvent.Completed
        assertEquals("你好", done.text)
        assertEquals(9, done.usage.inputTokens)
        assertEquals(2, done.usage.outputTokens)
        val body = h.take().json()
        assertTrue(!body.containsKey("stream_options"))
        assertEquals(0.6 * 9 / 1e6 + 2.5 * 2 / 1e6, done.usage.estimatedCostUsd!!, 1e-12)
    }

    /** `[DONE]` without any finish_reason (some proxies) still completes the answer. */
    @Test fun doneSentinelWithoutFinishReasonCompletes() {
        h.enqueueSse(openAiSse("""{"choices":[{"delta":{"content":"ok"}}]}"""))
        val done = h.events(request()).last() as EngineEvent.Completed
        assertEquals("ok", done.text)
        assertEquals(StopReason.END, done.stop)
        assertTrue(done.usageEstimated)
        assertEquals(1, done.usage.outputTokens)
    }

    @Test fun refusalAfterTextWinsOverLaterStop() {
        h.enqueueSse(
            openAiSse(
                """{"choices":[{"delta":{"content":"I "}}]}""",
                """{"choices":[{"delta":{"refusal":"cannot help with that."}}]}""",
                """{"choices":[{"delta":{},"finish_reason":"stop"}]}""",
            ),
        )
        val done = h.events(request()).last() as EngineEvent.Completed
        assertEquals(StopReason.REFUSAL, done.stop)
    }

    @Test fun outputBudgetNeverExceedsTheModelMaximum() {
        h.enqueueSse(openAiSse("""{"choices":[{"delta":{"content":"ok"},"finish_reason":"stop"}]}"""))
        val big = AiRequest(AiTask.CHAT, listOf(AiMessage(AiRole.USER, "Hi")), model = ModelRef(AiProviderId.OPENAI, "gpt-4-turbo"), maxOutputTokens = 50_000)
        h.events(big)
        assertEquals(4_096, h.take().json()["max_tokens"]!!.jsonPrimitive.int)
    }
}
