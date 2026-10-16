package com.inkwell.notes.ai

import com.inkwell.notes.ai.engine.EngineEvent
import com.inkwell.notes.ai.provider.StopReason
import com.inkwell.notes.core.model.AiEvent
import com.inkwell.notes.core.model.AiException
import com.inkwell.notes.core.model.AiMessage
import com.inkwell.notes.core.model.AiProviderId
import com.inkwell.notes.core.model.AiRequest
import com.inkwell.notes.core.model.AiRole
import com.inkwell.notes.core.model.AiTask
import com.inkwell.notes.core.model.ModelRef
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class OpenAiStreamTest {
    private val h = EngineHarness()
    private val gpt4o = ModelRef(AiProviderId.OPENAI, "gpt-4o")

    @After fun tearDown() = h.close()

    private fun request(model: ModelRef = gpt4o, task: AiTask = AiTask.CHAT) =
        AiRequest(task, listOf(AiMessage(AiRole.USER, "Hello?")), system = "Be brief.", model = model, documentId = "doc1")

    private val okStream = openAiSse(
        """{"id":"1","choices":[{"index":0,"delta":{"role":"assistant","content":""}}]}""",
        """{"id":"1","choices":[{"index":0,"delta":{"content":"Hello"}}]}""",
        """{"id":"1","choices":[{"index":0,"delta":{"content":" world"}}]}""",
        """{"id":"1","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}""",
        """{"id":"1","choices":[],"usage":{"prompt_tokens":12,"completion_tokens":2,"total_tokens":14}}""",
    )

    @Test fun streamsTextAndUsage() {
        h.enqueueSse(okStream)
        val events = h.events(request())
        val deltas = events.filterIsInstance<EngineEvent.Text>().joinToString("") { it.delta }
        assertEquals("Hello world", deltas)
        val done = events.last() as EngineEvent.Completed
        assertEquals("Hello world", done.text)
        assertEquals(12, done.usage.inputTokens)
        assertEquals(2, done.usage.outputTokens)
        assertFalse(done.usageEstimated)
        assertEquals(StopReason.END, done.stop)
        // gpt-4o: $2.50 in / $10 out per million tokens.
        assertEquals(12 * 2.5 / 1e6 + 2 * 10.0 / 1e6, done.usage.estimatedCostUsd!!, 1e-12)

        val rec = h.take()
        assertEquals("/v1/chat/completions", rec.path)
        assertEquals("Bearer sk-test-openai-0000000000", rec.getHeader("Authorization"))
        val body = rec.json()
        assertTrue(body["stream"]!!.jsonPrimitive.boolean)
        assertTrue(body["stream_options"]!!.jsonObject["include_usage"]!!.jsonPrimitive.boolean)
        assertEquals("gpt-4o", body["model"]!!.jsonPrimitive.content)

        val row = h.usage.rows.single()
        assertEquals("OPENAI", row.provider)
        assertEquals("gpt-4o", row.model)
        assertEquals("CHAT", row.task)
        assertEquals(12, row.inputTokens)
        assertEquals(2, row.outputTokens)
        assertEquals("doc1", row.documentId)
    }

    @Test fun publicStreamEmitsDeltasThenDone() = runBlocking {
        h.enqueueSse(okStream)
        val events = h.engine.toAiEvents(h.engine.streamDetailed(request())).toList()
        assertEquals(listOf("Hello", " world"), events.filterIsInstance<AiEvent.Delta>().map { it.text })
        val done = events.last() as AiEvent.Done
        assertEquals("Hello world", done.text)
        assertEquals(gpt4o, done.model)
    }

    @Test fun lengthFinishReasonMeansCutOff() {
        h.enqueueSse(openAiSse("""{"choices":[{"delta":{"content":"Part"},"finish_reason":"length"}]}"""))
        val done = h.events(request()).last() as EngineEvent.Completed
        assertEquals(StopReason.MAX_TOKENS, done.stop)
        assertTrue(done.usageEstimated)
    }

    @Test fun retriesWithOtherMaxTokensParameterOnce() {
        h.enqueueJson(
            """{"error":{"message":"Unsupported parameter: 'max_tokens' is not supported with this model. Use 'max_completion_tokens' instead.","type":"invalid_request_error","param":"max_tokens","code":"unsupported_parameter"}}""",
            code = 400,
        )
        h.enqueueSse(okStream)
        val done = h.events(request(ModelRef(AiProviderId.OPENAI, "gpt-4.1"))).last()
        assertTrue(done is EngineEvent.Completed)
        val first = h.take().json()
        val second = h.take().json()
        assertTrue(first.containsKey("max_tokens"))
        assertFalse(first.containsKey("max_completion_tokens"))
        assertTrue(second.containsKey("max_completion_tokens"))
        assertFalse(second.containsKey("max_tokens"))
        assertEquals(first["max_tokens"]!!.jsonPrimitive.int, second["max_completion_tokens"]!!.jsonPrimitive.int)
    }

    @Test fun retriesRateLimitWithRetryAfterThenSucceeds() {
        h.enqueueJson("""{"error":{"message":"Rate limit reached","type":"requests"}}""", code = 429, headers = mapOf("Retry-After" to "3"))
        h.enqueueSse(okStream)
        val events = h.events(request())
        val retry = events.filterIsInstance<EngineEvent.Retrying>().single()
        assertEquals(1, retry.attempt)
        assertEquals(listOf(3_000L), h.sleeps)
        assertTrue(events.last() is EngineEvent.Completed)
        assertEquals(2, h.server.requestCount)
    }

    @Test fun serverErrorsRetriedTwiceWithBackoffThenFail() {
        repeat(3) { h.enqueueJson("""{"error":{"message":"boom"}}""", code = 500) }
        val last = h.events(request()).last() as EngineEvent.Failed
        assertTrue(last.failure.retryable)
        assertEquals(3, h.server.requestCount)
        assertEquals(listOf(1_000L, 2_000L), h.sleeps)
        assertTrue(last.failure.message.contains("server error"))
        assertTrue(h.usage.rows.isEmpty())
    }

    @Test fun invalidKeyIsNotRetried() {
        h.enqueueJson("""{"error":{"message":"Incorrect API key provided: sk-test-openai-0000000000.","type":"invalid_request_error","code":"invalid_api_key"}}""", code = 401)
        val last = h.events(request()).last() as EngineEvent.Failed
        assertFalse(last.failure.retryable)
        assertTrue(last.failure.message.contains("rejected the API key"))
        assertFalse(last.failure.message.contains("sk-test"))
        assertEquals(1, h.server.requestCount)
    }

    @Test fun noRetryAfterTokensWereStreamed() {
        h.enqueueSse(
            """data: {"choices":[{"delta":{"content":"Partial"}}]}""" + "\n\n" +
                """data: {"error":{"message":"The server had an error","type":"server_error"}}""" + "\n\n",
        )
        val last = h.events(request()).last() as EngineEvent.Failed
        assertEquals("Partial", last.partialText)
        assertEquals(1, h.server.requestCount)
        assertTrue(h.sleeps.isEmpty())
        // Partial output is still accounted for.
        assertEquals(1, h.usage.rows.size)
    }

    @Test fun truncatedStreamWithoutTerminatorIsAnError() {
        h.enqueueSse("""data: {"choices":[{"delta":{"content":"Half"}}]}""" + "\n\n", code = 200)
        val last = h.events(request()).last() as EngineEvent.Failed
        assertTrue(last.failure.message.contains("interrupted"))
    }

    @Test fun completeThrowsFriendlyExceptionOnFailure() = runBlocking {
        h.enqueueJson("""{"error":{"message":"The model `gpt-9` does not exist","type":"invalid_request_error","code":"model_not_found"}}""", code = 404)
        try {
            h.engine.complete(request(ModelRef(AiProviderId.OPENAI, "gpt-9")))
            fail("expected AiException")
        } catch (e: AiException) {
            assertTrue(e.message!!.contains("\"gpt-9\" was not found"))
            assertFalse(e.retryable)
        }
    }

    @Test fun completeReturnsResult() = runBlocking {
        h.enqueueSse(okStream)
        val r = h.engine.complete(request())
        assertEquals("Hello world", r.text)
        assertNotNull(r.usage.estimatedCostUsd)
    }

    @Test fun refusalDeltaIsReportedAsRefusal() = runBlocking {
        h.enqueueSse(openAiSse("""{"choices":[{"delta":{"refusal":"I can't help with that."},"finish_reason":"stop"}]}"""))
        val events = h.engine.toAiEvents(h.engine.streamDetailed(request())).toList()
        assertEquals("The model declined this request.", (events.last() as AiEvent.Error).message)
    }
}
