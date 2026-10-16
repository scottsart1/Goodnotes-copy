package com.inkwell.notes.settings

import com.inkwell.notes.core.model.Orientation
import com.inkwell.notes.core.model.PageSizePreset
import com.inkwell.notes.core.model.PageTemplate
import com.inkwell.notes.core.model.PaperSpec
import com.inkwell.notes.core.model.PaperStyle
import com.inkwell.notes.settings.logic.Licenses
import com.inkwell.notes.settings.logic.PaperMath
import com.inkwell.notes.settings.logic.SettingsFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PaperAndFormatTest {
    @Test
    fun millimetreConversion() {
        assertEquals(595.28f, PaperMath.mmToPt(210f), 0.01f)
        assertEquals(210f, PaperMath.ptToMm(PaperMath.mmToPt(210f)), 0.001f)
        assertEquals("210", PaperMath.formatMm(595f))
    }

    @Test
    fun parseMmValidatesRange() {
        assertEquals(210.5f, PaperMath.parseMm("210,5")!!, 0.0001f)
        assertEquals(297f, PaperMath.parseMm(" 297 ")!!, 0f)
        assertNull(PaperMath.parseMm("20"))
        assertNull(PaperMath.parseMm("5000"))
        assertNull(PaperMath.parseMm("abc"))
        assertNull(PaperMath.parseMm(""))
    }

    @Test
    fun customSizeKeepsOrientationRules() {
        val t = PaperMath.withCustomSize(PageTemplate(orientation = Orientation.LANDSCAPE), 100f, 200f)
        assertEquals(PageSizePreset.CUSTOM, t.size)
        assertTrue(t.widthPt() > t.heightPt())
        assertEquals("200", PaperMath.formatMm(t.widthPt()))
    }

    @Test
    fun sizeLabelUsesOrientation() {
        assertEquals("A4 · 210 × 297 mm", PaperMath.sizeLabel(PageTemplate()))
        assertEquals("A4 · 297 × 210 mm", PaperMath.sizeLabel(PageTemplate(orientation = Orientation.LANDSCAPE)))
    }

    @Test
    fun spacingSnapsToGrid() {
        assertEquals(24f, PaperMath.clampSpacing(23.2f), 0f)
        assertEquals(PaperMath.SPACING_MIN_PT, PaperMath.clampSpacing(2f), 0f)
        assertEquals(PaperMath.SPACING_MAX_PT, PaperMath.clampSpacing(99f), 0f)
        assertEquals(PaperMath.SPACING_MIN_PT, PaperMath.clampSpacing(Float.NaN), 0f)
    }

    @Test
    fun previewSpacingScalesWithPageWidth() {
        val spec = PaperSpec(style = PaperStyle.RULED)
        assertEquals(24f * 200f / 595f, PaperMath.previewSpacingPx(spec, 595f, 200f), 0.001f)
        assertEquals(0f, PaperMath.previewSpacingPx(spec, 0f, 200f), 0f)
    }

    @Test
    fun spacingLabel() {
        assertEquals("24 pt (style default)", PaperMath.spacingLabel(PaperSpec(style = PaperStyle.RULED)))
        assertEquals("30 pt", PaperMath.spacingLabel(PaperSpec(style = PaperStyle.GRID, spacingPt = 30f)))
        assertEquals("No lines", PaperMath.spacingLabel(PaperSpec(style = PaperStyle.BLANK)))
    }

    @Test
    fun paperColours() {
        assertEquals("White", PaperMath.matchColor(PaperSpec())?.name)
        assertNull(PaperMath.matchColor(PaperSpec(color = 0xFF123456.toInt())))
        assertTrue(PaperMath.isDark(PaperMath.paperColors.first { it.name == "Charcoal" }.paper))
        assertFalse(PaperMath.isDark(PaperMath.paperColors.first().paper))
        assertEquals(PaperMath.paperColors.size, PaperMath.paperColors.map { it.paper }.toSet().size)
    }

    @Test
    fun bytes() {
        assertEquals("0 B", SettingsFormat.bytes(0))
        assertEquals("999 B", SettingsFormat.bytes(999))
        assertEquals("1.5 KB", SettingsFormat.bytes(1_500))
        assertEquals("820 KB", SettingsFormat.bytes(820_000))
        assertEquals("1.4 GB", SettingsFormat.bytes(1_400_000_000))
        assertEquals("0 B", SettingsFormat.bytes(-4))
    }

    @Test
    fun audioSizes() {
        assertEquals(29, SettingsFormat.megabytesPerHour(64_000))
        assertEquals(43, SettingsFormat.megabytesPerHour(96_000))
        assertEquals(58, SettingsFormat.megabytesPerHour(128_000))
        assertEquals("96 kbps · about 43 MB per hour", SettingsFormat.audioQualityLabel(96_000))
    }

    @Test
    fun lockDelayLabels() {
        assertEquals("Immediately", SettingsFormat.lockDelayLabel(0))
        assertEquals("After 1 minute", SettingsFormat.lockDelayLabel(60))
        assertEquals("After 15 minutes", SettingsFormat.lockDelayLabel(900))
        assertEquals("1 min", SettingsFormat.minutesLabel(60))
        assertEquals("5 min", SettingsFormat.minutesLabel(300))
        assertEquals("60%", SettingsFormat.percent(0.6f))
    }

    @Test
    fun licencesCoverTheBundledLibraries() {
        val names = Licenses.entries.map { it.name }
        for (n in listOf("AndroidX", "Jetpack Compose", "Kotlin", "OkHttp", "Coil", "PdfBox-Android", "Media3 ExoPlayer", "ML Kit", "Figtree")) {
            assertTrue(n, n in names)
        }
        assertEquals(Licenses.OFL, Licenses.entries.first { it.name == "Figtree" }.license)
        assertEquals(Licenses.GOOGLE_APIS, Licenses.entries.first { it.name == "ML Kit" }.license)
        assertTrue(Licenses.entries.all { it.url.startsWith("https://") })
    }
}
