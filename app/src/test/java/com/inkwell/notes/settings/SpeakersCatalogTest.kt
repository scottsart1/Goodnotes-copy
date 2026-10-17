package com.inkwell.notes.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.RecordVoiceOver
import com.inkwell.notes.app.SettingsSection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeakersCatalogTest {
    @Test
    fun voicesAndSpeakersSitsUnderCaptureAfterRecording() {
        val capture = SettingsCatalog.groups.first { it.title == "Capture" }.sections
        assertEquals(capture.indexOf(SettingsSection.RECORDING) + 1, capture.indexOf(SettingsSection.SPEAKERS))
        assertEquals(Icons.Outlined.RecordVoiceOver, SettingsCatalog.icon(SettingsSection.SPEAKERS))
        assertEquals(SettingsCatalog.SPEAKERS_SUMMARY, SettingsCatalog.summary(SettingsSection.SPEAKERS))
        assertTrue(SettingsCatalog.SPEAKERS_SUMMARY.isNotBlank())
    }

    @Test
    fun everySectionIsListedExactlyOnce() {
        val listed = SettingsCatalog.groups.flatMap { it.sections }
        assertEquals(SettingsSection.entries.toSet(), listed.toSet())
        assertEquals(listed.size, listed.toSet().size)
    }
}
