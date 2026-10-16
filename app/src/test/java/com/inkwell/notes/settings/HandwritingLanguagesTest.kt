package com.inkwell.notes.settings

import com.inkwell.notes.settings.logic.HandwritingLanguages
import com.inkwell.notes.settings.logic.HandwritingLanguages.Change
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HandwritingLanguagesTest {
    @Test
    fun supportedListMatchesTheBrief() {
        val expected = listOf(
            "en-US", "en-GB", "de-DE", "fr-FR", "es-ES", "it-IT", "pt-BR", "nl-NL", "sv-SE",
            "pl-PL", "tr-TR", "ru-RU", "ja-JP", "ko-KR", "zh-CN", "hi-IN", "ar",
        )
        assertEquals(expected, HandwritingLanguages.supported.map { it.tag })
        assertEquals(expected.size, HandwritingLanguages.supported.map { it.name }.toSet().size)
    }

    @Test
    fun canonicalSpelling() {
        assertEquals("en-US", HandwritingLanguages.canonical("en_us"))
        assertEquals("en-GB", HandwritingLanguages.canonical(" EN-gb "))
        assertEquals("ar", HandwritingLanguages.canonical("AR"))
        assertEquals("es-MX", HandwritingLanguages.canonical("ES_mx"))
        assertEquals("zh-Hant-TW", HandwritingLanguages.canonical("zh-hant-tw"))
        assertNull(HandwritingLanguages.canonical("  "))
    }

    @Test
    fun normalizeDeduplicatesAndNeverReturnsEmpty() {
        assertEquals(listOf("de-DE", "en-US"), HandwritingLanguages.normalize(listOf("de-de", "en_US", "DE-DE", "")))
        assertEquals(listOf("en-US"), HandwritingLanguages.normalize(emptyList()))
        assertEquals(listOf("en-US"), HandwritingLanguages.normalize(listOf(" ", "")))
    }

    @Test
    fun toggleAddsAtTheEnd() {
        val r = HandwritingLanguages.toggle(listOf("en-US"), "fr-FR")
        assertEquals(Change.Updated(listOf("en-US", "fr-FR")), r)
    }

    @Test
    fun toggleRemovesCaseInsensitively() {
        val r = HandwritingLanguages.toggle(listOf("en-US", "fr-FR", "ja-JP"), "FR_fr")
        assertEquals(Change.Updated(listOf("en-US", "ja-JP")), r)
    }

    @Test
    fun lastLanguageCannotBeRemoved() {
        val r = HandwritingLanguages.toggle(listOf("de-DE"), "de-DE")
        assertTrue(r is Change.Refused)
        // An empty stored list behaves like the default single language.
        assertTrue(HandwritingLanguages.toggle(emptyList(), "en-US") is Change.Refused)
    }

    @Test
    fun removingThePrimaryPromotesTheNext() {
        val r = HandwritingLanguages.toggle(listOf("en-US", "de-DE"), "en-US") as Change.Updated
        assertEquals("de-DE", HandwritingLanguages.primary(r.tags))
    }

    @Test
    fun makePrimaryMovesToFront() {
        assertEquals(listOf("ko-KR", "en-US", "de-DE"), HandwritingLanguages.makePrimary(listOf("en-US", "ko-KR", "de-DE"), "ko-kr"))
        assertEquals(listOf("ar", "en-US"), HandwritingLanguages.makePrimary(listOf("en-US"), "ar"))
    }

    @Test
    fun selectionChecks() {
        assertTrue(HandwritingLanguages.isSelected(listOf("en-US", "hi-IN"), "HI_in"))
        assertFalse(HandwritingLanguages.isSelected(listOf("en-US"), "hi-IN"))
        assertTrue(HandwritingLanguages.isSelected(emptyList(), "en-US"))
    }

    @Test
    fun rowsKeepUnsupportedSelectedTagsVisible() {
        val rows = HandwritingLanguages.rows(listOf("en-US", "es-MX"))
        assertEquals(HandwritingLanguages.supported.size + 1, rows.size)
        val extra = rows.last()
        assertEquals("es-MX", extra.tag)
        assertFalse(extra.supported)
        assertTrue(rows.dropLast(1).all { it.supported })
    }

    @Test
    fun displayNamesAndSummary() {
        assertEquals("Japanese", HandwritingLanguages.displayName("ja-JP"))
        assertEquals("Arabic", HandwritingLanguages.displayName("ar"))
        assertEquals("English (US), German", HandwritingLanguages.summary(listOf("en-US", "de-DE")))
    }
}
