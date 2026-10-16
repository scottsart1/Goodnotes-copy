package com.inkwell.notes.settings

import com.inkwell.notes.app.SettingsSection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsCatalogTest {
    @Test
    fun everySectionIsListedExactlyOnce() {
        val listed = SettingsCatalog.groups.flatMap { it.sections }
        assertEquals(SettingsSection.entries.size, listed.size)
        assertEquals(SettingsSection.entries.toSet(), listed.toSet())
    }

    @Test
    fun everySectionHasASummary() {
        for (s in SettingsSection.entries) assertTrue(s.name, SettingsCatalog.summary(s).isNotBlank())
    }

    @Test
    fun holdToEraseWordingMatchesTheBrief() {
        assertEquals(
            "Hold the side button to erase with your current eraser; release to return to the same pen.",
            com.inkwell.notes.settings.sections.HOLD_TO_ERASE_TEXT,
        )
    }
}
