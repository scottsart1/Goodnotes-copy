package com.inkwell.notes.ai

import com.inkwell.notes.ai.engine.ModelRegistry
import com.inkwell.notes.ai.provider.DiarizeReferences
import com.inkwell.notes.ai.provider.DiarizedTranscriptionParser
import com.inkwell.notes.ai.provider.ModelSpec
import com.inkwell.notes.ai.provider.OpenAiModels
import com.inkwell.notes.ai.provider.WavClip
import com.inkwell.notes.core.model.AiCapability
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
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

/** 16 kHz mono 16-bit WAV of [ms] with sample i = i (so a trimmed window can be located). */
internal fun testWav(ms: Long, rate: Int = 16_000): ByteArray {
    val samples = (rate * ms / 1000).toInt()
    val data = samples * 2
    val b = ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN)
    b.put("RIFF".toByteArray()).putInt(36 + data).put("WAVE".toByteArray())
    b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
    b.put("data".toByteArray()).putInt(data)
    for (i in 0 until samples) b.putShort(i.toShort())
    return b.array()
}

class DiarizedTranscriptionTest {
    @get:Rule val tmp = TemporaryFolder()
    private val h = EngineHarness()

    @After fun tearDown() = h.close()

    private fun audio(name: String = "seg_001.m4a"): File = tmp.newFile(name).apply { writeBytes(ByteArray(2048) { it.toByte() }) }

    private fun ref(name: String, ms: Long, personId: String? = null) = SpeakerReference(name, personId, testWav(ms))

    /** Multipart fields in order: name to value (file parts as raw text). */
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

    private val diarized = """
        {"task":"transcribe","duration":42.5,"text":"Hi all. Thanks.","segments":[
          {"type":"transcript.text.segment","id":"seg_0","start":0.0,"end":4.2,"text":" Hi all.","speaker":"S1"},
          {"type":"transcript.text.segment","id":"seg_1","start":"4.5","end":"9.25","text":"Thanks, I will start.","speaker":"A"}
        ],"usage":{"type":"tokens","input_tokens":310,"output_tokens":42,"total_tokens":352}}
    """.trimIndent()

    // ------------------------------------------------------------------ request

    @Test fun requestSendsDiarizeFieldsAndReferencesAsWavDataUrls() = runBlocking {
        h.enqueueJson(diarized)
        val voice = testWav(4_000)
        val person = testWav(6_000)
        val refs = listOf(SpeakerReference("S1", null, voice), SpeakerReference("P1", "person-1", person))
        val t = h.engine.transcribeDiarized("rec", audio(), 120_000, 120_000, refs, "en-GB")!!
        assertEquals("openai:gpt-4o-transcribe-diarize", t.source)
        assertEquals("en-GB", t.language)

        val r = h.take()
        assertEquals("/v1/audio/transcriptions", r.path)
        assertEquals("POST", r.method)
        assertEquals("Bearer sk-test-openai-0000000000", r.getHeader("Authorization"))
        val parts = r.parts()
        val fields = parts.filter { it.first != "file" }
        assertEquals(
            listOf(
                "model" to "gpt-4o-transcribe-diarize",
                "response_format" to "diarized_json",
                "chunking_strategy" to "auto",
                "language" to "en",
                "known_speaker_names[]" to "S1",
                "known_speaker_names[]" to "P1",
                "known_speaker_references[]" to "data:audio/wav;base64," + Base64.getEncoder().encodeToString(voice),
                "known_speaker_references[]" to "data:audio/wav;base64," + Base64.getEncoder().encodeToString(person),
            ),
            fields,
        )
        assertEquals(1, parts.count { it.first == "file" })
        val names = parts.map { it.first }.toSet()
        for (rejected in listOf("prompt", "logprobs", "timestamp_granularities[]", "include[]", "temperature")) {
            assertFalse("$rejected must never be sent", rejected in names)
        }
    }

    @Test fun noReferencesAndNoLanguageSendsOnlyTheRequiredFields() = runBlocking {
        h.enqueueJson(diarized)
        h.engine.transcribeDiarized("rec", audio(), 30_000, 0, emptyList(), language = "English")
        val names = h.take().parts().map { it.first }
        assertEquals(listOf("model", "file", "response_format", "chunking_strategy"), names)
    }

    // ------------------------------------------------------------------ response

