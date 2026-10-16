package com.inkwell.notes.core.naming

import com.inkwell.notes.core.model.AiException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class TopicInferenceTest {
    private val inference = TopicInference()

    private val lecture = NamingContent.fromPlainText(
        """
        Lecture 4 - central banking
        Central banks set monetary policy to keep inflation close to target.
        When inflation rises, the central bank raises the policy rate and bond yields usually rise too.
        Quantitative easing lowers bond yields and loosens monetary policy.
        Tightening monetary policy tends to flatten the yield curve.
        Credibility of monetary policy anchors inflation expectations.
        """.trimIndent(),
    )

    private class FakeAi(private val reply: () -> String) : TopicInference.AiNamer {
        var calls = 0
        var lastUser = ""
        var lastSystem = ""
        override suspend fun complete(system: String, user: String): String {
            calls++
            lastSystem = system
            lastUser = user
            return reply()
        }
    }

    @Test fun usesTheModelsTopic() = runTest {
        val ai = FakeAi { "Central Bank Policy" }
        val outcome = inference.infer(lecture, ai, aiFailures = 0, userRequested = false)
        assertEquals(TopicOutcome.Found("Central Bank Policy", TopicOutcome.Source.AI), outcome)
        assertEquals(1, ai.calls)
        assertTrue(ai.lastSystem.contains("INSUFFICIENT"))
        assertTrue(ai.lastUser.length <= NamingPrompt.MAX_CONTENT_CHARS)
        assertTrue(ai.lastUser.contains("Repeated terms: Monetary Policy"))
    }

    @Test fun cleansDecoratedReplies() = runTest {
        val outcome = inference.infer(lecture, FakeAi { "Topic: \"monetary policy.\"\nBecause the notes..." }, 0, false)
        assertEquals(TopicOutcome.Found("Monetary Policy", TopicOutcome.Source.AI), outcome)
        val dated = inference.infer(lecture, FakeAi { "20261015_Monetary Policy" }, 0, false)
        assertEquals(TopicOutcome.Found("Monetary Policy", TopicOutcome.Source.AI), dated)
    }

    @Test fun modelSayingInsufficientIsTrusted() = runTest {
        assertEquals(TopicOutcome.Insufficient, inference.infer(lecture, FakeAi { "INSUFFICIENT" }, 0, false))
        assertEquals(TopicOutcome.Insufficient, inference.infer(lecture, FakeAi { "insufficient." }, 0, false))
    }

    @Test fun genericModelAnswerFallsBackToLocalInference() = runTest {
        val outcome = inference.infer(lecture, FakeAi { "Notes" }, 0, false)
        assertEquals(TopicOutcome.Found("Monetary Policy", TopicOutcome.Source.LOCAL), outcome)
    }

    @Test fun transientFailuresQueueTheDocumentInTheBackground() = runTest {
        val offline = FakeAi { throw IOException("offline") }
        assertEquals(TopicOutcome.RetryLater, inference.infer(lecture, offline, aiFailures = 0, userRequested = false))
        val rateLimited = FakeAi { throw AiException("429", retryable = true) }
        assertEquals(TopicOutcome.RetryLater, inference.infer(lecture, rateLimited, aiFailures = 2, userRequested = false))
    }

    @Test fun afterThreeFailuresLocalInferenceTakesOver() = runTest {
        val ai = FakeAi { throw IOException("offline") }
        val outcome = inference.infer(lecture, ai, aiFailures = AutoNamingRules.MAX_AI_FAILURES, userRequested = false)
        assertEquals(TopicOutcome.Found("Monetary Policy", TopicOutcome.Source.LOCAL), outcome)
        assertEquals(0, ai.calls)
    }

    @Test fun permanentFailuresFallBackImmediately() = runTest {
        val badKey = FakeAi { throw AiException("401 invalid key", retryable = false) }
        val outcome = inference.infer(lecture, badKey, aiFailures = 0, userRequested = false)
        assertEquals(TopicOutcome.Found("Monetary Policy", TopicOutcome.Source.LOCAL), outcome)
    }

    @Test fun userRequestsNeverDefer() = runTest {
        val offline = FakeAi { throw IOException("offline") }
        val outcome = inference.infer(lecture, offline, aiFailures = 0, userRequested = true)
        assertEquals(TopicOutcome.Found("Monetary Policy", TopicOutcome.Source.LOCAL), outcome)
    }

    @Test fun withoutAiTheLocalExtractorIsUsed() = runTest {
        assertEquals(TopicOutcome.Found("Monetary Policy", TopicOutcome.Source.LOCAL), inference.infer(lecture, null, 0, false))
    }

    @Test fun emptyOrTinyContentNeverReachesTheModel() = runTest {
        val ai = FakeAi { "Groceries" }
        assertEquals(TopicOutcome.Insufficient, inference.infer(NamingContent.EMPTY, ai, 0, false))
        assertEquals(TopicOutcome.Insufficient, inference.infer(NamingContent.fromPlainText("hi"), ai, 0, false))
        assertEquals(0, ai.calls)
    }

    @Test fun shortButMeaningfulContentCanStillBeNamedByTheModel() = runTest {
        val note = NamingContent.fromPlainText("Dentist appointment Tuesday, bring insurance card")
        val ai = FakeAi { "Dentist Appointment" }
        assertEquals(TopicOutcome.Found("Dentist Appointment", TopicOutcome.Source.AI), inference.infer(note, ai, 0, false))
        // Locally the same note is too short to name.
        assertEquals(TopicOutcome.Insufficient, inference.infer(note, null, 0, false))
    }
}
