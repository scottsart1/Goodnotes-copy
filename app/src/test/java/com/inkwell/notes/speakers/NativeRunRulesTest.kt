package com.inkwell.notes.speakers

import com.inkwell.notes.speakers.logic.NativeRunRules
import com.inkwell.notes.speakers.logic.NativeRunRules.Decision
import com.inkwell.notes.speakers.logic.NativeRuns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A recording whose analysis keeps killing the app must stop being retried. */
class NativeRunRulesTest {
    private fun proceed(d: Decision): NativeRuns {
        assertTrue("expected to proceed: $d", d is Decision.Proceed)
        return d.next
    }

    @Test
    fun aRunThatComesBackLeavesNoTrace() {
        val started = proceed(NativeRunRules.start(NativeRuns(), "r1"))
        assertEquals(true, started.entries["r1"]?.inFlight)
        assertEquals(NativeRuns(), NativeRunRules.finish(started, "r1", completed = true))
    }

    @Test
    fun twoDeathsInsideNativeCodeStopTheRecording() {
        var s = proceed(NativeRunRules.start(NativeRuns(), "r1"))
        // The process died: nothing called finish. The next start counts one crash.
        s = proceed(NativeRunRules.start(s, "r1"))
        assertEquals(1, s.entries["r1"]?.crashes)
        val stop = NativeRunRules.start(s, "r1")
        assertTrue(stop is Decision.Stop)
        assertEquals(false, stop.next.entries["r1"]?.inFlight)
        // Stays stopped on later starts until the user tries again.
        assertTrue(NativeRunRules.start(stop.next, "r1") is Decision.Stop)
        assertTrue(NativeRunRules.start(NativeRunRules.reset(stop.next, "r1"), "r1") is Decision.Proceed)
    }

    @Test
    fun aJavaFailureIsNotACrash() {
        var s = proceed(NativeRunRules.start(NativeRuns(), "r1"))
        s = NativeRunRules.finish(s, "r1", completed = false)
        repeat(5) {
            s = proceed(NativeRunRules.start(s, "r1"))
            s = NativeRunRules.finish(s, "r1", completed = false)
        }
        assertEquals(0, s.entries["r1"]?.crashes)
    }

    @Test
    fun recordingsAreIndependentAndTheRecordSurvivesEncoding() {
        var s = proceed(NativeRunRules.start(NativeRuns(), "a"))
        s = proceed(NativeRunRules.start(s, "a"))
        s = proceed(NativeRunRules.start(s, "b"))
        val back = NativeRuns.decode(NativeRuns.encode(s))
        assertEquals(s, back)
        assertEquals(1, back.entries["a"]?.crashes)
        assertEquals(0, back.entries["b"]?.crashes)
        assertEquals(NativeRuns(), NativeRuns.decode("{not json"))
    }
}
