package com.inkwell.notes.editor

import com.inkwell.notes.editor.logic.DebouncedSaveScheduler
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.StandardTestDispatcher
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

/**
 * Adversarial sequences for the notebook autosave scheduler: pauses and screen exits while a
 * write is failing, deletes racing writes, and ordering under slow disks.
 */
class SaveSchedulerEdgeTest {

    private fun TestScope.settle() {
        advanceTimeBy(300_000)
        runCurrent()
    }

    @Test
    fun `flushAll during a pending retry writes right away and the job waits for it`() = runTest(StandardTestDispatcher()) {
        val saved = mutableListOf<Int>()
        var failures = 1
        val s = DebouncedSaveScheduler<String, Int>(
            scope = backgroundScope, delayMs = 400, retryDelayMs = 10_000, maxRetries = 3,
            save = { _, v -> if (failures > 0) { failures--; throw IOException("busy") } else saved += v },
        )
        s.submit("p", 1)
        advanceTimeBy(401)
        runCurrent() // first write fails, retry scheduled in 10s
        assertTrue(saved.isEmpty())
        assertEquals(1, s.latest("p"))
        val job = s.flushAll() // the user leaves the screen: do not wait 10s
        runCurrent()
        assertTrue(job.isCompleted)
        assertEquals(listOf(1), saved)
        assertFalse(s.hasPending())
        settle()
        assertEquals(listOf(1), saved) // the cancelled retry never fires a duplicate
    }

    @Test
    fun `an edit after the retry limit was reached starts saving again`() = runTest(StandardTestDispatcher()) {
        val saved = mutableListOf<Int>()
        var fail = true
        val s = DebouncedSaveScheduler<String, Int>(
            scope = backgroundScope, delayMs = 100, retryDelayMs = 100, maxRetries = 1,
            save = { _, v -> if (fail) throw IOException("full") else saved += v },
        )
        s.submit("p", 1)
        settle()
        assertEquals(1, s.latest("p"))
        assertTrue(saved.isEmpty())
        fail = false
        s.submit("p", 2)
        settle()
        assertEquals(listOf(2), saved)
        assertNull(s.latest("p"))
    }

    @Test
    fun `a page edited again after discard saves the new content only`() = runTest(StandardTestDispatcher()) {
        val saved = mutableListOf<Int>()
        val s = DebouncedSaveScheduler<String, Int>(scope = backgroundScope, delayMs = 100, save = { _, v -> saved += v })
        s.submit("p", 1)
        s.discard("p")
        s.submit("p", 2)
        settle()
        assertEquals(listOf(2), saved)
    }

    @Test
    fun `a newer edit submitted while the old write fails is what gets saved`() = runTest(StandardTestDispatcher()) {
        val saved = mutableListOf<Int>()
        val gate = CompletableDeferred<Unit>()
        var failFirst = true
        val s = DebouncedSaveScheduler<String, Int>(
            scope = backgroundScope, delayMs = 100, retryDelayMs = 100, maxRetries = 3,
            save = { _, v ->
                if (failFirst) { failFirst = false; gate.await(); throw IOException("io") }
                saved += v
            },
        )
        s.submit("p", 1)
        advanceTimeBy(101)
        runCurrent() // write of 1 is blocked inside save()
        s.submit("p", 2)
        gate.complete(Unit)
        settle()
        assertEquals(listOf(2), saved)
        assertNull(s.latest("p"))
        assertFalse(s.hasPending())
    }

    @Test
    fun `flushAll waits for a timer-started write that is still running`() = runTest(StandardTestDispatcher()) {
        val saved = mutableListOf<Int>()
        val gate = CompletableDeferred<Unit>()
        val s = DebouncedSaveScheduler<String, Int>(scope = backgroundScope, delayMs = 100, save = { _, v -> gate.await(); saved += v })
        s.submit("p", 1)
        advanceTimeBy(101)
        runCurrent() // write started, blocked on the gate
        val job = s.flushAll()
        runCurrent()
        assertFalse(job.isCompleted)
        assertEquals(1, s.latest("p")) // still not durable
        gate.complete(Unit)
        runCurrent()
        assertTrue(job.isCompleted)
        assertEquals(listOf(1), saved)
        assertNull(s.latest("p"))
    }

    @Test
    fun `pending keys and hasPending reflect in-flight writes`() = runTest(StandardTestDispatcher()) {
        val gate = CompletableDeferred<Unit>()
        val s = DebouncedSaveScheduler<String, Int>(scope = backgroundScope, delayMs = 100, save = { _, _ -> gate.await() })
        s.submit("a", 1)
        s.submit("b", 2)
        advanceTimeBy(101)
        runCurrent()
        assertEquals(setOf("a", "b"), s.pendingKeys())
        assertTrue(s.hasPending())
        gate.complete(Unit)
        runCurrent()
        assertEquals(emptySet<String>(), s.pendingKeys())
        assertFalse(s.hasPending())
    }

    @Test
    fun `many rapid edits across pages are each saved once with their latest content`() = runTest(StandardTestDispatcher()) {
        val saved = mutableListOf<Pair<String, Int>>()
        val s = DebouncedSaveScheduler<String, Int>(scope = backgroundScope, delayMs = 400, save = { k, v -> saved += k to v })
        for (i in 1..50) {
            s.submit("p${i % 3}", i)
            advanceTimeBy(50)
        }
        settle()
        assertEquals(3, saved.size)
        assertEquals(mapOf("p0" to 48, "p1" to 49, "p2" to 50), saved.toMap())
    }
}
