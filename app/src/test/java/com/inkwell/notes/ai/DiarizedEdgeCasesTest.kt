package com.inkwell.notes.ai

import com.inkwell.notes.ai.provider.DiarizedTranscriptionParser
import com.inkwell.notes.core.model.AiException
import com.inkwell.notes.core.model.AiPrefs
import com.inkwell.notes.core.model.AiProviderId
import com.inkwell.notes.core.model.AiProviderSettings
import com.inkwell.notes.core.model.AiTask
import com.inkwell.notes.core.model.ModelRef
import com.inkwell.notes.speakers.SpeakerReference
import kotlinx.coroutines.runBlocking
import okhttp3.MultipartReader
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Base64

/** Adversarial cases for gpt-4o-transcribe-diarize gating, request building, parsing and cost. */
class DiarizedEdgeCasesTest {
    @get:Rule val tmp = TemporaryFolder()
    private val h = EngineHarness()

    @After fun tearDown() = h.close()

    private fun audio(name: String = "seg_000.m4a"): File = tmp.newFile(name).apply { writeBytes(ByteArray(1024) { it.toByte() }) }

    private fun ref(name: String, ms: Long, personId: String? = null) = SpeakerReference(name, personId, testWav(ms))

    private fun RecordedRequest.parts(): List<Pair<String, String>> {
        val boundary = getHeader("Content-Type")!!.substringAfter("boundary=")
        val reader = MultipartReader(body, boundary)
        val out = ArrayList<Pair<String, String>>()
        while (true) {
            val part = reader.nextPart() ?: break
            val disposition = part.headers["Content-Disposition"]!!
            val name = Regex("name=\"([^\"]*)\"").find(disposition)!!.groupValues[1]
            out += name to part.body.readUtf8()
        }
        return out
    }

    private val twoVoices = """
        {"task":"transcribe","duration":42.5,"text":"Hi all. Thanks.","segments":[
          {"type":"transcript.text.segment","id":"seg_0","start":0.0,"end":4.2,"text":"Hi all.","speaker":"S1"},
          {"type":"transcript.text.segment","id":"seg_1","start":4.5,"end":9.25,"text":"Thanks.","speaker":"A"}
        ],"usage":{"type":"tokens","input_tokens":310,"output_tokens":42,"total_tokens":352}}
    """.trimIndent()

    private fun dataUrl(wav: ByteArray) = "data:audio/wav;base64," + Base64.getEncoder().encodeToString(wav)

    // ------------------------------------------------------------------ request building

    @Test fun namesAndClipsStayParallelWhenAReferenceIsDropped() = runBlocking {
        h.enqueueJson(twoVoices)
        val s1 = testWav(4_000)
        val p1 = testWav(6_000)
        val p2 = testWav(3_000)
        val refs = listOf(
            SpeakerReference("S1", null, s1),
            SpeakerReference("S2", null, testWav(1_000)), // too short: dropped
            SpeakerReference("P1", "p-1", p1),
            SpeakerReference("Bad", null, byteArrayOf(1, 2, 3)), // not a WAV: dropped
            SpeakerReference("P2", "p-2", p2),
        )
        h.engine.transcribeDiarized("rec", audio(), 60_000, 0, refs, null)
        val parts = h.take().parts()
        assertEquals(listOf("S1", "P1", "P2"), parts.filter { it.first == "known_speaker_names[]" }.map { it.second })
        assertEquals(listOf(dataUrl(s1), dataUrl(p1), dataUrl(p2)), parts.filter { it.first == "known_speaker_references[]" }.map { it.second })
        // Names come before clips so the arrays pair up by index on the server.
        val names = parts.indices.filter { parts[it].first == "known_speaker_names[]" }
        val clips = parts.indices.filter { parts[it].first == "known_speaker_references[]" }
        assertTrue(names.max() < clips.min())
    }

    @Test fun customEndpointIsUsedAndStillCountsAsConfigured() = runBlocking {
        val custom = h.server.url("/gateway/v1").toString().trimEnd('/')
        h.prefs = AiPrefs(providers = AiProviderId.entries.map { AiProviderSettings(it, customBaseUrl = if (it == AiProviderId.OPENAI) custom else null) })
        assertTrue(h.engine.canDiarize())
        h.enqueueJson(twoVoices)
        val t = h.engine.transcribeDiarized("rec", audio(), 60_000, 0, listOf(ref("S1", 4_000)), null)!!
        assertEquals(2, t.segments.size)
        val r = h.take()
        assertEquals("/gateway/v1/audio/transcriptions", r.path)
        assertEquals("Bearer sk-test-openai-0000000000", r.getHeader("Authorization"))
    }

