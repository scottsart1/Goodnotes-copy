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

class SaveSchedulerTest {

    /** Runs all background work (advanceUntilIdle ignores backgroundScope). */
    private fun TestScope.settle() {
        advanceTimeBy(120_000)
        runCurrent()
    }

    private fun TestScope.scheduler(
        saved: MutableList<Pair<String, Int>>,
        delayMs: Long = 400,
        failFirst: Int = 0,
        errors: MutableList<Boolean>? = null,
    ): DebouncedSaveScheduler<String, Int> {
        var failures = failFirst
        return DebouncedSaveScheduler(
            scope = backgroundScope,
            delayMs = delayMs,
            retryDelayMs = 1_000,
            maxRetries = 2,
            save = { k, v ->
                if (failures > 0) { failures--; throw IOException("disk full") }
                saved += k to v
            },
            onError = { _, _, retry -> errors?.add(retry) },
        )
    }

    @Test
    fun `rapid edits of one page are saved once with the latest content`() = runTest(StandardTestDispatcher()) {
        val saved = mutableListOf<Pair<String, Int>>()
        val s = scheduler(saved)
        s.submit("p1", 1)
        advanceTimeBy(200)
        s.submit("p1", 2)
        advanceTimeBy(200)
        s.submit("p1", 3)
        advanceTimeBy(399)
        runCurrent()
        assertTrue(saved.isEmpty())
        advanceTimeBy(2)
        runCurrent()
        assertEquals(listOf("p1" to 3), saved)
        assertFalse(s.hasPending())
    }

    @Test
    fun `pages debounce independently`() = runTest(StandardTestDispatcher()) {
        val saved = mutableListOf<Pair<String, Int>>()
        val s = scheduler(saved)
        s.submit("a", 1)
        advanceTimeBy(300)
        s.submit("b", 1)
        advanceTimeBy(150)
        runCurrent()
        assertEquals(listOf("a" to 1), saved)
        settle()
        assertEquals(listOf("a" to 1, "b" to 1), saved)
    }

    @Test
    fun `flushAll saves immediately and its job completes after the writes`() = runTest(StandardTestDispatcher()) {
        val saved = mutableListOf<Pair<String, Int>>()
        val s = scheduler(saved, delayMs = 10_000)
        s.submit("a", 1)
        s.submit("b", 2)
        val job = s.flushAll()
        runCurrent()
        assertTrue(job.isCompleted)
        assertEquals(setOf("a" to 1, "b" to 2), saved.toSet())
        settle()
        assertEquals(2, saved.size)
    }

    @Test
    fun `latest returns unsaved content until it is written`() = runTest(StandardTestDispatcher()) {
        val saved = mutableListOf<Pair<String, Int>>()
        val s = scheduler(saved)
        assertNull(s.latest("p"))
        s.submit("p", 7)
        assertEquals(7, s.latest("p"))
        assertEquals(setOf("p"), s.pendingKeys())
        settle()
        assertNull(s.latest("p"))
    }

    @Test
    fun `a newer edit made during a slow write is saved after it, never before`() = runTest(StandardTestDispatcher()) {
        val order = mutableListOf<Int>()
        val gate = CompletableDeferred<Unit>()
        val s = DebouncedSaveScheduler<String, Int>(
            scope = backgroundScope,
            delayMs = 100,
            save = { _, v ->
                if (v == 1) gate.await()
                order += v
            },
        )
        s.submit("p", 1)
        advanceTimeBy(101)
        runCurrent() // write of 1 is now blocked
        s.submit("p", 2)
        assertEquals(2, s.latest("p"))
        advanceTimeBy(101)
        runCurrent() // write of 2 waits for 1
        assertTrue(order.isEmpty())
        gate.complete(Unit)
        settle()
        assertEquals(listOf(1, 2), order)
    }

    @Test
    fun `failed saves keep the content and retry`() = runTest(StandardTestDispatcher()) {
        val saved = mutableListOf<Pair<String, Int>>()
        val errors = mutableListOf<Boolean>()
        val s = scheduler(saved, failFirst = 1, errors = errors)
        s.submit("p", 5)
        advanceTimeBy(401)
        runCurrent()
        assertEquals(listOf(true), errors)
        assertEquals(5, s.latest("p"))
        settle()
        assertEquals(listOf("p" to 5), saved)
        assertFalse(s.hasPending())
    }

    @Test
    fun `retries stop after the limit but flush tries again`() = runTest(StandardTestDispatcher()) {
        val saved = mutableListOf<Pair<String, Int>>()
        val errors = mutableListOf<Boolean>()
        val s = scheduler(saved, failFirst = 3, errors = errors)
        s.submit("p", 9)
        settle()
        assertEquals(listOf(true, true, false), errors)
        assertEquals(9, s.latest("p"))
        s.flushAll()
        settle()
        assertEquals(listOf("p" to 9), saved)
    }

    @Test
    fun `discard drops a pending write`() = runTest(StandardTestDispatcher()) {
        val saved = mutableListOf<Pair<String, Int>>()
        val s = scheduler(saved)
        s.submit("p", 1)
        s.discard("p")
        settle()
        assertTrue(saved.isEmpty())
        assertFalse(s.hasPending())
    }
}
