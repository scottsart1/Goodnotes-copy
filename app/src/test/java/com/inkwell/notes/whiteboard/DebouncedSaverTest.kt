package com.inkwell.notes.whiteboard

import com.inkwell.notes.whiteboard.logic.DebouncedSaver
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class DebouncedSaverTest {

    private fun TestScope.saver(writes: MutableList<Pair<String, Int>>, failTimes: Int = 0, errors: MutableList<Boolean>? = null): DebouncedSaver<String, Int> {
        var failures = failTimes
        return DebouncedSaver(
            scope = backgroundScope,
            delayMs = 400,
            save = { k, v ->
                if (failures > 0) { failures--; throw IOException("disk full") }
                writes += k to v
            },
            onResult = { _, e, retry -> if (e != null) errors?.add(retry) },
            retryDelaysMs = listOf(1_000L),
        )
    }

    @Test fun onlyTheLatestValueIsWrittenAfterTheQuietPeriod() = runTest {
        val writes = mutableListOf<Pair<String, Int>>()
        val s = saver(writes)
        s.submit("p", 1)
        advanceTimeBy(300)
        s.submit("p", 2)
        advanceTimeBy(300)
        runCurrent()
        assertTrue(writes.isEmpty())
        assertEquals(2, s.pending("p"))
        advanceTimeBy(150)
        runCurrent()
        assertEquals(listOf("p" to 2), writes)
        assertNull(s.pending("p"))
        assertFalse(s.hasPending)
    }

    @Test fun flushWritesImmediatelyAndOnlyOnce() = runTest {
        val writes = mutableListOf<Pair<String, Int>>()
        val s = saver(writes)
        s.submit("a", 1)
        s.submit("b", 2)
        s.flush()
        assertEquals(setOf("a" to 1, "b" to 2), writes.toSet())
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(2, writes.size)
    }

    @Test fun failedWritesStayPendingAndRetry() = runTest {
        val writes = mutableListOf<Pair<String, Int>>()
        val errors = mutableListOf<Boolean>()
        val s = saver(writes, failTimes = 1, errors = errors)
        s.submit("p", 7)
        advanceTimeBy(401)
        runCurrent()
        assertEquals(listOf(true), errors)
        assertEquals(7, s.pending("p"))
        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(listOf("p" to 7), writes)
        assertFalse(s.hasPending)
    }

    @Test fun newerValueSubmittedDuringFailureIsNotLost() = runTest {
        val writes = mutableListOf<Pair<String, Int>>()
        val s = saver(writes, failTimes = 1)
        s.submit("p", 1)
        advanceTimeBy(401)
        runCurrent()
        s.submit("p", 2)
        s.flush()
        assertEquals(listOf("p" to 2), writes)
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(listOf("p" to 2), writes)
    }
}
