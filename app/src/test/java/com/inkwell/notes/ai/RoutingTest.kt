package com.inkwell.notes.ai

import com.inkwell.notes.ai.engine.EngineEvent
import com.inkwell.notes.core.model.AiCapability
import com.inkwell.notes.core.model.AiMessage
import com.inkwell.notes.core.model.AiPrefs
import com.inkwell.notes.core.model.AiProviderId
import com.inkwell.notes.core.model.AiProviderId.ANTHROPIC
import com.inkwell.notes.core.model.AiProviderId.DEEPSEEK
import com.inkwell.notes.core.model.AiProviderId.MOONSHOT
import com.inkwell.notes.core.model.AiProviderId.OPENAI
import com.inkwell.notes.core.model.AiProviderSettings
import com.inkwell.notes.core.model.AiRequest
import com.inkwell.notes.core.model.AiRole
import com.inkwell.notes.core.model.AiTask
import com.inkwell.notes.core.model.ModelRef
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutingTest {
    private val harnesses = mutableListOf<EngineHarness>()

    private fun harness(vararg keys: AiProviderId, prefs: AiPrefs = AiPrefs()): EngineHarness =
        EngineHarness(prefs, FakeKeys(*keys.map { it to "key-${it.name}-123456" }.toTypedArray())).also { harnesses += it }

    @After fun tearDown() = harnesses.forEach { it.close() }

    @Test fun nothingConfiguredMeansUnavailable() {
        val h = harness()
        for (t in AiTask.entries) {
            assertNull(h.engine.modelFor(t))
            assertFalse(h.engine.isAvailable(t))
        }
    }

    @Test fun masterSwitchOffDisablesEverything() {
        val h = harness(OPENAI, ANTHROPIC, prefs = AiPrefs(enabled = false))
        assertTrue(AiTask.entries.none { h.engine.isAvailable(it) })
        assertNull(h.engine.modelFor(AiTask.CHAT))
    }

    @Test fun routeUsedWhenProviderHasKeyAndCapability() {
        val route = ModelRef(DEEPSEEK, "deepseek-reasoner")
        val h = harness(DEEPSEEK, ANTHROPIC, prefs = AiPrefs(routing = mapOf(AiTask.MATH to route), defaultModel = ModelRef(ANTHROPIC, "claude-sonnet-5-5")))
        assertEquals(route, h.engine.modelFor(AiTask.MATH))
        assertEquals(ModelRef(ANTHROPIC, "claude-sonnet-5-5"), h.engine.modelFor(AiTask.CHAT))
    }

    @Test fun routeIgnoredWithoutKeyOrCapability() {
        val h = harness(
            ANTHROPIC,
            prefs = AiPrefs(
                routing = mapOf(AiTask.MATH to ModelRef(OPENAI, "o3"), AiTask.OCR_ASSIST to ModelRef(DEEPSEEK, "deepseek-chat")),
                defaultModel = ModelRef(ANTHROPIC, "claude-haiku-5-5"),
            ),
        )
        assertEquals(ModelRef(ANTHROPIC, "claude-haiku-5-5"), h.engine.modelFor(AiTask.MATH))
        assertEquals(ModelRef(ANTHROPIC, "claude-haiku-5-5"), h.engine.modelFor(AiTask.OCR_ASSIST))
    }

    @Test fun visionTaskSkipsTextOnlyDefaultAndProvider() {
        val h = harness(DEEPSEEK, MOONSHOT, prefs = AiPrefs(defaultModel = ModelRef(DEEPSEEK, "deepseek-chat")))
        assertEquals(ModelRef(DEEPSEEK, "deepseek-chat"), h.engine.modelFor(AiTask.CHAT))
        // DeepSeek has no vision model, so OCR assist goes to Moonshot's vision default.
        assertEquals(ModelRef(MOONSHOT, "kimi-latest"), h.engine.modelFor(AiTask.OCR_ASSIST))
    }

    @Test fun firstConfiguredProviderDefaultWhenNoDefaultModel() {
        val h = harness(ANTHROPIC, DEEPSEEK)
        assertEquals(ModelRef(ANTHROPIC, "claude-opus-5-5"), h.engine.modelFor(AiTask.CHAT))
        val custom = harness(
            ANTHROPIC,
            prefs = AiPrefs(providers = AiProviderId.entries.map { AiProviderSettings(it, defaultModel = if (it == ANTHROPIC) "claude-sonnet-5" else null) }),
        )
        assertEquals(ModelRef(ANTHROPIC, "claude-sonnet-5"), custom.engine.modelFor(AiTask.SUMMARIZE))
    }

    @Test fun transcriptionAndEmbeddingsNeedOpenAiKey() {
        val noOpenAi = harness(ANTHROPIC, DEEPSEEK, MOONSHOT, prefs = AiPrefs(semanticSearch = true))
        assertNull(noOpenAi.engine.modelFor(AiTask.TRANSCRIPTION))
        assertNull(noOpenAi.engine.modelFor(AiTask.EMBEDDINGS))
        assertFalse(noOpenAi.engine.isAvailable(AiTask.TRANSCRIPTION))

        val withOpenAi = harness(ANTHROPIC, OPENAI, prefs = AiPrefs(semanticSearch = true, defaultModel = ModelRef(ANTHROPIC, "claude-opus-5-5")))
        assertEquals(ModelRef(OPENAI, "whisper-1"), withOpenAi.engine.modelFor(AiTask.TRANSCRIPTION))
        assertEquals(ModelRef(OPENAI, "text-embedding-3-small"), withOpenAi.engine.modelFor(AiTask.EMBEDDINGS))
        assertTrue(withOpenAi.engine.isAvailable(AiTask.EMBEDDINGS))
        assertEquals(ModelRef(ANTHROPIC, "claude-opus-5-5"), withOpenAi.engine.modelFor(AiTask.CHAT))
    }

    @Test fun embeddingsRequireSemanticSearchAndOcrRequiresImages() {
        val h = harness(OPENAI, prefs = AiPrefs(semanticSearch = false, allowImages = false))
        assertFalse(h.engine.isAvailable(AiTask.EMBEDDINGS))
        assertFalse(h.engine.isAvailable(AiTask.OCR_ASSIST))
        assertTrue(h.engine.isAvailable(AiTask.CHAT))
    }

    @Test fun disabledProviderIsSkipped() {
        val prefs = AiPrefs(providers = AiProviderId.entries.map { AiProviderSettings(it, enabled = it != ANTHROPIC) })
        val h = harness(ANTHROPIC, DEEPSEEK, prefs = prefs)
        assertEquals(DEEPSEEK, h.engine.modelFor(AiTask.CHAT)!!.provider)
    }

    @Test fun liveModelListDrivesDefaults() {
        val h = harness(OPENAI)
        h.models.storeLive(OPENAI, listOf("gpt-4o-mini", "whisper-1").map { h.registry[OPENAI].spec(it).copy(live = true) })
        assertEquals(ModelRef(OPENAI, "gpt-4o-mini"), h.engine.modelFor(AiTask.CHAT))
    }

    @Test fun modelsWithFiltersByCapabilityAcrossConfiguredProviders() = runBlocking {
        val h = harness(DEEPSEEK, MOONSHOT)
        // Empty cache is stale: the engine fetches live lists (DeepSeek, then Moonshot).
        h.enqueueJson("""{"object":"list","data":[{"id":"deepseek-chat"},{"id":"deepseek-reasoner"}]}""")
        h.enqueueJson("""{"object":"list","data":[{"id":"moonshot-v1-8k"},{"id":"moonshot-v1-8k-vision-preview"},{"id":"kimi-latest"}]}""")
        val vision = h.engine.modelsWith(AiCapability.VISION)
        assertTrue(vision.isNotEmpty())
        assertTrue(vision.all { it.ref.provider == MOONSHOT && AiCapability.VISION in it.capabilities })
        val text = h.engine.modelsWith(AiCapability.TEXT)
        assertTrue(text.any { it.ref.provider == DEEPSEEK })
    }

    @Test fun explicitModelWithoutKeyFailsClearly() {
        val h = harness(ANTHROPIC)
        val events = h.events(AiRequest(AiTask.CHAT, listOf(AiMessage(AiRole.USER, "Hi")), model = ModelRef(OPENAI, "gpt-4o")))
        val failed = events.single() as EngineEvent.Failed
        assertTrue(failed.failure.message.contains("Add your OpenAI API key"))
        assertEquals(0, h.server.requestCount)
    }
}
