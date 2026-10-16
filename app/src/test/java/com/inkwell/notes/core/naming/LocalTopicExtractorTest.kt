package com.inkwell.notes.core.naming

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalTopicExtractorTest {
    private val extractor = LocalTopicExtractor()

    private val monetary = """
        Lecture 4 - central banking
        Central banks set monetary policy to keep inflation close to target.
        When inflation rises, the central bank raises the policy rate and bond yields usually rise too.
        Long-dated bond yields reflect inflation expectations and term premia.
        Quantitative easing: buying government bonds lowers bond yields and loosens monetary policy.
        Tightening monetary policy (rate hikes) tends to flatten the yield curve.
        Forward guidance shapes expectations about future monetary policy.
        Key takeaway: credibility of monetary policy anchors inflation expectations.
    """.trimIndent()

    private val productMeeting = """
        Meeting notes - Tuesday
        Agenda: product roadmap review and user acquisition update.
        The product roadmap for Q4 focuses on onboarding, referrals and the new pricing page.
        User acquisition costs rose 12% this quarter; paid channels are saturating.
        We agreed the product roadmap must prioritise retention features before new acquisition campaigns.
        Action items: Sarah to share the updated product roadmap; Tom to model user acquisition scenarios.
        Next meeting: review roadmap milestones and acquisition targets.
    """.trimIndent()

    @Test fun monetaryPolicyLectureIsNamedAfterMonetaryPolicy() {
        val topic = extractor.extract(NamingContent.fromPlainText(monetary))
        assertNotNull(topic)
        assertTrue("got $topic", topic!!.contains("Monetary Policy"))
    }

    @Test fun productMeetingIsNamedAfterTheProduct() {
        val topic = extractor.extract(NamingContent.fromPlainText(productMeeting))
        assertNotNull(topic)
        assertTrue("got $topic", topic!!.contains("Product"))
        assertFalse("filler must not win: $topic", topic.contains("Meeting") || topic.contains("Notes"))
    }

    @Test fun tinyTextIsInsufficient() {
        assertNull(extractor.extract(NamingContent.fromPlainText("Buy milk\ncall mum")))
        assertNull(extractor.extract(NamingContent.EMPTY))
    }

    @Test fun textWithoutRepetitionIsInsufficient() {
        val text = "Saw an otter near the harbour while cycling home past bright orange fishing boats, " +
            "quiet cafes, crumbling stone walls, two lighthouses and a lonely bakery selling warm pretzels."
        assertNull(extractor.extract(NamingContent.fromPlainText(text)))
    }

    @Test fun onlyFillerIsInsufficient() {
        val text = "Meeting notes. Action items for the meeting. Notes from today, notes from tomorrow. " +
            "Agenda, agenda, agenda. Follow up next week. Questions and answers. Summary of the meeting notes."
        assertNull(extractor.extract(NamingContent.fromPlainText(text)))
    }

    @Test fun headingsOutweighBodyTerms() {
        val content = NamingContent(
            listOf(
                NamingSegment("Organic Chemistry", SegmentKind.HEADING),
                NamingSegment("Alkenes react with bromine water. Organic chemistry studies carbon compounds.", SegmentKind.LEAD),
                NamingSegment("Reaction mechanisms involve electrophiles and nucleophiles attacking carbon atoms.", SegmentKind.BODY),
                NamingSegment("Carbon forms four bonds; bromine adds across double bonds in alkenes.", SegmentKind.BODY),
            ),
        )
        assertEquals("Organic Chemistry", extractor.extract(content))
    }

    @Test fun subjectOfTheFirstSentenceBeatsAListOfTerms() {
        val text = "Photosynthesis happens in chloroplasts. Light reactions make ATP and NADPH. " +
            "The Calvin cycle uses ATP and NADPH to fix carbon dioxide. Photosynthesis rate depends on light " +
            "intensity, temperature and carbon dioxide concentration. Limiting factors of photosynthesis."
        assertEquals("Photosynthesis", extractor.extract(NamingContent.fromPlainText(text)))
    }

    @Test fun foreignFunctionWordsDoNotBecomeTopics() {
        val text = "Die Zellteilung ist ein wichtiger Prozess. Bei der Mitose entstehen zwei identische Tochterzellen. " +
            "Die Mitose hat vier Phasen: Prophase, Metaphase, Anaphase und Telophase. In der Metaphase ordnen sich " +
            "die Chromosomen an. Die Mitose ist wichtig für Wachstum."
        assertEquals("Mitose", extractor.extract(NamingContent.fromPlainText(text)))
    }

    @Test fun acronymsKeepTheirSpelling() {
        val text = """
            GDP growth slowed in the third quarter as exports weakened.
            Economists revised GDP forecasts after weak retail figures.
            Real GDP per capita stagnated while nominal wages climbed.
            Services output held up better than manufacturing output overall.
        """.trimIndent()
        val topic = extractor.extract(NamingContent.fromPlainText(text))
        assertNotNull(topic)
        assertTrue("got $topic", topic!!.contains("GDP"))
    }

    @Test fun shoutedHeadingsAreTitleCased() {
        val content = NamingContent(
            listOf(
                NamingSegment("PHOTOSYNTHESIS", SegmentKind.HEADING),
                NamingSegment("Photosynthesis converts light energy into chemical energy inside chloroplasts.", SegmentKind.LEAD),
                NamingSegment("Chlorophyll absorbs red and blue light; the Calvin cycle fixes carbon dioxide into sugars.", SegmentKind.BODY),
            ),
        )
        assertEquals("Photosynthesis", extractor.extract(content))
    }

    @Test fun keyTermsSkipPartsOfBetterPhrases() {
        val terms = extractor.keyTerms(NamingContent.fromPlainText(monetary))
        assertEquals("Monetary Policy", terms.first())
        assertFalse(terms.contains("Policy"))
        assertFalse(terms.contains("Monetary"))
        assertTrue(terms.contains("Bond Yields") || terms.contains("Inflation"))
    }

    @Test fun resultIsAtMostFiveWordsInTitleCase() {
        val topic = extractor.extract(NamingContent.fromPlainText(monetary))!!
        val words = topic.split(' ')
        assertTrue(words.size in 1..5)
        assertTrue(words.first().first().isUpperCase())
    }

    @Test fun pluralsAreGroupedWithSingulars() {
        assertEquals("yield", LocalTopicExtractor.stem("yields"))
        assertEquals("policy", LocalTopicExtractor.stem("policies"))
        assertEquals("analysis", LocalTopicExtractor.stem("analysis"))
        assertEquals("class", LocalTopicExtractor.stem("class"))
    }

    @Test fun titleCaseKeepsMinorWordsAndAcronyms() {
        assertEquals("Bank of England", LocalTopicExtractor.titleCase("bank of england"))
        assertEquals("GDP Outlook", LocalTopicExtractor.titleCase("GDP outlook"))
        assertEquals("Monetary Policy", LocalTopicExtractor.titleCase("MONETARY POLICY"))
        assertEquals("iPhone Launch", LocalTopicExtractor.titleCase("iPhone launch"))
    }
}
