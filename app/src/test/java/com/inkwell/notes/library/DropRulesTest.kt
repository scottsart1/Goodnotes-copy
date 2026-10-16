package com.inkwell.notes.library

import com.inkwell.notes.core.model.LibrarySort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class DropRulesTest {
    // a
    //   b
    //     c
    // d
    private val index = FolderIndex(
        listOf(folder("a"), folder("b", "a"), folder("c", "b"), folder("d")),
        LibrarySorting.folderComparator(LibrarySort.NAME_ASC, Locale.US),
    )
    private val docFolders = mapOf("n1" to "a", "n2" to "c", "n3" to null, "n4" to "d")
    private val folderOf: (String) -> String? = { docFolders[it] }

    private fun plan(items: ItemSet, target: DropTarget) = DropRules.plan(items, target, index, folderOf)

    @Test fun folderCannotGoIntoItself() {
        val p = plan(ItemSet(folderIds = setOf("a")), DropTarget.IntoFolder("a"))
        assertFalse(p.allowed)
        assertEquals(DropPlan.Reason.INTO_ITSELF, p.reason)
    }

    @Test fun folderCannotGoIntoItsDescendants() {
        assertFalse(plan(ItemSet(folderIds = setOf("a")), DropTarget.IntoFolder("b")).allowed)
        assertFalse(plan(ItemSet(folderIds = setOf("a")), DropTarget.IntoFolder("c")).allowed)
        assertFalse(plan(ItemSet(folderIds = setOf("b")), DropTarget.IntoFolder("c")).allowed)
    }

    @Test fun mixedSelectionWithACycleIsRefusedEntirely() {
        val p = plan(ItemSet(documentIds = setOf("n3"), folderIds = setOf("b")), DropTarget.IntoFolder("c"))
        assertFalse(p.allowed)
        assertEquals(DropPlan.Reason.INTO_ITSELF, p.reason)
    }

    @Test fun folderCanMoveIntoASiblingOrUp() {
        val sideways = plan(ItemSet(folderIds = setOf("b")), DropTarget.IntoFolder("d"))
        assertTrue(sideways.allowed)
        assertEquals(listOf("b"), sideways.folderIds)
        val up = plan(ItemSet(folderIds = setOf("c")), DropTarget.IntoFolder("a"))
        assertTrue(up.allowed)
    }

    @Test fun droppingWhereItAlreadyIsIsNotAMove() {
        val p = plan(ItemSet(documentIds = setOf("n1")), DropTarget.IntoFolder("a"))
        assertFalse(p.allowed)
        assertEquals(DropPlan.Reason.NOTHING_TO_MOVE, p.reason)
        assertFalse(plan(ItemSet(folderIds = setOf("b")), DropTarget.IntoFolder("a")).allowed)
    }

    @Test fun onlyItemsThatChangePlaceAreMoved() {
        val p = plan(ItemSet(documentIds = setOf("n1", "n2", "n3")), DropTarget.IntoFolder("a"))
        assertTrue(p.allowed)
        assertEquals(listOf("n2", "n3"), p.documentIds)
    }

    @Test fun nestedSelectionsTravelWithTheirAncestor() {
        val items = ItemSet(documentIds = setOf("n1", "n2", "n3"), folderIds = setOf("a", "c"))
        val n = DropRules.normalize(items, index, folderOf)
        assertEquals(setOf("a"), n.folderIds)
        // n1 is in a, n2 is in c (inside a): both move with a. n3 is at the top level.
        assertEquals(setOf("n3"), n.documentIds)
        val p = plan(items, DropTarget.IntoFolder("d"))
        assertTrue(p.allowed)
        assertEquals(listOf("a"), p.folderIds)
        assertEquals(listOf("n3"), p.documentIds)
    }

    @Test fun rootDropMovesOnlyFiledItems() {
        val p = plan(ItemSet(documentIds = setOf("n1", "n3"), folderIds = setOf("b", "d")), DropTarget.Root)
        assertTrue(p.allowed)
        assertEquals(listOf("n1"), p.documentIds)
        assertEquals(listOf("b"), p.folderIds)
        assertFalse(plan(ItemSet(documentIds = setOf("n3"), folderIds = setOf("a")), DropTarget.Root).allowed)
    }

    @Test fun documentInAMissingFolderAlreadyShowsAtTheTopLevel() {
        val p = DropRules.plan(ItemSet(documentIds = setOf("x")), DropTarget.Root, index) { "deleted-folder" }
        assertFalse(p.allowed)
    }

    @Test fun unknownTargetIsRefused() {
        val p = plan(ItemSet(documentIds = setOf("n3")), DropTarget.IntoFolder("nope"))
        assertFalse(p.allowed)
        assertEquals(DropPlan.Reason.UNKNOWN_TARGET, p.reason)
    }

    @Test fun trashTakesEverythingNormalised() {
        val p = plan(ItemSet(documentIds = setOf("n2", "n4"), folderIds = setOf("b")), DropTarget.Trash)
        assertTrue(p.allowed)
        assertEquals(listOf("b"), p.folderIds)
        assertEquals(listOf("n4"), p.documentIds)
    }

    @Test fun emptyOrUnknownItemsAreNothingToMove() {
        assertFalse(plan(ItemSet(), DropTarget.Trash).allowed)
        assertFalse(plan(ItemSet(folderIds = setOf("ghost")), DropTarget.Root).allowed)
    }

    @Test fun pickerDisablesTheMovedFoldersAndTheirSubtrees() {
        assertEquals(setOf("b", "c"), DropRules.forbiddenTargets(ItemSet(folderIds = setOf("b")), index))
        assertTrue(DropRules.forbiddenTargets(ItemSet(documentIds = setOf("n1")), index).isEmpty())
    }
}
