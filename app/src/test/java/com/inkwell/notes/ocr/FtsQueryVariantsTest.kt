package com.inkwell.notes.ocr

import com.inkwell.notes.ocr.text.FtsQuery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The FTS4 simple tokenizer folds case for ASCII letters only (verified against SQLite: the
 * expression "école*" does not match an indexed "École"). Non-ASCII letters therefore have to
 * be searched in every spelling that occurs: as typed, lower-case, capitalised, upper-case.
 * ASCII letters need no variants, the tokenizer folds them on both sides.
 */
class FtsQueryVariantsTest {

    @Test
    fun asciiQueriesProduceOneExpression() {
        assertEquals(listOf("\"financ*\" \"market*\""), FtsQuery.expressions("Financ Market"))
        assertEquals(listOf("\"a\" \"bc*\""), FtsQuery.variants(listOf("a", "bc")))
    }

    @Test
    fun accentedTermsAreSearchedInEveryCase() {
        assertEquals(listOf("\"école*\"", "\"École*\""), FtsQuery.expressions("école"))
        // As typed comes first, then the other spellings; nothing is repeated.
        assertEquals(listOf("\"École*\"", "\"école*\""), FtsQuery.expressions("École"))
        // ASCII letters are folded by the tokenizer, so "ÜBER" is searched as "Über" and "über".
        assertEquals(listOf("\"Über*\"", "\"über*\""), FtsQuery.expressions("ÜBER"))
    }

    @Test
    fun nonAsciiLettersInsideATermGetAnUpperCaseVariant() {
        // An all-caps heading "NAÏVE" is indexed as the folded token "naÏve".
        assertEquals(listOf("\"naïve*\"", "\"naÏve*\""), FtsQuery.expressions("naïve"))
        assertEquals(listOf("\"Übergrößen*\"", "\"übergrößen*\"", "\"ÜbergrÖßen*\""), FtsQuery.expressions("Übergrößen"))
    }

    @Test
    fun everyTermOfAQueryChangesTogether() {
        assertEquals(listOf("\"école*\" \"übung*\"", "\"École*\" \"Übung*\""), FtsQuery.expressions("école übung"))
    }

    @Test
    fun uncasedScriptsAndEmptyQueriesStaySimple() {
        assertEquals(listOf("\"日本語*\""), FtsQuery.expressions("日本語"))
        assertEquals(listOf("\"straße*\""), FtsQuery.expressions("straße"))
        assertTrue(FtsQuery.expressions("...").isEmpty())
        assertTrue(FtsQuery.variants(emptyList()).isEmpty())
    }

    @Test
    fun invisibleFormatCharactersAreNeitherTermsNorSeparators() {
        // A zero-width non-joiner inside a Persian word, a soft hyphen, a byte order mark: the
        // index drops them (see IndexText), so the query drops them too and the word matches
        // whether or not it was typed with them.
        assertEquals(listOf("میخواهم"), FtsQuery.tokens("می‌خواهم"))
        assertEquals(listOf("میخواهم"), FtsQuery.tokens("میخواهم"))
        assertEquals(listOf("information"), FtsQuery.tokens("in­formation"))
        assertEquals(listOf("hello"), FtsQuery.tokens("﻿Hello"))
        assertEquals(listOf("miخواهم"), FtsQuery.tokens("mi‌خواهم"))
    }
}