    @Test fun transcriptionRouteDoesNotChangeTheDiarizeModel() = runBlocking {
        h.prefs = AiPrefs(routing = mapOf(AiTask.TRANSCRIPTION to ModelRef(AiProviderId.OPENAI, "gpt-4o-transcribe")))
        assertTrue(h.engine.canDiarize())
        h.enqueueJson(twoVoices)
        val t = h.engine.transcribeDiarized("rec", audio(), 60_000, 0, emptyList(), null)!!
        assertEquals("openai:gpt-4o-transcribe-diarize", t.source)
        assertEquals("gpt-4o-transcribe-diarize", h.take().parts().toMap()["model"])
    }

    @Test fun plainWhisperRequestIsUntouchedBySpeakerWork() = runBlocking {
        h.enqueueJson("""{"text":"Hello","language":"english","duration":3.0,"segments":[{"start":0.0,"end":3.0,"text":"Hello"}]}""")
        val t = h.engine.transcribe("rec", listOf(audio()), listOf(3_000L), null)!!
        assertEquals("openai:whisper-1", t.source)
        assertEquals(listOf(0L to 3_000L), t.segments.map { it.startMs to it.endMs })
        assertTrue(t.segments.all { it.speaker == null && it.speakerLabel == null })
        val fields = h.take().parts().filter { it.first != "file" }
        assertEquals(
            listOf("model" to "whisper-1", "response_format" to "verbose_json", "timestamp_granularities[]" to "segment"),
            fields,
        )
    }

    // ------------------------------------------------------------------ gating

    @Test fun reachedMonthlyLimitStopsTheUploadAndRecordsNothing() = runBlocking {
        h.prefs = AiPrefs(monthlyHardLimitUsd = 1.0)
        h.usage.spentThisMonth = 1.0
        try {
            h.engine.transcribeDiarized("rec", audio(), 60_000, 0, listOf(ref("S1", 4_000)), null)
            fail("expected the monthly limit")
        } catch (e: AiException) {
            assertFalse(e.retryable)
            assertTrue(e.message!!.contains("limit"))
        }
        assertEquals(0, h.server.requestCount)
        assertTrue(h.usage.rows.isEmpty())
        assertFalse("the limit is now known", h.engine.canDiarize())
    }

    @Test fun keyWithInvisibleCharactersIsAConfigErrorWithoutUpload() = runBlocking {
        h.keys.keys[AiProviderId.OPENAI] = "sk-test​-openai"
        assertTrue("configured as far as the cheap check can tell", h.engine.canDiarize())
        try {
            h.engine.transcribeDiarized("rec", audio(), 60_000, 0, emptyList(), null)
            fail("expected a configuration error")
        } catch (e: AiException) {
            assertFalse(e.retryable)
            assertTrue(e.message!!, e.message!!.contains("cannot be sent"))
            assertFalse(e.message!!.contains("sk-test"))
        }
        assertEquals(0, h.server.requestCount)
    }

    @Test fun disabledOpenAiProviderIsNotConfiguredEvenWithAKey() = runBlocking {
        h.prefs = AiPrefs(providers = AiProviderId.entries.map { AiProviderSettings(it, enabled = it != AiProviderId.OPENAI) })
        assertFalse(h.engine.canDiarize())
        assertNull(h.engine.transcribeDiarized("rec", audio(), 60_000, 0, emptyList(), null))
        assertEquals(0, h.server.requestCount)
    }

    // ------------------------------------------------------------------ reference refusal

    @Test fun payloadTooLargeRetriesOnceWithoutReferences() = runBlocking {
        h.enqueueJson("""{"error":{"message":"Request entity too large"}}""", code = 413)
        h.enqueueJson(twoVoices)
        val t = h.engine.transcribeDiarized("rec", audio(), 60_000, 0, listOf(ref("S1", 4_000)), null)!!
        assertEquals(2, t.segments.size)
        assertTrue(h.take().parts().any { it.first == "known_speaker_names[]" })
        assertFalse(h.take().parts().any { it.first.startsWith("known_speaker") })
        assertEquals(2, h.server.requestCount)
        assertEquals("one successful call is billed once", 1, h.usage.rows.size)
    }

    @Test fun unprocessableReferencesRetryOnceWithoutThem() = runBlocking {
        h.enqueueJson("""{"error":{"message":"known_speaker_references[0] could not be decoded"}}""", code = 422)
        h.enqueueJson(twoVoices)
        val t = h.engine.transcribeDiarized("rec", audio(), 60_000, 0, listOf(ref("S1", 4_000)), null)!!
        assertEquals(listOf("S1", "A"), t.segments.map { it.speaker })
        assertEquals(2, h.server.requestCount)
    }

