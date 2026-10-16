package com.inkwell.notes.export.sync

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DebouncerTest {
    private val scope = TestScope(StandardTestDispatcher())
    private val fired = mutableListOf<Pair<String, Long>>()
    private val debouncer = Debouncer(scope, 30_000)

    private fun edit(doc: String) = debouncer.submit(doc) { fired += doc to scope.testScheduler.currentTime }

    @Test
    fun `fires once, 30 seconds after the last edit`() {
        edit("a")
        scope.advanceTimeBy(10_000)
        edit("a")
        scope.advanceTimeBy(20_000)
        edit("a")
        scope.advanceTimeBy(29_999)
        scope.runCurrent()
        assertTrue(fired.isEmpty())
        scope.advanceTimeBy(2)
        scope.runCurrent()
        assertEquals(listOf("a" to 60_000L), fired)
        assertTrue(debouncer.pendingKeys.isEmpty())
    }

    @Test
    fun `documents settle independently`() {
        edit("a")
        scope.advanceTimeBy(5_000)
        edit("b")
        scope.advanceTimeBy(26_000)
        scope.runCurrent()
        assertEquals(listOf("a" to 30_000L), fired)
        scope.advanceTimeBy(5_000)
        scope.runCurrent()
        assertEquals(listOf("a" to 30_000L, "b" to 35_000L), fired)
    }

    @Test
    fun `cancel drops a pending action`() {
        edit("a")
        debouncer.cancel("a")
        scope.advanceTimeBy(60_000)
        scope.runCurrent()
        assertTrue(fired.isEmpty())
    }
}
