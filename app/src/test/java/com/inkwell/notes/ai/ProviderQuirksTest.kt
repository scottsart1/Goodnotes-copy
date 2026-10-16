package com.inkwell.notes.ai

import com.inkwell.notes.ai.provider.ChatCall
import com.inkwell.notes.ai.provider.DeepSeekAdapter
import com.inkwell.notes.ai.provider.MoonshotAdapter
import com.inkwell.notes.ai.provider.OpenAiAdapter
import com.inkwell.notes.ai.provider.ProviderAdapter
import com.inkwell.notes.core.model.AiMessage
import com.inkwell.notes.core.model.AiRole
import com.inkwell.notes.core.model.AiTask
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Per-provider request quirks: o1-mini system folding, temperature rules, Moonshot clamping. */
class ProviderQuirksTest {
    private val openAi = OpenAiAdapter()
    private val deepSeek = DeepSeekAdapter()
    private val moonshot = MoonshotAdapter()

    private fun call(adapter: ProviderAdapter, model: String, messages: List<AiMessage>, temperature: Float? = null, system: String? = "Be terse.") =
        ChatCall("https://example.test/v1", true, "key", adapter.spec(model), AiTask.CHAT, system, messages, 1024, temperature)

    private fun JsonObject.messages() = this["messages"]!!.jsonArray.map { it.jsonObject }

    /** o1-mini has no system/developer role: the system prompt is folded into the first user turn that is sent. */
    @Test fun o1MiniFoldsSystemPromptIntoFirstSentUserTurn() {
        val body = openAi.chatBody(call(openAi, "o1-mini", listOf(AiMessage(AiRole.USER, "Hi"), AiMessage(AiRole.ASSISTANT, "Hello"), AiMessage(AiRole.USER, "More"))))
        val msgs = body.messages()
        assertEquals(3, msgs.size)
        assertEquals("user", msgs[0]["role"]!!.jsonPrimitive.content)
        assertEquals("Be terse.\n\nHi", msgs[0]["content"]!!.jsonPrimitive.content)
        assertEquals("More", msgs[2]["content"]!!.jsonPrimitive.content)
        assertFalse(body.containsKey("reasoning_effort"))
        assertTrue(body.containsKey("max_completion_tokens"))
    }

    /** An empty leading user turn is skipped; the system prompt must still reach the model. */
    @Test fun o1MiniSystemPromptSurvivesSkippedEmptyTurn() {
        val body = openAi.chatBody(call(openAi, "o1-mini", listOf(AiMessage(AiRole.USER, ""), AiMessage(AiRole.USER, "Hi"))))
        val msgs = body.messages()
        assertEquals(1, msgs.size)
        assertEquals("Be terse.\n\nHi", msgs[0]["content"]!!.jsonPrimitive.content)
    }

    @Test fun reasoningModelsNeverReceiveTemperature() {
        val ds = deepSeek.chatBody(call(deepSeek, "deepseek-reasoner", listOf(AiMessage(AiRole.USER, "Hi")), temperature = 0.2f))
        assertNull(ds["temperature"])
        val o4 = openAi.chatBody(call(openAi, "o4-mini", listOf(AiMessage(AiRole.USER, "Hi")), temperature = 0.2f))
        assertNull(o4["temperature"])
        val gpt5 = openAi.chatBody(call(openAi, "gpt-5", listOf(AiMessage(AiRole.USER, "Hi")), temperature = 0.2f))
        assertNull(gpt5["temperature"])
        val chat = deepSeek.chatBody(call(deepSeek, "deepseek-chat", listOf(AiMessage(AiRole.USER, "Hi")), temperature = 0.2f))
        assertEquals(0.2, chat["temperature"]!!.jsonPrimitive.double, 1e-6)
    }

    @Test fun moonshotClampsTemperatureToItsRange() {
        val body = moonshot.chatBody(call(moonshot, "moonshot-v1-32k", listOf(AiMessage(AiRole.USER, "Hi")), temperature = 1.7f))
        assertEquals(1.0, body["temperature"]!!.jsonPrimitive.double, 1e-6)
        assertFalse("Moonshot reports usage in the last choice, not via stream_options", body.containsKey("stream_options"))
        assertEquals("https://api.moonshot.ai/v1", moonshot.defaultBaseUrl)
    }

    @Test fun moonshotThinkingModelsAreReasoningAndKimiLatestIsVision() {
        val thinking = moonshot.spec("kimi-k2-thinking")
        assertTrue(thinking.has(com.inkwell.notes.core.model.AiCapability.REASONING))
        assertTrue(moonshot.spec("kimi-latest").has(com.inkwell.notes.core.model.AiCapability.VISION))
        assertFalse(moonshot.spec("moonshot-v1-8k").has(com.inkwell.notes.core.model.AiCapability.VISION))
        assertEquals(4096, moonshot.outputBudget(moonshot.spec("moonshot-v1-8k"), 16_000))
    }
}
