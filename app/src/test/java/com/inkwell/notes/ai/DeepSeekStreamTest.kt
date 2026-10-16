package com.inkwell.notes.ai

import com.inkwell.notes.ai.engine.EngineEvent
import com.inkwell.notes.core.model.AiMessage
import com.inkwell.notes.core.model.AiPart
import com.inkwell.notes.core.model.AiProviderId
import com.inkwell.notes.core.model.AiRequest
import com.inkwell.notes.core.model.AiRole
import com.inkwell.notes.core.model.AiTask
import com.inkwell.notes.core.model.ModelRef
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepSeekStreamTest {
    private val h = EngineHarness()
    private val reasoner = ModelRef(AiProviderId.DEEPSEEK, "deepseek-reasoner")

    @After fun tearDown() = h.close()

    @Test fun reasoningContentIsSeparatedFromAnswer() {
        h.enqueueSse(
            ": keep-alive\n\n" + openAiSse(
                """{"choices":[{"index":0,"delta":{"role":"assistant","content":null,"reasoning_content":"First, "}}]}""",
                """{"choices":[{"index":0,"delta":{"content":null,"reasoning_content":"add 2 and 2."}}]}""",
                """{"choices":[{"index":0,"delta":{"content":"The answer is 4.","reasoning_content":null}}]}""",
                """{"choices":[{"index":0,"delta":{"content":""},"finish_reason":"stop"}],"usage":{"prompt_tokens":10,"completion_tokens":20}}""",
            ),
        )
        val events = h.events(AiRequest(AiTask.MATH, listOf(AiMessage(AiRole.USER, "2+2?")), model = reasoner, temperature = 0.5f))
        val reasoning = events.filterIsInstance<EngineEvent.Reasoning>().joinToString("") { it.delta }
        val text = events.filterIsInstance<EngineEvent.Text>().joinToString("") { it.delta }
        assertEquals("First, add 2 and 2.", reasoning)
        assertEquals("The answer is 4.", text)
        val done = events.last() as EngineEvent.Completed
        assertEquals("The answer is 4.", done.text)
        assertEquals("First, add 2 and 2.", done.reasoning)
        assertEquals(10, done.usage.inputTokens)
        assertEquals(20, done.usage.outputTokens)
        // deepseek-reasoner estimate: $0.55 in / $2.19 out per million.
        assertEquals(10 * 0.55 / 1e6 + 20 * 2.19 / 1e6, done.usage.estimatedCostUsd!!, 1e-12)

        val rec = h.take()
        assertEquals("/v1/chat/completions", rec.path)
        assertEquals("Bearer sk-test-deepseek-000000", rec.getHeader("Authorization"))
        val body = rec.json()
        assertFalse("reasoner does not take temperature", body.containsKey("temperature"))
        assertTrue(body.containsKey("max_tokens"))
    }

    @Test fun imagesAreStrippedForTextOnlyModelWithNote() {
        h.enqueueSse(openAiSse("""{"choices":[{"delta":{"content":"ok"},"finish_reason":"stop"}]}"""))
        val png = ByteArray(32)
        val req = AiRequest(
            AiTask.CHAT,
            listOf(AiMessage(AiRole.USER, listOf(AiPart.Image(png), AiPart.Text("What does this say?")))),
            model = ModelRef(AiProviderId.DEEPSEEK, "deepseek-chat"),
        )
        val events = h.events(req)
        val started = events.first() as EngineEvent.Started
        assertTrue(started.notes.single().contains("does not accept images"))
        val messages = h.take().json()["messages"]!!.jsonArray
        val content = messages.last().jsonObject["content"]!!
        assertTrue("text-only content is a plain string", content is JsonPrimitive)
        assertTrue(content.jsonPrimitive.content.contains("What does this say?"))
        assertFalse(content.jsonPrimitive.content.contains("base64"))
    }

    @Test fun visionTaskFailsClearlyOnTextOnlyModel() {
        val req = AiRequest(
            AiTask.OCR_ASSIST,
            listOf(AiMessage(AiRole.USER, listOf(AiPart.Image(ByteArray(8)), AiPart.Text("Transcribe")))),
            model = ModelRef(AiProviderId.DEEPSEEK, "deepseek-chat"),
        )
        val failed = h.events(req).single() as EngineEvent.Failed
        assertTrue(failed.failure.message.contains("cannot read images"))
        assertEquals(0, h.server.requestCount)
    }
}