    @Test fun segmentsAreShiftedClampedAndKeepProviderLabels() = runBlocking {
        h.enqueueJson(diarized)
        val t = h.engine.transcribeDiarized("rec", audio(), 120_000, 120_000, emptyList(), null)!!
        assertEquals(listOf(120_000L to 124_200L, 124_500L to 129_250L), t.segments.map { it.startMs to it.endMs })
        assertEquals(listOf("Hi all.", "Thanks, I will start."), t.segments.map { it.text })
        assertEquals(listOf("S1", "A"), t.segments.map { it.speaker })
        assertTrue(t.segments.all { it.speakerLabel == null })
    }

    @Test fun parserToleratesMissingFieldsStringsAndUnknownTypes() {
        val body = """
            {"text":"x","duration":"20.0","segments":[
              {"type":"transcript.text.segment","start":1,"end":3,"text":"One","speaker":"B"},
              {"type":"transcript.text.delta","start":3,"end":4,"text":"partial"},
              {"start":5,"text":"No end","speaker":7},
              {"type":"transcript.text.segment","end":"8","text":"No start"},
              {"type":"transcript.text.segment","start":9,"end":9.5,"text":"   ","speaker":"A"},
              {"type":"transcript.text.segment","start":18,"end":25,"text":"Past the end","speaker":" "},
              "not an object",
              {"type":"transcript.text.segment","start":"bad","end":null,"text":"Bad numbers","speaker":"A"}
            ],"usage":{"type":"duration","seconds":20}}
        """.trimIndent()
        val p = DiarizedTranscriptionParser.parse(body, offsetMs = 10_000, durationMs = 10_000)
        assertEquals(listOf("One", "No end", "No start", "Past the end", "Bad numbers"), p.segments.map { it.text })
        val byText = p.segments.associateBy { it.text }
        assertEquals(11_000L to 13_000L, byText.getValue("One").let { it.startMs to it.endMs })
        assertEquals("B", byText.getValue("One").speaker)
        assertEquals("7", byText.getValue("No end").speaker)
        assertEquals(15_000L, byText.getValue("No end").endMs)
        assertEquals("missing start continues after the previous block", 15_000L, byText.getValue("No start").startMs)
        assertEquals(18_000L, byText.getValue("No start").endMs)
        assertEquals("clamped to the file", 20_000L to 20_000L, byText.getValue("Past the end").let { it.startMs to it.endMs })
        assertNull("blank label is no label", byText.getValue("Past the end").speaker)
        assertFalse(p.segments.any { it.text == "partial" })
        assertEquals(20_000L, p.durationMs)
        assertEquals(p.segments.map { it.startMs }.sorted(), p.segments.map { it.startMs })
    }

    @Test fun textOnlyOrPlainBodyBecomesOneUnlabelledBlock() {
        val p = DiarizedTranscriptionParser.parse("""{"text":"Just text.","duration":12.0}""", 60_000, 0)
        assertEquals(listOf(Triple(60_000L, 72_000L, "Just text.")), p.segments.map { Triple(it.startMs, it.endMs, it.text) })
        assertNull(p.segments.single().speaker)
        val plain = DiarizedTranscriptionParser.parse("plain words", 0, 5_000)
        assertEquals("plain words", plain.segments.single().text)
        assertTrue(DiarizedTranscriptionParser.parse("""{"segments":[]}""", 0, 5_000).segments.isEmpty())
    }

    @Test fun usageIsRecordedPerAudioMinuteWithReportedTokens() = runBlocking {
        h.enqueueJson(diarized)
        h.engine.transcribeDiarized("rec", audio(), 90_000, 0, emptyList(), null)
        val row = h.usage.rows.single()
        assertEquals(AiTask.TRANSCRIPTION.name, row.task)
        assertEquals("OPENAI", row.provider)
        assertEquals("gpt-4o-transcribe-diarize", row.model)
        assertEquals(310, row.inputTokens)
        assertEquals(42, row.outputTokens)
        assertEquals(0.006 * 1.5, row.estimatedCostUsd!!, 1e-9)
    }

    // ------------------------------------------------------------------ references

