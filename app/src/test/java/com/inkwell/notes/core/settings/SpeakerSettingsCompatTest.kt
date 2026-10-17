package com.inkwell.notes.core.settings

import com.inkwell.notes.core.model.AppSettings
import com.inkwell.notes.core.model.SpeakerEngine
import com.inkwell.notes.core.model.SpeakerPrefs
import com.inkwell.notes.core.model.ThemeMode
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The speakers block was added to AppSettings after preview builds shipped: settings files
 * written before it, by newer builds with fields this build does not know, and by this build
 * read by an older one must all keep every other preference.
 */
class SpeakerSettingsCompatTest {
    private val json = SettingsRepository.json

    @Test fun settingsWrittenBeforeTheSpeakersBlockDecodeWithSpeakerDefaults() {
        val old = """{"theme":"DARK","recording":{"autoTranscribe":true,"segmentSeconds":60},"privacy":{"appLock":true},"onboardingDone":true}"""
        val s = json.decodeFromString(AppSettings.serializer(), old)
        assertEquals(ThemeMode.DARK, s.theme)
        assertTrue(s.recording.autoTranscribe)
        assertEquals(60, s.recording.segmentSeconds)
        assertTrue(s.privacy.appLock)
        assertTrue(s.onboardingDone)
        assertEquals(SpeakerPrefs(), s.speakers)
        assertEquals(SpeakerEngine.DEVICE, s.speakers.engine)
        assertTrue(s.speakers.askToName)
        assertTrue(s.speakers.rememberVoices)
        assertEquals(0.55f, s.speakers.matchThreshold, 1e-6f)
    }

    @Test fun unknownSpeakerFieldsAndEnginesFromAFutureBuildAreTolerated() {
        val future = """{"speakers":{"engine":"WHISPERX_LOCAL","askToName":false,"matchThreshold":0.7,"liveIdentification":true,"nested":{"x":[1,2]}},"theme":"LIGHT"}"""
        val s = json.decodeFromString(AppSettings.serializer(), future)
        assertEquals("an engine this build does not know falls back to the default", SpeakerEngine.DEVICE, s.speakers.engine)
        assertFalse(s.speakers.askToName)
        assertEquals(0.7f, s.speakers.matchThreshold, 1e-6f)
        assertEquals(ThemeMode.LIGHT, s.theme)
    }

    @Test fun nullSpeakerValuesCoerceToDefaultsInsteadOfFailing() {
        val s = json.decodeFromString(AppSettings.serializer(), """{"speakers":{"engine":null,"rememberVoices":null,"expectedSpeakers":3}}""")
        assertEquals(SpeakerEngine.DEVICE, s.speakers.engine)
        assertTrue(s.speakers.rememberVoices)
        assertEquals(3, s.speakers.expectedSpeakers)
    }

    /** What a build that predates the speakers block sees in a settings file written by this one. */
    @Serializable
    private data class PreSpeakersSettings(
        val theme: ThemeMode = ThemeMode.SYSTEM,
        val onboardingDone: Boolean = false,
    )

    @Test fun olderBuildsIgnoreTheSpeakersBlockAndKeepTheirOwnSettings() {
        val current = AppSettings(theme = ThemeMode.DARK, onboardingDone = true, speakers = SpeakerPrefs(engine = SpeakerEngine.OPENAI, rememberVoices = false))
        val text = json.encodeToString(AppSettings.serializer(), current)
        assertTrue("the block is written", json.parseToJsonElement(text).jsonObject.containsKey("speakers"))
        val old = json.decodeFromString(PreSpeakersSettings.serializer(), text)
        assertEquals(ThemeMode.DARK, old.theme)
        assertTrue(old.onboardingDone)
        // And the round trip through this build is lossless.
        assertEquals(current, json.decodeFromString(AppSettings.serializer(), text))
    }

    @Test fun settingsJsonHoldsPreferencesAboutVoicesButNeverVoiceData() {
        val text = json.encodeToString(AppSettings.serializer(), AppSettings())
        val speakers = json.parseToJsonElement(text).jsonObject["speakers"]!!.jsonObject
        assertEquals(
            setOf("engine", "askToName", "rememberVoices", "matchThreshold", "expectedSpeakers", "downloadOnUnmeteredOnly"),
            speakers.keys,
        )
        for (word in listOf("embedding", "voiceprint", "centroid", "clip", "apiKey", "password")) {
            assertFalse("settings must not carry $word", text.contains(word, ignoreCase = true))
        }
    }
}
