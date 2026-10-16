package com.inkwell.notes.whiteboard

import com.inkwell.notes.whiteboard.logic.DebouncedSaver
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** Whiteboard and typed-document autosave under failures, slow disks and screen exits. */
class DebouncedSaverEdgeTest {

    @Test fun flushAfterExhaustedRetriesWritesAgain() = runTest(StandardTestDispatcher()) {
        val writes = mutableListOf<Int>()
        var fail = true
        val results = mutableListOf<Boolean?>()
        val s = DebouncedSaver<String, Int>(
            scope = backgroundScope, delayMs = 100,
            save = { _, v -> if (fail) throw IOException("io") else writes += v },
            onResult = { _, e, retry -> results += if (e == null) null else retry },
            retryDelaysMs = listOf(100L),
        )
        s.submit("p", 1)
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(listOf(true, false), results) // one retry, then gave up
        assertEquals(1, s.pending("p"))
        fail = false
        s.flush()
        assertEquals(listOf(1), writes)
        assertFalse(s.hasPending)
    }

    @Test fun flushOfOneKeyLeavesOtherKeysOnTheirTimers() = runTest(StandardTestDispatcher()) {
        val writes = mutableListOf<Pair<String, Int>>()
        val s = DebouncedSaver<String, Int>(scope = backgroundScope, delayMs = 400, save = { k, v -> writes += k to v })
        s.submit("a", 1)
        s.submit("b", 2)
        s.flush("a")
        assertEquals(listOf("a" to 1), writes)
        assertEquals(2, s.pending("b"))
        advanceTimeBy(401)
        runCurrent()
        assertEquals(listOf("a" to 1, "b" to 2), writes)
    }

    @Test fun valueSubmittedDuringASlowWriteIsNotMarkedSavedAndIsWrittenAfter() = runTest(StandardTestDispatcher()) {
        val writes = mutableListOf<Int>()
        val gate = CompletableDeferred<Unit>()
        val s = DebouncedSaver<String, Int>(scope = backgroundScope, delayMs = 100, save = { _, v -> if (v == 1) gate.await(); writes += v })
        s.submit("p", 1)
        advanceTimeBy(101)
        runCurrent() // write of 1 blocked
        s.submit("p", 2)
        assertEquals(2, s.pending("p"))
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf(1), writes)
        assertEquals(2, s.pending("p")) // the newer value is still owed to disk
        advanceTimeBy(101)
        runCurrent()
        assertEquals(listOf(1, 2), writes)
        assertNull(s.pending("p"))
    }

    @Test fun flushAsyncFromAScreenExitCompletesTheWrite() = runTest(StandardTestDispatcher()) {
        val writes = mutableListOf<Int>()
        val s = DebouncedSaver<String, Int>(scope = backgroundScope, delayMs = 10_000, save = { _, v -> writes += v })
        s.submit("p", 7)
        val job = s.flushAsync()
        runCurrent()
        assertTrue(job.isCompleted)
        assertEquals(listOf(7), writes)
    }

    @Test fun concurrentFlushAndTimerWriteTheValueOnce() = runTest(StandardTestDispatcher()) {
        val writes = mutableListOf<Int>()
        val gate = CompletableDeferred<Unit>()
        val s = DebouncedSaver<String, Int>(scope = backgroundScope, delayMs = 100, save = { _, v -> gate.await(); writes += v })
        s.submit("p", 3)
        advanceTimeBy(101)
        runCurrent() // timer write in progress
        val flush = backgroundScope.launch { s.flush() }
        runCurrent()
        gate.complete(Unit)
        advanceTimeBy(1)
        runCurrent()
        assertTrue(flush.isCompleted)
        assertEquals(listOf(3), writes)
        assertFalse(s.hasPending)
    }
}
