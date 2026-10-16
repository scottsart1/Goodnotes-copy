package com.inkwell.notes.ai

import com.inkwell.notes.ai.engine.AiEngine
import com.inkwell.notes.ai.engine.EngineEvent
import com.inkwell.notes.ai.engine.KeyProvider
import com.inkwell.notes.ai.engine.MemoryStore
import com.inkwell.notes.ai.engine.ModelRegistry
import com.inkwell.notes.ai.engine.UsageSink
import com.inkwell.notes.ai.provider.ProviderRegistry
import com.inkwell.notes.core.model.AiPrefs
import com.inkwell.notes.core.model.AiProviderId
import com.inkwell.notes.core.storage.db.AiUsageEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.util.concurrent.TimeUnit

class FakeKeys(vararg entries: Pair<AiProviderId, String>) : KeyProvider {
    val keys = mutableMapOf(*entries)
    override fun has(provider: AiProviderId) = keys.containsKey(provider)
    override fun get(provider: AiProviderId) = keys[provider]
}

class FakeUsage(var spentThisMonth: Double = 0.0) : UsageSink {
    val rows = mutableListOf<AiUsageEntity>()
    override suspend fun record(row: AiUsageEntity) { rows += row }
    override suspend fun costSince(sinceMs: Long): Double = spentThisMonth + rows.sumOf { it.estimatedCostUsd ?: 0.0 }
}

/** An engine wired to a MockWebServer that stands in for every provider's official endpoint. */
class EngineHarness(
    var prefs: AiPrefs = AiPrefs(),
    val keys: FakeKeys = FakeKeys(
        AiProviderId.OPENAI to "sk-test-openai-0000000000",
        AiProviderId.ANTHROPIC to "sk-ant-test-0000000000",
        AiProviderId.DEEPSEEK to "sk-test-deepseek-000000",
        AiProviderId.MOONSHOT to "sk-test-moonshot-000000",
    ),
) : AutoCloseable {
    val server = MockWebServer().apply { start() }
    val usage = FakeUsage()
    val sleeps = mutableListOf<Long>()
    val registry = ProviderRegistry.default()
    val models = ModelRegistry(registry, MemoryStore(), MemoryStore())
    val base: String = server.url("/v1").toString().trimEnd('/')

    val engine = AiEngine(
        http = OkHttpClient.Builder().readTimeout(10, TimeUnit.SECONDS).build(),
        prefs = { prefs },
        keys = keys,
        usage = usage,
        models = models,
        providers = registry,
        io = Dispatchers.IO,
        sleep = { sleeps += it },
        requireHttps = false,
        officialBaseOverride = AiProviderId.entries.associateWith { base },
    )

    fun enqueueSse(body: String, code: Int = 200) {
        server.enqueue(MockResponse().setResponseCode(code).setHeader("Content-Type", "text/event-stream").setBody(body))
    }

    fun enqueueJson(body: String, code: Int = 200, headers: Map<String, String> = emptyMap()) {
        server.enqueue(MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body).also { r -> headers.forEach { (k, v) -> r.setHeader(k, v) } })
    }

    fun take(): RecordedRequest = server.takeRequest(5, TimeUnit.SECONDS) ?: error("no request recorded")

    fun events(request: com.inkwell.notes.core.model.AiRequest): List<EngineEvent> = runBlocking { engine.streamDetailed(request).toList() }

    override fun close() = server.shutdown()
}

fun RecordedRequest.json(): JsonObject = Json.parseToJsonElement(body.readUtf8()).jsonObject

/** Builds an OpenAI-style SSE body from JSON chunks, ending with [DONE]. */
fun openAiSse(vararg chunks: String, done: Boolean = true): String = buildString {
    for (c in chunks) append("data: ").append(c).append("\n\n")
    if (done) append("data: [DONE]\n\n")
}

fun sseEvent(event: String, data: String) = "event: $event\ndata: $data\n\n"
