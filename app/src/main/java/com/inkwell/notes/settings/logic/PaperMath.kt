package com.inkwell.notes.settings.logic

import com.inkwell.notes.core.model.Orientation
import com.inkwell.notes.core.model.PageSizePreset
import com.inkwell.notes.core.model.PageTemplate
import com.inkwell.notes.core.model.PaperSpec
import com.inkwell.notes.core.model.PaperStyle
import java.util.Locale
import kotlin.math.roundToInt

/** A paper colour offered for new notebooks, with a line colour that reads well on it. */
data class PaperColorOption(val name: String, val paper: Int, val lines: Int)

/** Page size, spacing and paper colour helpers for the "Paper & templates" section. */
object PaperMath {
    private const val MM_PER_INCH = 25.4f
    private const val PT_PER_INCH = 72f

    const val MIN_CUSTOM_MM = 50f
    const val MAX_CUSTOM_MM = 1200f

    const val SPACING_MIN_PT = 12f
    const val SPACING_MAX_PT = 48f
    const val SPACING_STEP_PT = 2f

    val sizes: List<PageSizePreset> = listOf(
        PageSizePreset.A4, PageSizePreset.A5, PageSizePreset.A3,
        PageSizePreset.LETTER, PageSizePreset.LEGAL, PageSizePreset.CUSTOM,
    )

    val styles: List<PaperStyle> = PaperStyle.entries

    /** Paper colours are document content (not UI chrome), so they are fixed ARGB values. */
    val paperColors: List<PaperColorOption> = listOf(
        PaperColorOption("White", 0xFFFFFFFF.toInt(), 0xFFD6E0F0.toInt()),
        PaperColorOption("Ivory", 0xFFFBF7EE.toInt(), 0xFFE2DACA.toInt()),
        PaperColorOption("Pale yellow", 0xFFFFF8D9.toInt(), 0xFFE9DEAE.toInt()),
        PaperColorOption("Mist", 0xFFF0F4FA.toInt(), 0xFFC9D6EA.toInt()),
        PaperColorOption("Sage", 0xFFEEF5EF.toInt(), 0xFFC6DBCA.toInt()),
        PaperColorOption("Charcoal", 0xFF262624.toInt(), 0xFF45433E.toInt()),
    )

    fun mmToPt(mm: Float): Float = mm / MM_PER_INCH * PT_PER_INCH

    fun ptToMm(pt: Float): Float = pt / PT_PER_INCH * MM_PER_INCH

    /** Parses a millimetre value ("210", "210.5", "210,5"); null when invalid or out of range. */
    fun parseMm(text: String): Float? {
        val v = text.trim().replace(',', '.').toFloatOrNull() ?: return null
        if (v.isNaN() || v < MIN_CUSTOM_MM || v > MAX_CUSTOM_MM) return null
        return v
    }

    fun formatMm(pt: Float): String = ptToMm(pt).roundToInt().toString()

    fun styleLabel(style: PaperStyle): String = when (style) {
        PaperStyle.BLANK -> "Blank"
        PaperStyle.RULED -> "Ruled"
        PaperStyle.NARROW_RULED -> "Narrow ruled"
        PaperStyle.WIDE_RULED -> "Wide ruled"
        PaperStyle.GRID -> "Grid"
        PaperStyle.DOT_GRID -> "Dots"
        PaperStyle.GRAPH -> "Graph"
    }

    /** "A4 · 210 × 297 mm" for the current template (orientation applied). */
    fun sizeLabel(t: PageTemplate): String {
        val w = ptToMm(t.widthPt()).roundToInt()
        val h = ptToMm(t.heightPt()).roundToInt()
        return "${t.size.label} · $w × $h mm"
    }

    fun orientationLabel(o: Orientation): String = if (o == Orientation.PORTRAIT) "Portrait" else "Landscape"

    /** Snaps a slider value to the spacing grid. */
    fun clampSpacing(pt: Float): Float {
        if (pt.isNaN()) return SPACING_MIN_PT
        val c = pt.coerceIn(SPACING_MIN_PT, SPACING_MAX_PT)
        return ((c / SPACING_STEP_PT).roundToInt() * SPACING_STEP_PT).coerceIn(SPACING_MIN_PT, SPACING_MAX_PT)
    }

    fun spacingLabel(spec: PaperSpec): String {
        val s = spec.effectiveSpacing()
        return if (spec.style == PaperStyle.BLANK) "No lines"
        else String.format(Locale.US, "%d pt%s", s.roundToInt(), if (spec.spacingPt <= 0f) " (style default)" else "")
    }

    /** Line spacing in preview pixels for a page [pageWidthPt] wide drawn [previewWidthPx] wide. */
    fun previewSpacingPx(spec: PaperSpec, pageWidthPt: Float, previewWidthPx: Float): Float {
        if (pageWidthPt <= 0f || previewWidthPx <= 0f) return 0f
        return spec.effectiveSpacing() * (previewWidthPx / pageWidthPt)
    }

    fun matchColor(spec: PaperSpec): PaperColorOption? = paperColors.firstOrNull { it.paper == spec.color }

    /** True when the paper is dark enough to need light ink/lines in previews. */
    fun isDark(argb: Int): Boolean {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return (0.2126 * r + 0.7152 * g + 0.0722 * b) < 110.0
    }

    /** Applies a custom size in millimetres; the template keeps its orientation. */
    fun withCustomSize(t: PageTemplate, widthMm: Float, heightMm: Float): PageTemplate =
        t.copy(size = PageSizePreset.CUSTOM, customWidthPt = mmToPt(widthMm), customHeightPt = mmToPt(heightMm))

    /** Custom size in mm to show in the fields (falls back to A4 when unset). */
    fun customMm(t: PageTemplate): Pair<String, String> {
        val w = if (t.customWidthPt > 0f) t.customWidthPt else PageSizePreset.A4.widthPt
        val h = if (t.customHeightPt > 0f) t.customHeightPt else PageSizePreset.A4.heightPt
        return formatMm(w) to formatMm(h)
    }
}
