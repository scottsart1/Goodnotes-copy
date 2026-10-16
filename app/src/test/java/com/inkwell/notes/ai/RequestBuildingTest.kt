package com.inkwell.notes.ai

import com.inkwell.notes.ai.provider.AnthropicAdapter
import com.inkwell.notes.ai.provider.ChatCall
import com.inkwell.notes.ai.provider.DeepSeekAdapter
import com.inkwell.notes.ai.provider.MoonshotAdapter
import com.inkwell.notes.ai.provider.OpenAiAdapter
import com.inkwell.notes.ai.provider.ProviderAdapter
import com.inkwell.notes.core.model.AiCapability
import com.inkwell.notes.core.model.AiMessage
import com.inkwell.notes.core.model.AiPart
import com.inkwell.notes.core.model.AiRole
import com.inkwell.notes.core.model.AiTask
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class RequestBuildingTest {
    private val openAi = OpenAiAdapter()
    private val anthropic = AnthropicAdapter()
    private val deepSeek = DeepSeekAdapter()
    private val moonshot = MoonshotAdapter()
    private val png = byteArrayOf(1, 2, 3, 4)
    private val b64 = Base64.getEncoder().encodeToString(png)

    private fun call(
        adapter: ProviderAdapter, model: String, messages: List<AiMessage>, task: AiTask = AiTask.CHAT,
        system: String? = "System prompt", max: Int = 2048, temperature: Float? = null, official: Boolean = true,
    ) = ChatCall(
        baseUrl = "https://example.test/v1", officialEndpoint = official, apiKey = "key-123", model = adapter.spec(model), task = task,
        system = system, messages = messages, maxOutputTokens = adapter.outputBudget(adapter.spec(model), max), temperature = temperature,
    )

    private val withImage = listOf(AiMessage(AiRole.USER, listOf(AiPart.Text("Read this"), AiPart.Image(png))))
    private val textOnly = listOf(AiMessage(AiRole.USER, "Hi"))

    private fun JsonObject.messages() = this["messages"]!!.jsonArray.map { it.jsonObject }

    @Test fun openAiChatModelUsesSystemRoleMaxTokensAndImageParts() {
        val body = openAi.chatBody(call(openAi, "gpt-4o", withImage, temperature = 0.4f))
        val msgs = body.messages()
        assertEquals("system", msgs[0]["role"]!!.jsonPrimitive.content)
        assertEquals("System prompt", msgs[0]["content"]!!.jsonPrimitive.content)
        val parts = msgs[1]["content"]!!.jsonArray.map { it.jsonObject }
        assertEquals("text", parts[0]["type"]!!.jsonPrimitive.content)
        assertEquals("Read this", parts[0]["text"]!!.jsonPrimitive.content)
        assertEquals("image_url", parts[1]["type"]!!.jsonPrimitive.content)
        assertEquals("data:image/png;base64,$b64", parts[1]["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content)
        assertEquals(2048, body["max_tokens"]!!.jsonPrimitive.int)
        assertFalse(body.containsKey("max_completion_tokens"))
        assertEquals(0.4, body["temperature"]!!.jsonPrimitive.content.toDouble(), 1e-6)
        assertFalse(body.containsKey("reasoning_effort"))
    }

    @Test fun openAiReasoningModelsUseDeveloperRoleAndMaxCompletionTokens() {
        for (model in listOf("o3", "o4-mini", "gpt-5", "gpt-5-mini")) {
            val body = openAi.chatBody(call(openAi, model, textOnly, task = AiTask.NAMING, max = 64, temperature = 0.2f))
            assertEquals(model, "developer", body.messages()[0]["role"]!!.jsonPrimitive.content)
            assertTrue(model, body.containsKey("max_completion_tokens"))
            assertFalse(model, body.containsKey("max_tokens"))
            assertFalse("$model rejects temperature", body.containsKey("temperature"))
            assertEquals(model, "low", body["reasoning_effort"]!!.jsonPrimitive.content)
            // Room for hidden reasoning even for a short answer.
            assertTrue(model, body["max_completion_tokens"]!!.jsonPrimitive.int >= 4096)
        }
        val chat = openAi.chatBody(call(openAi, "gpt-5", textOnly))
        assertFalse("chat keeps default effort", chat.containsKey("reasoning_effort"))
        // gpt-5-chat is not a reasoning model but still takes max_completion_tokens.
        val g5chat = openAi.chatBody(call(openAi, "gpt-5-chat-latest", textOnly))
        assertEquals("system", g5chat.messages()[0]["role"]!!.jsonPrimitive.content)
        assertTrue(g5chat.containsKey("max_completion_tokens"))
    }

    @Test fun textOnlyMessagesAreStringsAndAssistantTurnsKept() {
        val body = openAi.chatBody(
            call(openAi, "gpt-4.1", listOf(AiMessage(AiRole.USER, "Q1"), AiMessage(AiRole.ASSISTANT, "A1"), AiMessage(AiRole.USER, "Q2")), system = null),
        )
        val msgs = body.messages()
        assertEquals(listOf("user", "assistant", "user"), msgs.map { it["role"]!!.jsonPrimitive.content })
        assertTrue(msgs.all { it["content"] is JsonPrimitive })
    }

    @Test fun maxTokensVariantSwapsParameterName() {
        val c = call(openAi, "gpt-4.1", textOnly)
        val alt = openAi.variantAfterError(c, 400, """{"error":{"message":"Unsupported parameter: 'max_tokens' is not supported with this model. Use 'max_completion_tokens' instead."}}""")!!
        val body = openAi.chatBody(alt)
        assertTrue(body.containsKey("max_completion_tokens"))
        assertFalse(body.containsKey("max_tokens"))
        // Only once.
        assertNull(openAi.variantAfterError(alt, 400, """{"error":{"message":"Unsupported parameter: 'max_completion_tokens'"}}"""))
        // Unrelated 400s are not retried.
        assertNull(openAi.variantAfterError(c, 400, """{"error":{"message":"Invalid image"}}"""))
    }

    @Test fun openAiRequestHeadersAndPath() {
        val r = openAi.chatRequest(call(openAi, "gpt-4o", textOnly))
        assertEquals("https://example.test/v1/chat/completions", r.url.toString())
        assertEquals("Bearer key-123", r.header("Authorization"))
        assertEquals("https://example.test/v1/models", openAi.modelsRequest("https://example.test/v1", "k").url.toString())
    }

    @Test fun anthropicBodyMatchesMessagesApi() {
        val turns = listOf(
            AiMessage(AiRole.ASSISTANT, "leading assistant turn is dropped"),
            AiMessage(AiRole.SYSTEM, "Extra system"),
            AiMessage(AiRole.USER, listOf(AiPart.Image(png), AiPart.Text("What is this?"))),
            AiMessage(AiRole.USER, "Second part"),
            AiMessage(AiRole.ASSISTANT, "It is"),
            AiMessage(AiRole.USER, "And?"),
            AiMessage(AiRole.ASSISTANT, "Prefill must not be sent"),
        )
        val c = call(anthropic, "claude-opus-5-5", turns, temperature = 0.9f)
        val body = anthropic.chatBody(c)
        assertEquals("System prompt\n\nExtra system", body["system"]!!.jsonPrimitive.content)
        assertEquals(16000, body["max_tokens"]!!.jsonPrimitive.int)
        val msgs = body.messages()
        assertEquals(listOf("user", "assistant", "user"), msgs.map { it["role"]!!.jsonPrimitive.content })
        val first = msgs[0]["content"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("image", "text", "text"), first.map { it["type"]!!.jsonPrimitive.content })
        val src = first[0]["source"]!!.jsonObject
        assertEquals("base64", src["type"]!!.jsonPrimitive.content)
        assertEquals("image/png", src["media_type"]!!.jsonPrimitive.content)
        assertEquals(b64, src["data"]!!.jsonPrimitive.content)
        for (k in listOf("temperature", "top_p", "top_k", "thinking")) assertFalse(k, body.containsKey(k))
        assertEquals("medium", body["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        assertEquals("default", body["fallbacks"]!!.jsonPrimitive.content)

        val req = anthropic.chatRequest(c)
        assertEquals("https://example.test/v1/messages", req.url.toString())
        assertEquals("key-123", req.header("x-api-key"))
        assertEquals("2023-06-01", req.header("anthropic-version"))
        assertEquals("server-side-fallback-2026-07-01", req.header("anthropic-beta"))
        assertEquals("application/json", req.body!!.contentType().toString())
        val sent = Buffer().also { req.body!!.writeTo(it) }.readUtf8()
        assertFalse(sent.contains("key-123"))
    }

    @Test fun anthropicMaxTokensForShortTasks() {
        val naming = anthropic.chatBody(call(anthropic, "claude-sonnet-4-6", textOnly, task = AiTask.NAMING, max = 64))
        assertEquals(256, naming["max_tokens"]!!.jsonPrimitive.int)
        assertEquals("low", naming["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        assertFalse("sonnet-4-6 has no fallbacks", naming.containsKey("fallbacks"))
    }

    @Test fun anthropicEffortAndFallbackRulesPerModel() {
        val current = listOf("claude-opus-5", "claude-sonnet-5", "claude-haiku-5-5", "claude-fable-5-1", "claude-opus-4-6", "claude-opus-4-7", "claude-opus-4-8", "claude-sonnet-4-6", "claude-opus-5-5", "claude-sonnet-5-5")
        for (m in current) assertTrue(m, anthropic.chatBody(call(anthropic, m, textOnly)).containsKey("output_config"))
        for (m in listOf("claude-haiku-4-5", "claude-sonnet-4-5", "claude-3-7-sonnet-latest")) {
            assertFalse(m, anthropic.chatBody(call(anthropic, m, textOnly)).containsKey("output_config"))
        }
        val withFallbacks = setOf("claude-fable-5-1", "claude-opus-5-5", "claude-opus-5", "claude-sonnet-5-5")
        for (m in current) {
            val c = call(anthropic, m, textOnly)
            assertEquals(m, m in withFallbacks, anthropic.chatBody(c).containsKey("fallbacks"))
            assertEquals(m, m in withFallbacks, anthropic.chatRequest(c).header("anthropic-beta") != null)
        }
        val custom = call(anthropic, "claude-opus-5-5", textOnly, official = false)
        assertFalse(anthropic.chatBody(custom).containsKey("fallbacks"))
        assertNull(anthropic.chatRequest(custom).header("anthropic-beta"))
    }

    @Test fun anthropicModelsListParsing() {
        val models = anthropic.parseModels(
            """{"data":[{"type":"model","id":"claude-opus-5-5","display_name":"Claude Opus 5.5","created_at":"2026-01-01T00:00:00Z","max_input_tokens":1000000,"max_tokens":128000},
               {"type":"model","id":"claude-new-9","display_name":"Claude New 9"}],"has_more":false}""",
        )
        assertEquals(listOf("claude-opus-5-5", "claude-new-9"), models.map { it.id })
        assertTrue(models.all { it.capabilities == setOf(AiCapability.TEXT, AiCapability.VISION) })
        assertEquals(1_000_000, models[0].contextTokens)
        assertEquals(4.0, models[0].inputUsdPerMTok!!, 0.0)
        assertNull("unknown model has no price", models[1].inputUsdPerMTok)
        assertTrue(anthropic.modelsRequest("https://api.anthropic.com/v1", "k").url.toString().startsWith("https://api.anthropic.com/v1/models"))
    }

    @Test fun deepSeekDropsImagesAndMoonshotOmitsStreamOptions() {
        val ds = deepSeek.chatBody(call(deepSeek, "deepseek-chat", withImage))
        val content = ds.messages().last()["content"]!!
        assertTrue(content is JsonPrimitive)
        assertTrue(ds.containsKey("stream_options"))

        val ms = moonshot.chatBody(call(moonshot, "moonshot-v1-8k-vision-preview", withImage))
        assertFalse(ms.containsKey("stream_options"))
        val parts = ms.messages().last()["content"]!!.jsonArray
        assertEquals("image_url", parts[1].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("https://example.test/v1/chat/completions", moonshot.chatRequest(call(moonshot, "kimi-latest", textOnly)).url.toString())
    }

    @Test fun capabilitiesByModelId() {
        assertTrue(openAi.spec("gpt-4o-2024-11-20").has(AiCapability.VISION))
        assertTrue(openAi.spec("gpt-4.1-mini").has(AiCapability.VISION))
        assertTrue(openAi.spec("o3").has(AiCapability.VISION))
        assertFalse(openAi.spec("o3-mini").has(AiCapability.VISION))
        assertEquals(setOf(AiCapability.AUDIO_TRANSCRIPTION), openAi.spec("whisper-1").capabilities)
        assertEquals(setOf(AiCapability.AUDIO_TRANSCRIPTION), openAi.spec("gpt-4o-mini-transcribe").capabilities)
        assertEquals(setOf(AiCapability.EMBEDDINGS), openAi.spec("text-embedding-3-small").capabilities)
        assertTrue(openAi.spec("tts-1").capabilities.isEmpty())
        assertTrue(openAi.spec("dall-e-3").capabilities.isEmpty())
        assertTrue(openAi.spec("gpt-4o-realtime-preview").capabilities.isEmpty())
        assertEquals(0.15, openAi.spec("gpt-4o-mini").inputUsdPerMTok!!, 0.0)
        assertEquals(0.25, openAi.spec("gpt-5-mini").inputUsdPerMTok!!, 0.0)
        assertNull(openAi.spec("gpt-unknown-thing").inputUsdPerMTok)

        assertTrue(moonshot.spec("kimi-latest").has(AiCapability.VISION))
        assertTrue(moonshot.spec("moonshot-v1-32k-vision-preview").has(AiCapability.VISION))
        assertTrue(moonshot.spec("kimi-k1.5-preview").has(AiCapability.VISION))
        assertFalse(moonshot.spec("kimi-k2-0905-preview").has(AiCapability.VISION))
        assertEquals(0.6, moonshot.spec("kimi-k2-turbo-preview").inputUsdPerMTok!!, 0.0)
        assertEquals(1.0, moonshot.spec("moonshot-v1-32k").inputUsdPerMTok!!, 0.0)
        assertEquals(8_192, moonshot.spec("moonshot-v1-8k").contextTokens)

        assertFalse(deepSeek.spec("deepseek-chat").has(AiCapability.VISION))
        assertTrue(deepSeek.spec("deepseek-reasoner").has(AiCapability.REASONING))
    }

    @Test fun defaultsPreferLiveListThenCatalogue() {
        assertEquals("gpt-4o", openAi.defaultModel(AiCapability.TEXT, listOf("gpt-4o", "gpt-4o-mini", "whisper-1")))
        assertEquals("whisper-1", openAi.defaultModel(AiCapability.AUDIO_TRANSCRIPTION, listOf("gpt-4o", "whisper-1")))
        assertEquals("gpt-4.1", openAi.defaultModel(AiCapability.TEXT, emptyList()))
        assertEquals("claude-opus-5-5", anthropic.defaultModel(AiCapability.TEXT, emptyList()))
        assertEquals("claude-sonnet-5-5", anthropic.defaultModel(AiCapability.VISION, listOf("claude-sonnet-5-5", "claude-haiku-4-5")))
        assertNull(deepSeek.defaultModel(AiCapability.VISION, listOf("deepseek-chat")))
        assertEquals("kimi-latest", moonshot.defaultModel(AiCapability.VISION, emptyList()))
    }

    @Test fun openAiModelListParsingHidesSnapshots() {
        val parsed = openAi.parseModels("""{"object":"list","data":[{"id":"gpt-4o"},{"id":"gpt-4o-2024-08-06"},{"id":"tts-1"},{"id":"o3-2025-04-16"}]}""")
        val ids = parsed.map { it.id }
        assertEquals(listOf("gpt-4o", "gpt-4o-2024-08-06", "tts-1", "o3-2025-04-16"), ids)
        assertTrue(openAi.isListed(parsed[0], ids))
        assertFalse(openAi.isListed(parsed[1], ids))
        assertFalse(openAi.isListed(parsed[2], ids))
        assertTrue("snapshot without alias stays visible", openAi.isListed(parsed[3], ids))
    }
}
