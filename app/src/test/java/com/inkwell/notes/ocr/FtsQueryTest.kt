package com.inkwell.notes.ocr

import com.inkwell.notes.ocr.text.FtsQuery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FtsQueryTest {
    @Test
    fun splitsLikeTheSimpleTokenizerAndLowercasesAscii() {
        assertEquals(listOf("financial", "markets"), FtsQuery.tokens("Financial Markets"))
        assertEquals(listOf("20261015", "financial", "markets"), FtsQuery.tokens("20261015_Financial-Markets"))
        assertEquals(listOf("p", "3"), FtsQuery.tokens("p.3"))
    }

    @Test
    fun buildsQuotedPrefixTermsJoinedByImplicitAnd() {
        assertEquals("\"financ*\" \"market*\"", FtsQuery.forQuery("financ market"))
    }

    @Test
    fun singleCharacterTermsMatchExactly() {
        assertEquals("\"a\" \"bc*\"", FtsQuery.build(listOf("a", "bc")))
    }

    @Test
    fun operatorWordsAndPunctuationCannotChangeTheQuery() {
        val q = FtsQuery.forQuery("cats OR NOT \"dogs\" -mice NEAR(x) AND*")
        assertEquals("\"cats*\" \"or*\" \"not*\" \"dogs*\" \"mice*\" \"near*\" \"x\" \"and*\"", q)
        // Every term is a quoted phrase: no bare operator survives.
        val bare = q!!.split(' ').filter { !it.startsWith("\"") }
        assertTrue(bare.isEmpty())
    }

    @Test
    fun emptyOrPunctuationOnlyQueriesHaveNoMatchExpression() {
        assertNull(FtsQuery.forQuery(""))
        assertNull(FtsQuery.forQuery("   "))
        assertNull(FtsQuery.forQuery("\"*()-:^"))
        assertNull(FtsQuery.build(emptyList()))
    }

    @Test
    fun typographicPunctuationIsNotPartOfTerms() {
        assertEquals(listOf("market", "rates"), FtsQuery.tokens("“market” — rates…"))
    }

    @Test
    fun nonAsciiLettersStayInTermsWithOnlyAsciiFolded() {
        // The index folds ASCII only, so the query does the same.
        assertEquals(listOf("cafÉ", "Über"), FtsQuery.tokens("CAFÉ Über"))
        assertEquals(listOf("café"), FtsQuery.tokens("café"))
        // In-memory matching (ranking, highlighting) folds everything.
        assertEquals(listOf("école"), FtsQuery.matchTokens("École"))
    }

    @Test
    fun escapesQuotesAndLikeWildcards() {
        assertEquals("a\"\"b", FtsQuery.escape("a\"b"))
        assertEquals("50\\%\\_off\\\\", FtsQuery.escapeLike("50%_off\\"))
    }

    @Test
    fun deduplicatesAndCapsTerms() {
        assertEquals(listOf("a", "b"), FtsQuery.tokens("a b A B a"))
        val many = (1..30).joinToString(" ") { "w$it" }
        assertEquals(FtsQuery.MAX_TOKENS, FtsQuery.tokens(many).size)
    }

    @Test
    fun capsVeryLongTerms() {
        val long = "x".repeat(500)
        assertEquals(64, FtsQuery.tokens(long).single().length)
    }
}