    @Test fun referencesRefusedTwiceFailsAfterExactlyTwoRequests() = runBlocking {
        h.enqueueJson("""{"error":{"message":"Invalid known_speaker_references"}}""", code = 400)
        h.enqueueJson("""{"error":{"message":"Unsupported file format"}}""", code = 400)
        try {
            h.engine.transcribeDiarized("rec", audio(), 60_000, 0, listOf(ref("S1", 4_000)), null)
            fail("expected AiException")
        } catch (e: AiException) {
            assertFalse(e.retryable)
            assertTrue(e.message!!.contains("Unsupported file format"))
        }
        assertEquals(2, h.server.requestCount)
        assertTrue("failed calls are not billed", h.usage.rows.isEmpty())
    }

    @Test fun rateLimitedRequestIsRetriedWithReferencesIntact() = runBlocking {
        h.enqueueJson("""{"error":{"message":"Rate limit"}}""", code = 429, headers = mapOf("Retry-After" to "1"))
        h.enqueueJson(twoVoices)
        val t = h.engine.transcribeDiarized("rec", audio(), 60_000, 0, listOf(ref("S1", 4_000)), null)!!
        assertEquals(2, t.segments.size)
        assertTrue(h.take().parts().any { it.first == "known_speaker_names[]" })
        assertTrue("a rate limit is not a refusal of the clips", h.take().parts().any { it.first == "known_speaker_names[]" })
        assertEquals(listOf(1_000L), h.sleeps)
    }

    // ------------------------------------------------------------------ cost

    @Test fun unknownSegmentDurationIsBilledByTheReportedOne() = runBlocking {
        h.enqueueJson(twoVoices)
        h.engine.transcribeDiarized("rec", audio(), durationMs = 0, offsetMs = 240_000, references = emptyList(), language = null)
        val row = h.usage.rows.single()
        assertEquals(0.006 * 42.5 / 60.0, row.estimatedCostUsd!!, 1e-9)
    }

    @Test fun costUsesTheSegmentDurationNotTheBlocks() = runBlocking {
        h.enqueueJson(twoVoices)
        h.engine.transcribeDiarized("rec", audio(), 120_000, 120_000, emptyList(), null)
        assertEquals(0.006 * 2.0, h.usage.rows.single().estimatedCostUsd!!, 1e-9)
    }

    // ------------------------------------------------------------------ parsing

    @Test fun blocksPastTheFileEndCollapseToItsEnd() {
        val body = """
            {"segments":[
              {"type":"transcript.text.segment","start":8.0,"end":14.0,"text":"Runs over","speaker":"A"},
              {"type":"transcript.text.segment","start":12.0,"end":15.0,"text":"Entirely past","speaker":"B"},
              {"type":"transcript.text.segment","start":-1.0,"end":1.0,"text":"Negative start","speaker":"A"}
            ]}
        """.trimIndent()
        val p = DiarizedTranscriptionParser.parse(body, offsetMs = 120_000, durationMs = 10_000)
        val byText = p.segments.associateBy { it.text }
        assertEquals(128_000L to 130_000L, byText.getValue("Runs over").let { it.startMs to it.endMs })
        assertEquals(130_000L to 130_000L, byText.getValue("Entirely past").let { it.startMs to it.endMs })
        assertEquals(120_000L to 121_000L, byText.getValue("Negative start").let { it.startMs to it.endMs })
        assertEquals(listOf("Negative start", "Runs over", "Entirely past"), p.segments.map { it.text })
    }

    @Test fun endBeforeStartIsClampedAndLabelsAreKeptVerbatim() {
        val body = """{"segments":[{"type":"transcript.text.segment","start":5.0,"end":3.0,"text":"Backwards","speaker":"  s1 "}]}"""
        val s = DiarizedTranscriptionParser.parse(body, 0, 60_000).segments.single()
        assertEquals(5_000L to 5_000L, s.startMs to s.endMs)
        assertEquals("the caller resolves labels case-insensitively; the parser only trims", "s1", s.speaker)
        assertNull(s.speakerLabel)
    }

    @Test fun noSegmentsAndNoTextIsEmptyNotACrash() {
        assertTrue(DiarizedTranscriptionParser.parse("{}", 0, 1_000).segments.isEmpty())
        assertTrue(DiarizedTranscriptionParser.parse("", 0, 1_000).segments.isEmpty())
        assertTrue(DiarizedTranscriptionParser.parse("""{"text":"   ","segments":null}""", 0, 1_000).segments.isEmpty())
        assertTrue(DiarizedTranscriptionParser.parse("[1,2,3]", 0, 1_000).segments.single().text == "[1,2,3]")
    }

    @Test fun tokenUsageIsOptional() {
        val p = DiarizedTranscriptionParser.parse("""{"text":"x","segments":[{"start":0,"end":1,"text":"x","speaker":"A"}],"usage":{"type":"duration","seconds":"61.5"}}""", 0, 0)
        assertNull(p.inputTokens)
        assertNull(p.outputTokens)
        assertEquals(61_500L, p.durationMs)
    }
}
