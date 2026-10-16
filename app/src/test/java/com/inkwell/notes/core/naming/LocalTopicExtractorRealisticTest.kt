package com.inkwell.notes.core.naming

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Realistic note text (handwriting recognition noise, lists, headings, dates, other languages). */
class LocalTopicExtractorRealisticTest {
    private val extractor = LocalTopicExtractor()

    private fun topic(text: String, headings: List<String> = emptyList()): String? {
        val t = extractor.extract(NamingContent.fromPlainText(text, headings))
        println("TOPIC[" + text.lineSequence().first().take(40) + "] = " + t)
        return t
    }

    private fun words(t: String) = t.split(' ').filter { it.isNotEmpty() }

    @Test fun handwrittenLectureWithRecognitionNoise() {
        val t = topic(
            """
            Thermodynamics  - 1st law
            Energy is conserved . heat Q , work W
            dU = Q - W   (sign conventlon!)
            internal energy U is a state functlon
            Thermodynamics 2nd law : entropy S increases
            heat engines : efficiency = W / Q_in
            Carnot engine : max efficiency between two reservoirs
            entropy of universe never decreases -> thermodynamics arrow of time
            """.trimIndent(),
        )
        assertTrue("got $t", t != null && t.contains("Thermodynamics"))
    }

    @Test fun shortListsAreInsufficientAndLongerOnesNeverYieldFiller() {
        assertNull(topic("milk\neggs\nbread\ncall dentist\npay rent"))
        val t = topic("milk\neggs\nbread\nbutter\ncall dentist\nbook car service\npay rent\nmilk again\nbread\nchicken")
        if (t != null) assertTrue("got $t", words(t).none { it.lowercase() in setOf("call", "again", "pay", "book") })
    }

    @Test fun datesNumbersAndWeekdaysNeverBecomeTheTopic() {
        val t = topic(
            """
            Monday 3 March 2026
            2026 budget: 2026 targets vs 2025 actuals
            Tuesday 4 March 2026 - same again 2026
            Wednesday 5 March 2026, 2026, 2026
            10:30 am call, 11:00 am call, 3 pm review
            """.trimIndent(),
        )
        if (t != null) {
            assertTrue("got $t", words(t).none { w -> w.all { it.isDigit() } })
            assertTrue("got $t", words(t).none { it.lowercase() in setOf("monday", "tuesday", "wednesday", "march") })
        }
    }

    @Test fun meetingNotesAreNamedAfterTheSubjectNotTheMeeting() {
        val t = topic(
            """
            Weekly sync with Anna and Tom
            Agenda: hiring plan for Q3, office move
            Hiring plan: two backend engineers, one designer. Hiring plan budget approved.
            Tom worried the hiring plan slips if the office move happens in July.
            Action items: Anna drafts hiring plan timeline, Tom asks facilities about the office move.
            Next week: review hiring plan again.
            """.trimIndent(),
        )
        assertTrue("got $t", t != null && t.contains("Hiring"))
        assertTrue("got $t", !t!!.contains("Weekly") && !t.contains("Sync") && !t.contains("Agenda") && !t.contains("Anna") && !t.contains("Tom"))
    }

    @Test fun headingLineWithWeekNumberYieldsTheSubject() {
        val t = topic(
            """
            Week 3 - Linear Algebra
            Vectors, matrices and linear maps. A matrix represents a linear map between vector spaces.
            Matrix multiplication is composition of linear maps.
            Determinant of a matrix: zero means the linear map is not invertible.
            Eigenvalues and eigenvectors of a matrix.
            """.trimIndent(),
            headings = listOf("Week 3 - Linear Algebra"),
        )
        assertTrue("got $t", t != null && t.contains("Linear Algebra"))
    }

    @Test fun germanHistoryNotes() {
        val t = topic(
            """
            Die Französische Revolution 1789
            Die Französische Revolution begann mit dem Sturm auf die Bastille.
            Ursachen der Revolution: Finanzkrise, Ständegesellschaft, Aufklärung.
            Die Nationalversammlung erklärte die Menschenrechte.
            Die Französische Revolution veränderte Europa.
            """.trimIndent(),
        )
        assertTrue("got $t", t != null && t.contains("Revolution"))
        assertTrue("got $t", words(t!!).none { it.lowercase() in setOf("die", "der", "dem", "mit", "auf") })
    }

    @Test fun urlsAndEmailsDoNotBecomeTopics() {
        val t = topic(
            """
            Sources for the marketing campaign
            https://example.com/campaign/brief https://example.com/campaign/brief
            contact: anna@example.com, tom@example.com, anna@example.com
            marketing campaign launch in May; marketing campaign budget 40k
            marketing campaign channels: search, social, email newsletter
            """.trimIndent(),
        )
        assertTrue("got $t", t != null && t.contains("Marketing Campaign"))
        assertTrue("got $t", !t!!.contains("example") && !t.contains("https") && !t.contains("com"))
    }

    @Test fun resultIsNeverLongerThanFiveWordsOrFortyEightCharacters() {
        val t = topic(
            """
            Bank of England monetary policy committee decision
            The Bank of England monetary policy committee decision surprised markets.
            Bank of England monetary policy committee decision: rates held.
            Analysts expected the Bank of England monetary policy committee decision to be a cut.
            """.trimIndent(),
        )
        assertTrue("got $t", t != null && words(t!!).size <= 5 && t.length <= DateNaming.MAX_TOPIC_CHARS)
        assertTrue("got $t", t!!.none { it == '/' || it == ':' || it == '"' })
    }

    @Test fun firstPersonJournalIsNotNamedAfterPronounsOrFiller() {
        val t = topic(
            """
            Today I went for a long walk and thought about things.
            I think I should really try to get more sleep. I really do.
            Then I called mum and we talked about the garden for a bit.
            Tomorrow I will try to go to bed earlier, really.
            """.trimIndent(),
        )
        if (t != null) assertTrue("got $t", words(t).none { it.lowercase() in setOf("today", "tomorrow", "really", "things", "think", "went") })
    }

    @Test fun ocrOfAPrintedSyllabusKeepsCourseName() {
        val t = topic(
            """
            CS 229 Machine Learning - Syllabus
            Machine learning course covering supervised learning, unsupervised learning and reinforcement learning.
            Week 1: introduction to machine learning. Week 2: linear regression. Week 3: logistic regression.
            Grading: problem sets 40%, project 40%, final 20%.
            Textbook: Pattern Recognition and Machine Learning.
            """.trimIndent(),
        )
        assertTrue("got $t", t != null && t.contains("Machine Learning"))
    }
}