    @Test fun referencesAreLimitedUniqueAndWithinTwoToTenSeconds() {
        val long = testWav(15_000)
        val prepared = DiarizeReferences.prepare(
            listOf(
                ref("  ", 4_000),
                ref("Priya", 4_000),
                ref("priya", 5_000),
                ref("Short", 1_500),
                SpeakerReference("Garbage", null, byteArrayOf(1, 2, 3)),
                SpeakerReference("Long", null, long),
                ref("Line\nbreak", 3_000),
                ref("Exactly2", 2_000),
                ref("Fifth", 4_000),
            ),
        )
        assertEquals(listOf("Priya", "Long", "Line break", "Exactly2"), prepared.map { it.name })
        val trimmed = WavClip.parse(prepared[1].wav)!!
        assertEquals(10_000L, trimmed.durationMs)
        assertEquals(16_000, trimmed.sampleRate)
        assertEquals(1, trimmed.channels)
        // The middle 10 s of 15 s: samples 40 000..199 999.
        val firstSample = ByteBuffer.wrap(prepared[1].wav, trimmed.dataOffset, 2).order(ByteOrder.LITTLE_ENDIAN).short
        assertEquals((40_000).toShort(), firstSample)
        assertArrayEquals("valid clips are sent unchanged", testWav(4_000), prepared[0].wav)
    }

