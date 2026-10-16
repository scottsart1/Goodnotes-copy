package com.inkwell.notes.ai

import com.inkwell.notes.ai.engine.EngineEvent
import com.inkwell.notes.core.model.AiMessage
import com.inkwell.notes.core.model.AiProviderId
import com.inkwell.notes.core.model.AiRequest
import com.inkwell.notes.core.model.AiRole
import com.inkwell.notes.core.model.AiTask
import com.inkwell.notes.core.model.ModelRef
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * API keys must never reach the UI. Providers normally mask keys in their own error bodies,
 * but a custom gateway may echo the credential verbatim, and a gateway's key need not look
 * like `sk-...`, so pattern-based redaction alone is not enough: the engine must redact the
 * key it actually sent from every failure message, including errors raised inside a stream.
 */
class KeyRedactionTest {
    // Keys that do not match any of the Redactor's built-in prefixes (sk-, sk-ant-, Bearer ...).
    private val openAiKey = "gatewaykey_7Qx9ZpL2mN4vR8tB1wY6"
    private val anthropicKey = "proxytoken-A1B2C3D4E5F6G7H8"
    private val h = EngineHarness(keys = FakeKeys(AiProviderId.OPENAI to openAiKey, AiProviderId.ANTHROPIC to anthropicKey))

    @After fun tearDown() = h.close()

    private fun request(model: ModelRef) =
        AiRequest(AiTask.CHAT, listOf(AiMessage(AiRole.USER, "Hello?")), system = "Be brief.", model = model)

    @Test fun openAiInStreamErrorEchoingTheKeyIsRedacted() {
        h.enqueueSse("data: " + """{"error":{"message":"Invalid credential $openAiKey for this gateway","type":"invalid_request_error"}}""" + "\n\n")
        val failed = h.events(request(ModelRef(AiProviderId.OPENAI, "gpt-4o"))).last() as EngineEvent.Failed
        assertFalse(failed.failure.message, failed.failure.message.contains(openAiKey))
        assertTrue(failed.failure.message, failed.failure.message.contains("••••"))
    }

    @Test fun anthropicErrorEventEchoingTheKeyIsRedacted() {
        h.enqueueSse(sseEvent("error", """{"type":"error","error":{"type":"invalid_request_error","message":"key $anthropicKey is not allowed here"}}"""))
        val failed = h.events(request(ModelRef(AiProviderId.ANTHROPIC, "claude-opus-5-5"))).last() as EngineEvent.Failed
        assertFalse(failed.failure.message, failed.failure.message.contains(anthropicKey))
    }

    @Test fun nonStreamedJsonErrorEchoingTheKeyIsRedacted() {
        // A gateway that answers 200 with a JSON error body instead of a stream.
        h.enqueueJson("""{"error":{"message":"token $openAiKey rejected","type":"invalid_request_error"}}""")
        val failed = h.events(request(ModelRef(AiProviderId.OPENAI, "gpt-4o"))).last() as EngineEvent.Failed
        assertFalse(failed.failure.message, failed.failure.message.contains(openAiKey))
    }

    @Test fun httpErrorBodyEchoingTheKeyIsRedacted() {
        h.enqueueJson("""{"error":{"message":"Bad request: credential $openAiKey has no access","type":"invalid_request_error"}}""", code = 400)
        val failed = h.events(request(ModelRef(AiProviderId.OPENAI, "gpt-4o"))).last() as EngineEvent.Failed
        assertFalse(failed.failure.message, failed.failure.message.contains(openAiKey))
    }

    @Test fun keyValidationFailureEchoingTheKeyIsRedacted() {
        h.enqueueJson("""{"error":{"message":"Forbidden for key $openAiKey","type":"permission_error"}}""", code = 403)
        val check = kotlinx.coroutines.runBlocking { h.engine.checkKey(AiProviderId.OPENAI, openAiKey, null) }
        assertFalse(check.ok)
        assertFalse(check.message, check.message.contains(openAiKey))
    }
}
