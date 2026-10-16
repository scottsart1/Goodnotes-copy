package com.inkwell.notes.library

import com.inkwell.notes.core.model.Orientation
import com.inkwell.notes.core.model.PageSizePreset
import com.inkwell.notes.core.model.PaperStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TemplateAndSelectionTest {

    @Test fun millimetresAndPoints() {
        assertEquals(595.3f, TemplateChoices.mmToPt(210f), 0.1f)
        assertEquals(210f, TemplateChoices.ptToMm(TemplateChoices.mmToPt(210f)), 0.001f)
        assertEquals("297", TemplateChoices.displayMm(841.9f))
    }

    @Test fun customSizeParsing() {
        assertEquals(210f, TemplateChoices.parseMm(" 210 "))
        assertEquals(210.5f, TemplateChoices.parseMm("210,5"))
        assertNull(TemplateChoices.parseMm(""))
        assertNull(TemplateChoices.parseMm("abc"))
        assertNull(TemplateChoices.parseMm("10"))
        assertNull(TemplateChoices.parseMm("5000"))
    }

    @Test fun buildsPresetAndCustomTemplates() {
        val ivory = TemplateChoices.paperColors[1]
        val a5 = TemplateChoices.build(PageSizePreset.A5, Orientation.LANDSCAPE, PaperStyle.DOT_GRID, ivory, "", "")
        assertNotNull(a5)
        assertEquals(PageSizePreset.A5, a5!!.size)
        assertEquals(595f, a5.widthPt())
        assertEquals(420f, a5.heightPt())
        assertEquals(PaperStyle.DOT_GRID, a5.paper.style)
        assertEquals(ivory.paper, a5.paper.color)
        assertEquals(ivory.lines, a5.paper.lineColor)

        val custom = TemplateChoices.build(PageSizePreset.CUSTOM, Orientation.PORTRAIT, PaperStyle.GRID, ivory, "300", "200")
        assertNotNull(custom)
        assertEquals(TemplateChoices.mmToPt(200f), custom!!.widthPt(), 0.01f)
        assertEquals(TemplateChoices.mmToPt(300f), custom.heightPt(), 0.01f)
        assertNull(TemplateChoices.build(PageSizePreset.CUSTOM, Orientation.PORTRAIT, PaperStyle.GRID, ivory, "x", "200"))
        assertEquals(2f / 3f, TemplateChoices.aspect(custom), 0.001f)
    }

    @Test fun paperColourLookupFallsBackToWhite() {
        val ivory = TemplateChoices.paperColors[1]
        assertEquals(ivory, TemplateChoices.colorFor(com.inkwell.notes.core.model.PaperSpec(color = ivory.paper)))
        assertEquals(TemplateChoices.paperColors.first(), TemplateChoices.colorFor(com.inkwell.notes.core.model.PaperSpec(color = 0x12345678)))
    }

    @Test fun selectionToggleAndPrune() {
        val a = ItemKey.Doc("a")
        val f = ItemKey.Fold("f")
        var s = Selection.None
        assertFalse(s.active)
        s = s.toggle(a).toggle(f)
        assertTrue(s.active)
        assertEquals(2, s.size)
        assertEquals(setOf("a"), s.documentIds)
        assertEquals(setOf("f"), s.folderIds)
        assertTrue(s.hasFolders && s.hasDocuments)
        assertEquals(ItemSet(setOf("a"), setOf("f")), s.asItems())
        s = s.toggle(a)
        assertEquals(setOf(f), s.keys)
        assertEquals(Selection.None, s.retainOnly(setOf(a)))
        val same = Selection(setOf(a))
        assertTrue(same.retainOnly(setOf(a, f)) === same)
    }

    @Test fun bulkTogglesSetUnlessAllHaveIt() {
        val fav = doc("1").copy(favorite = true)
        val plain = doc("2")
        assertTrue(Selection.bulkTarget(listOf(fav, plain)) { it.favorite })
        assertFalse(Selection.bulkTarget(listOf(fav)) { it.favorite })
        assertTrue(Selection.bulkTarget(emptyList()) { it.favorite })
    }

    @Test fun packageNames() {
        assertTrue(PackageNames.looksLikePackage("20261015_Markets.inkwell"))
        assertTrue(PackageNames.looksLikePackage("Backup.ZIP "))
        assertFalse(PackageNames.looksLikePackage("notes.pdf"))
        assertFalse(PackageNames.looksLikePackage("inkwell"))
    }

    @Test fun folderStyleIsComplete() {
        assertEquals(24, FolderStyle.iconNames.size)
        assertEquals(24, FolderStyle.iconNames.toSet().size)
        assertEquals(12, FolderStyle.swatches.size)
        assertEquals(FolderStyle.swatches.size, FolderStyle.swatchNames.size)
        assertEquals(0, FolderStyle.swatches.first())
        assertFalse(FolderStyle.hasCustomIcon("folder"))
        assertFalse(FolderStyle.hasCustomIcon("unknown_icon"))
        assertTrue(FolderStyle.hasCustomIcon("school"))
        assertEquals("Menu book", FolderStyle.iconLabel("menu_book"))
    }
}
