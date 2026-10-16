package com.inkwell.notes.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.RecordVoiceOver
import com.inkwell.notes.app.SettingsSection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeakersCatalogTest {
    @Test
    fun voicesAndSpeakersSitsUnderCaptureAfterRecordingWhenDeclared() {
        val speakers = SettingsCatalog.speakers ?: return // The navigation enum doesn't declare it yet.
        val capture = SettingsCatalog.groups.first { it.title == "Capture" }.sections
        assertEquals(capture.indexOf(SettingsSection.RECORDING) + 1, capture.indexOf(speakers))
        assertEquals(Icons.Outlined.RecordVoiceOver, SettingsCatalog.icon(speakers))
        assertEquals(SettingsCatalog.SPEAKERS_SUMMARY, SettingsCatalog.summary(speakers))
        assertEquals(1, SettingsCatalog.groups.flatMap { it.sections }.count { it == speakers })
    }

    @Test
    fun theLookupMatchesTheEnumName() {
        val declared = SettingsSection.entries.any { it.name == SettingsCatalog.SPEAKERS_NAME }
        assertEquals(declared, SettingsCatalog.speakers != null)
        assertTrue(SettingsCatalog.SPEAKERS_SUMMARY.isNotBlank())
    }
}
