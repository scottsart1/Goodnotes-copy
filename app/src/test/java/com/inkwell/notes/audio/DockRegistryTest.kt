package com.inkwell.notes.audio

import com.inkwell.notes.audio.ui.dock.DockRegistry
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DockRegistryTest {
    private val a = Any()
    private val b = Any()

    @After
    fun tearDown() {
        DockRegistry.detach(a)
        DockRegistry.detach(b)
    }

    @Test
    fun recordingDocumentsEditorWins() {
        DockRegistry.attach(a, "notes")
        DockRegistry.attach(b, "lecture")
        assertTrue(DockRegistry.shows(b, "lecture", recordingDocId = "lecture"))
        assertFalse(DockRegistry.shows(a, "notes", recordingDocId = "lecture"))
    }

    @Test
    fun otherwiseOnlyTheFirstDockShows() {
        DockRegistry.attach(a, "notes")
        DockRegistry.attach(b, "slides")
        assertTrue(DockRegistry.shows(a, "notes", recordingDocId = "lecture"))
        assertFalse(DockRegistry.shows(b, "slides", recordingDocId = "lecture"))
        assertTrue(DockRegistry.isPrimary(a))
        assertFalse(DockRegistry.isPrimary(b))
    }

    @Test
    fun leavingHandsOverToTheRemainingDock() {
        DockRegistry.attach(a, "notes")
        DockRegistry.attach(b, "slides")
        DockRegistry.detach(a)
        assertTrue(DockRegistry.shows(b, "slides", recordingDocId = "lecture"))
        assertTrue(DockRegistry.isPrimary(b))
    }

    @Test
    fun singleDockAlwaysShows() {
        DockRegistry.attach(a, "notes")
        assertTrue(DockRegistry.shows(a, "notes", recordingDocId = "notes"))
        assertTrue(DockRegistry.shows(a, "notes", recordingDocId = "elsewhere"))
    }
}
