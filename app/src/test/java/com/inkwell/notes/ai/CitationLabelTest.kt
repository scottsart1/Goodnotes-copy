package com.inkwell.notes.ai

import com.inkwell.notes.ai.context.Citations
import com.inkwell.notes.ai.context.SourceLabels
import com.inkwell.notes.ai.ui.Markdown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * Citation labels must agree between the three places that handle them: the labels sent to the
 * model ([SourceLabels]), the citations parsed from its answer ([Citations]) and the tappable
 * spans in the rendered answer ([Markdown.inline]).
 */
class CitationLabelTest {
    /** Tapping an inline citation looks the span's label up among the parsed citations, so they must match. */
    @Test fun inlineCitationSpanCarriesTheNormalisedLabel() {
        for (raw in listOf("[p.3]", "[p. 3]", "[P.3]", "[page 3]", "[pp. 3-4]", "[p.3, 5]", "[rec 18:40]", "[D2 p. 4]", "[Rec 1:02:03]")) {
            val span = Markdown.inline("See $raw for details.").single { it.citation != null }
            val parsed = Citations.parse(raw).map { it.label }
            assertTrue("$raw -> span '${span.citation}' must be one of the parsed labels $parsed", span.citation in parsed)
            assertEquals("span keeps the original text", raw, span.text)
        }
    }

    @Test fun citationSpanInsideMarkdownListAndBoldStillNormalised() {
        val spans = Markdown.inline("**Rates rose** [p. 7] and fell [pp. 9–10].")
        assertEquals(listOf("p.7", "p.9"), spans.mapNotNull { it.citation })
    }

    /** Labels sent to the model and parsed back must use ASCII digits even where the device locale has its own. */
    @Test fun recordingLabelsUseAsciiDigitsInEveryLocale() {
        val saved = Locale.getDefault()
        try {
            for (locale in listOf(Locale("ar", "EG"), Locale("fa", "IR"), Locale("hi", "IN"), Locale.US)) {
                Locale.setDefault(locale)
                assertEquals("$locale", "18:40", SourceLabels.timestamp(18 * 60_000L + 40_000L))
                assertEquals("$locale", "1:02:03", SourceLabels.timestamp(3_723_000L))
                val label = SourceLabels.recording(1_120_000L)
                assertEquals("$locale", "rec 18:40", label)
                val cited = Citations.parse("As said at [$label].").single()
                assertEquals("$locale", label, cited.label)
                assertEquals("$locale", 1_120_000L, cited.recordingMs)
            }
        } finally {
            Locale.setDefault(saved)
        }
    }
}
