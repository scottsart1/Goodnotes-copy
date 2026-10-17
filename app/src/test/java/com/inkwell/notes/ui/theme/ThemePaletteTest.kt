package com.inkwell.notes.ui.theme

import com.inkwell.notes.speakers.logic.SpeakerColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The speaker palette the theme draws must match the palette the speaker logic hands out indices for. */
class ThemePaletteTest {
    @Test
    fun speakerPalettesMatchTheLogicsPaletteSize() {
        // SpeakerColors.next / forRecording pick indices 0 until PALETTE assuming they are all
        // distinct colours; a shorter theme palette would make two voices share a dot.
        assertEquals(SpeakerColors.PALETTE, LightSpeakerPalette.size)
        assertEquals(SpeakerColors.PALETTE, DarkSpeakerPalette.size)
        assertEquals("every voice colour is distinct", LightSpeakerPalette.size, LightSpeakerPalette.toSet().size)
        assertEquals(DarkSpeakerPalette.size, DarkSpeakerPalette.toSet().size)
    }

    @Test
    fun speakerColourLookupWrapsAnyIndex() {
        assertEquals(LightInkwell.speakers[0], LightInkwell.speaker(0))
        assertEquals(LightInkwell.speakers[0], LightInkwell.speaker(SpeakerColors.PALETTE))
        assertEquals(LightInkwell.speakers.last(), LightInkwell.speaker(-1))
        assertEquals(DarkInkwell.speakers[3], DarkInkwell.speaker(3 + 2 * SpeakerColors.PALETTE))
        assertTrue(DarkInkwell.speakers.indices.all { DarkInkwell.speaker(it) == DarkInkwell.speakers[it] })
        // Light and dark palettes are different renderings of the same voices, not the same colours.
        assertNotEquals(LightInkwell.speaker(0), DarkInkwell.speaker(0))
    }
}