    @Test fun wavReaderSkipsExtraChunksAndRejectsNonPcmHeaders() {
        val base = testWav(3_000)
        // Insert a LIST chunk (odd size, padded) between fmt and data.
        val list = ByteBuffer.allocate(8 + 5 + 1).order(ByteOrder.LITTLE_ENDIAN).put("LIST".toByteArray()).putInt(5).put(byteArrayOf(1, 2, 3, 4, 5, 0)).array()
        val withList = base.copyOfRange(0, 36) + list + base.copyOfRange(36, base.size)
        assertEquals(3_000L, WavClip.parse(withList)!!.durationMs)
        assertNull(WavClip.parse("RIFF0000WAVEdata".toByteArray()))
        assertNull(WavClip.parse(ByteArray(0)))
        // A streamed header whose data length is larger than what follows is clamped.
        val streamed = base.copyOf().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(40, -1) }
        assertEquals(3_000L, WavClip.parse(streamed)!!.durationMs)
    }

    @Test fun refusedReferencesRetryOnceWithoutThem() = runBlocking {
        h.enqueueJson("""{"error":{"message":"Invalid known_speaker_references[0]: audio too short","type":"invalid_request_error"}}""", code = 400)
        h.enqueueJson(diarized)
        val t = h.engine.transcribeDiarized("rec", audio(), 60_000, 0, listOf(ref("S1", 3_000)), null)!!
        assertEquals(2, t.segments.size)
        assertTrue(h.take().parts().any { it.first == "known_speaker_names[]" })
        assertFalse(h.take().parts().any { it.first.startsWith("known_speaker") })
        assertEquals(2, h.server.requestCount)
    }

    @Test fun badRequestWithoutReferencesFailsWithoutRetry() = runBlocking {
        h.enqueueJson("""{"error":{"message":"Unsupported file format"}}""", code = 400)
        try {
            h.engine.transcribeDiarized("rec", audio(), 60_000, 0, emptyList(), null)
            fail("expected AiException")
        } catch (e: AiException) {
            assertFalse(e.retryable)
            assertTrue(e.message!!.contains("Unsupported file format"))
        }
        assertEquals(1, h.server.requestCount)
    }

    // ------------------------------------------------------------------ errors and availability

    @Test fun authFailureIsNotRetriedAndNeverShowsTheKey() = runBlocking {
        h.enqueueJson("""{"error":{"message":"Incorrect API key provided: sk-test-openai-0000000000"}}""", code = 401)
        try {
            h.engine.transcribeDiarized("rec", audio(), 60_000, 0, emptyList(), null)
            fail("expected AiException")
        } catch (e: AiException) {
            assertFalse(e.retryable)
            assertFalse(e.message!!.contains("sk-test-openai"))
        }
        assertEquals(1, h.server.requestCount)
        assertTrue(h.usage.rows.isEmpty())
    }

    @Test fun serverErrorsAreRetriedThenReportedAsRetryable() = runBlocking {
        repeat(3) { h.enqueueJson("""{"error":{"message":"boom"}}""", code = 500) }
        try {
            h.engine.transcribeDiarized("rec", audio(), 60_000, 0, emptyList(), null)
            fail("expected AiException")
        } catch (e: AiException) {
            assertTrue(e.retryable)
        }
        assertEquals(3, h.server.requestCount)
        assertEquals(listOf(1_000L, 2_000L), h.sleeps)
    }

    @Test fun canDiarizeNeedsAiOpenAiKeyEndpointAndBudget() = runBlocking {
        assertTrue(h.engine.canDiarize())
        h.prefs = AiPrefs(enabled = false)
        assertFalse(h.engine.canDiarize())
        assertNull(h.engine.transcribeDiarized("rec", audio("a.m4a"), 1_000, 0, emptyList(), null))

        h.prefs = AiPrefs(providers = AiProviderId.entries.map { AiProviderSettings(it, enabled = it != AiProviderId.OPENAI) })
        assertFalse(h.engine.canDiarize())

        h.prefs = AiPrefs(providers = AiProviderId.entries.map { AiProviderSettings(it, customBaseUrl = if (it == AiProviderId.OPENAI) "not a url" else null) })
        assertFalse("an unusable custom endpoint", h.engine.canDiarize())

        h.prefs = AiPrefs(monthlyHardLimitUsd = 1.0)
        h.usage.spentThisMonth = 2.0
        assertTrue("spend not read yet: the request itself checks", h.engine.canDiarize())
        try {
            h.engine.transcribeDiarized("rec", audio("b.m4a"), 1_000, 0, emptyList(), null)
            fail("expected the monthly limit")
        } catch (e: AiException) {
            assertTrue(e.message!!.contains("limit"))
        }
        assertFalse("limit reached once spend is known", h.engine.canDiarize())
        h.prefs = AiPrefs(monthlyHardLimitUsd = 5.0)
        assertTrue(h.engine.canDiarize())

        h.keys.keys.remove(AiProviderId.OPENAI)
        assertFalse(h.engine.canDiarize())
        assertNull(h.engine.transcribeDiarized("rec", audio("c.m4a"), 1_000, 0, emptyList(), null))
        assertEquals(0, h.server.requestCount)
    }

    @Test fun missingOrEmptyFileIsAnEmptyTranscriptWithoutUpload() = runBlocking {
        val t = h.engine.transcribeDiarized("rec", File(tmp.root, "missing.m4a"), 60_000, 0, listOf(ref("S1", 4_000)), null)!!
        assertTrue(t.segments.isEmpty())
        assertEquals(0, h.server.requestCount)
    }

    // ------------------------------------------------------------------ catalogue and routing

    @Test fun diarizeModelIsInTheCatalogueButNeverAPlainTranscriptionDefault() {
        val spec: ModelSpec = OpenAiModels.spec("gpt-4o-transcribe-diarize")
        assertEquals("GPT-4o Transcribe Diarize", spec.displayName)
        assertEquals(setOf(AiCapability.AUDIO_TRANSCRIPTION), spec.capabilities)
        assertEquals(0.006, spec.usdPerAudioMinute!!, 1e-12)
        assertTrue(OpenAiModels.isDiarize("gpt-4o-transcribe-diarize-2025-10-15"))
        assertFalse(OpenAiModels.isDiarize("gpt-4o-transcribe"))

        assertEquals(ModelRef(AiProviderId.OPENAI, "whisper-1"), h.engine.modelFor(AiTask.TRANSCRIPTION))
        h.models.storeLive(AiProviderId.OPENAI, listOf(OpenAiModels.spec("gpt-4o-transcribe-diarize").copy(live = true), OpenAiModels.spec("gpt-4o").copy(live = true)))
        assertEquals("only the diarize model is listed: still whisper-1", "whisper-1", h.engine.modelFor(AiTask.TRANSCRIPTION)?.modelId)
        h.models.storeLive(AiProviderId.OPENAI, listOf("gpt-4o-transcribe-diarize", "gpt-4o-mini-transcribe").map { OpenAiModels.spec(it).copy(live = true) })
        assertEquals("gpt-4o-mini-transcribe", h.engine.modelFor(AiTask.TRANSCRIPTION)?.modelId)
        assertTrue(ModelRegistry.STALE_MS > 0)
    }

    @Test fun plainTranscriptionRoutedToTheDiarizeModelStillWorks() = runBlocking {
        h.prefs = AiPrefs(routing = mapOf(AiTask.TRANSCRIPTION to ModelRef(AiProviderId.OPENAI, "gpt-4o-transcribe-diarize")))
        h.enqueueJson(diarized)
        val t = h.engine.transcribe("rec", listOf(audio()), listOf(60_000L), null)!!
        assertEquals(listOf(0L, 4_500L), t.segments.map { it.startMs })
        assertTrue("plain transcripts carry no provider labels", t.segments.all { it.speaker == null })
        val fields = h.take().parts().toMap()
        assertEquals("diarized_json", fields["response_format"])
        assertEquals("auto", fields["chunking_strategy"])
        assertNotNull(fields["file"])
    }
}
