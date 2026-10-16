package com.inkwell.notes.core.naming

import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class NamingStateStoreTest {
    private val dir: File = Files.createTempDirectory("naming-state").toFile()
    private val file = File(dir, "naming/state.json")

    @After fun tearDown() {
        dir.deleteRecursively()
    }

    @Test fun updatesPersistAcrossInstances() = runTest {
        val store = NamingStateStore(file)
        store.update("a") { it.copy(lastCheckAt = 100, aiFailures = 1) }
        store.update("a") { it.copy(aiFailures = it.aiFailures + 1) }
        store.update("b") { NamingDocState(lastCheckAt = 5) }
        assertEquals(NamingDocState(lastCheckAt = 100, aiFailures = 2), NamingStateStore(file).get("a"))
        assertEquals(setOf("a", "b"), NamingStateStore(file).all().keys)
    }

    @Test fun clearingAndPruningRemoveEntries() = runTest {
        val store = NamingStateStore(file)
        store.update("a") { NamingDocState(lastCheckAt = 1) }
        store.update("b") { NamingDocState(lastCheckAt = 2) }
        store.update("c") { NamingDocState(lastCheckAt = 3) }
        store.update("a") { null }
        store.retainOnly(setOf("b"))
        assertEquals(setOf("b"), store.all().keys)
        assertEquals(NamingDocState(), store.get("missing"))
    }

    @Test fun corruptFileMeansNeverChecked() = runTest {
        file.parentFile!!.mkdirs()
        file.writeText("{not json")
        val store = NamingStateStore(file)
        assertTrue(store.all().isEmpty())
        store.update("a") { NamingDocState(lastCheckAt = 9) }
        assertEquals(9L, NamingStateStore(file).get("a").lastCheckAt)
    }
}
