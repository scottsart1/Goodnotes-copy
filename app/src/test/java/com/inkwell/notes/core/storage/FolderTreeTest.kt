package com.inkwell.notes.core.storage

import com.inkwell.notes.core.model.Folder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderTreeTest {
    private fun f(id: String, parent: String?, name: String = id) = Folder(id, name, parent, createdAt = 0, modifiedAt = 0)

    private val tree = FolderTree(
        listOf(
            f("econ", null, "Economics"),
            f("t1", "econ", "Term 1"),
            f("wk1", "t1", "Week 1"),
            f("t2", "econ", "Term 2"),
            f("art", null, "Art"),
        ),
    )

    @Test fun subtreeIncludesAllDescendants() {
        assertEquals(setOf("econ", "t1", "wk1", "t2"), tree.subtree("econ"))
        assertEquals(setOf("t1", "wk1"), tree.subtree("t1"))
        assertEquals(setOf("art"), tree.subtree("art"))
    }

    @Test fun movesThatCreateCyclesAreDetected() {
        assertTrue(tree.wouldCreateCycle("econ", "wk1"))
        assertTrue(tree.wouldCreateCycle("econ", "econ"))
        assertFalse(tree.wouldCreateCycle("wk1", "art"))
        assertFalse(tree.wouldCreateCycle("t1", null))
    }

    @Test fun pathsReadFromTheRoot() {
        assertEquals("Economics / Term 1 / Week 1", tree.path("wk1"))
        assertEquals("Art", tree.path("art"))
        assertEquals("", tree.path(null))
        assertEquals("", tree.path("missing"))
    }

    @Test fun corruptCyclesAndMissingParentsDoNotHang() {
        val broken = FolderTree(listOf(f("a", "b"), f("b", "a"), f("lost", "gone", "Lost")))
        assertEquals(setOf("a", "b"), broken.subtree("a"))
        assertEquals(2, broken.path("a").split(" / ").size)
        assertEquals("Lost", broken.path("lost"))
        assertEquals(listOf("lost"), broken.childrenOf(null).map { it.id })
    }
}
