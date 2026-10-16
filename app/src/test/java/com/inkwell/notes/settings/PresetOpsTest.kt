package com.inkwell.notes.settings

import com.inkwell.notes.core.model.PenPreset
import com.inkwell.notes.settings.logic.PresetOps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PresetOpsTest {
    private val defaults = PenPreset.Defaults
    private fun ids(list: List<PenPreset>) = list.map { it.id }

    @Test
    fun cleanName() {
        assertEquals("Lecture blue", PresetOps.cleanName("  Lecture \n  blue "))
        assertNull(PresetOps.cleanName("   "))
        assertEquals(PresetOps.MAX_NAME_CHARS, PresetOps.cleanName("x".repeat(80))!!.length)
    }

    @Test
    fun renameOnlyTouchesTheTarget() {
        val r = PresetOps.rename(defaults, "preset-thick-red", " Margin red ")
        assertEquals("Margin red", r.first { it.id == "preset-thick-red" }.name)
        assertEquals(defaults.filter { it.id != "preset-thick-red" }, r.filter { it.id != "preset-thick-red" })
    }

    @Test
    fun blankRenameIsIgnored() {
        assertSame(defaults, PresetOps.rename(defaults, "preset-thin-black", "  "))
    }

    @Test
    fun deleteAndRestoreAtTheSamePlace() {
        val removed = PresetOps.delete(defaults, "preset-medium-blue")
        assertEquals(defaults.size - 1, removed.size)
        val restored = PresetOps.restore(removed, defaults[1], 1)
        assertEquals(defaults, restored)
        // Restoring twice does not duplicate.
        assertEquals(defaults, PresetOps.restore(restored, defaults[1], 1))
        // Index beyond the end appends.
        assertEquals("preset-medium-blue", PresetOps.restore(removed, defaults[1], 99).last().id)
    }

    @Test
    fun moveUpAndDown() {
        val up = PresetOps.move(defaults, "preset-thick-red", -1)
        assertEquals(listOf("preset-thin-black", "preset-thick-red", "preset-medium-blue", "preset-yellow-hl", "preset-graphite"), ids(up))
        val down = PresetOps.move(defaults, "preset-thin-black", 1)
        assertEquals("preset-medium-blue", down[0].id)
        assertEquals("preset-thin-black", down[1].id)
    }

    @Test
    fun moveIsClampedAndIgnoresUnknownIds() {
        assertEquals(defaults, PresetOps.move(defaults, "preset-thin-black", -1))
        assertEquals("preset-thin-black", PresetOps.move(defaults, "preset-thin-black", 10).last().id)
        assertSame(defaults, PresetOps.move(defaults, "nope", 1))
        assertSame(defaults, PresetOps.moveTo(defaults, 9, 0))
    }

    @Test
    fun resetAndDefaultCheck() {
        assertTrue(PresetOps.isDefault(PresetOps.defaults()))
        assertFalse(PresetOps.isDefault(PresetOps.delete(defaults, "preset-graphite")))
    }

    @Test
    fun describe() {
        assertEquals("Ballpoint · 1 pt", PresetOps.describe(defaults[0]))
        assertEquals("Fountain pen · 1.8 pt", PresetOps.describe(defaults[1]))
        assertEquals("Highlighter · 14 pt", PresetOps.describe(defaults[3]))
        assertEquals("Pencil · 1.2 pt", PresetOps.describe(defaults[4]))
    }
}
