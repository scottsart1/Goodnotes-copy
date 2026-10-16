package com.inkwell.notes.pdf

import com.inkwell.notes.core.model.OutlineEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage: when extract/split/merge copy an outline, entries are inserted one by one and the
 * library drops a parent reference whose parent does not exist yet. Parents must therefore be
 * inserted before their children whatever the stored sort order says.
 */
class OutlineCopyOrderTest {
    private fun e(id: String, parent: String?, sort: Int) = OutlineEntry(id, "doc", id, "page-$id", parent, sort)

    @Test
    fun parentsComeBeforeTheirChildren() {
        val entries = listOf(e("c", "b", 0), e("b", "a", 1), e("a", null, 2), e("d", "a", 3))
        val ordered = DocumentAssembler.parentsFirst(entries)
        assertEquals(entries.toSet(), ordered.toSet())
        val position = ordered.withIndex().associate { it.value.id to it.index }
        for (x in ordered) {
            val p = x.parentId ?: continue
            assertTrue("${x.id} before its parent $p", position.getValue(p) < position.getValue(x.id))
        }
    }

    @Test
    fun alreadyOrderedEntriesKeepTheirOrderAndMissingParentsOrCyclesDoNotLoop() {
        val sorted = listOf(e("a", null, 0), e("b", "a", 1), e("c", "a", 2), e("d", "c", 3))
        assertEquals(sorted, DocumentAssembler.parentsFirst(sorted))
        val orphan = listOf(e("x", "gone", 0), e("y", null, 1))
        assertEquals(orphan, DocumentAssembler.parentsFirst(orphan))
        val cycle = listOf(e("p", "q", 0), e("q", "p", 1))
        assertEquals(cycle.toSet(), DocumentAssembler.parentsFirst(cycle).toSet())
        assertTrue(DocumentAssembler.parentsFirst(emptyList()).isEmpty())
    }
}
