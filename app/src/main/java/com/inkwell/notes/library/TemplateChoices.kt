package com.inkwell.notes.library

import com.inkwell.notes.core.model.Orientation
import com.inkwell.notes.core.model.PageSizePreset
import com.inkwell.notes.core.model.PageTemplate
import com.inkwell.notes.core.model.PaperSpec
import com.inkwell.notes.core.model.PaperStyle
import kotlin.math.roundToInt

/** Choices offered by the "Notebook from template" sheet, and conversions it needs. */
object TemplateChoices {
    const val MM_PER_INCH = 25.4f
    const val PT_PER_INCH = 72f
    const val MIN_CUSTOM_MM = 50f
    const val MAX_CUSTOM_MM = 1200f

    val sizes: List<PageSizePreset> = listOf(
        PageSizePreset.A3, PageSizePreset.A4, PageSizePreset.A5,
        PageSizePreset.LETTER, PageSizePreset.LEGAL, PageSizePreset.CUSTOM,
    )

    val styles: List<PaperStyle> = PaperStyle.entries

    /** A paper colour with the line colour that reads well on it. */
    data class PaperColor(val name: String, val paper: Int, val lines: Int)

    val paperColors: List<PaperColor> = listOf(
        PaperColor("White", 0xFFFFFFFF.toInt(), 0xFFD6E0F0.toInt()),
        PaperColor("Ivory", 0xFFFBF7EC.toInt(), 0xFFE2D9C2.toInt()),
        PaperColor("Grey", 0xFFF1F1EE.toInt(), 0xFFD5D5CF.toInt()),
        PaperColor("Charcoal", 0xFF2B2B29.toInt(), 0xFF4A4A46.toInt()),
        PaperColor("Black", 0xFF141414.toInt(), 0xFF34342F.toInt()),
    )

    fun mmToPt(mm: Float): Float = mm / MM_PER_INCH * PT_PER_INCH

    fun ptToMm(pt: Float): Float = pt / PT_PER_INCH * MM_PER_INCH

    /** Rounded millimetres for display in the custom size fields. */
    fun displayMm(pt: Float): String = ptToMm(pt).roundToInt().toString()

    /** Parses a custom size field ("210", "210.5", "210,5"); null when invalid or out of range. */
    fun parseMm(text: String): Float? {
        val v = text.trim().replace(',', '.').toFloatOrNull() ?: return null
        if (!v.isFinite() || v < MIN_CUSTOM_MM || v > MAX_CUSTOM_MM) return null
        return v
    }

    /**
     * Builds the template. For CUSTOM sizes [customWidthMm] / [customHeightMm] must parse;
     * returns null otherwise so the sheet can disable "Create".
     */
    fun build(
        size: PageSizePreset,
        orientation: Orientation,
        style: PaperStyle,
        color: PaperColor,
        customWidthMm: String,
        customHeightMm: String,
        base: PaperSpec = PaperSpec(),
    ): PageTemplate? {
        val paper = base.copy(style = style, color = color.paper, lineColor = color.lines)
        if (size != PageSizePreset.CUSTOM) return PageTemplate(size = size, orientation = orientation, paper = paper)
        val w = parseMm(customWidthMm) ?: return null
        val h = parseMm(customHeightMm) ?: return null
        return PageTemplate(
            size = PageSizePreset.CUSTOM,
            orientation = orientation,
            customWidthPt = mmToPt(w),
            customHeightPt = mmToPt(h),
            paper = paper,
        )
    }

    /** The paper colour entry matching [paper] (by paper ARGB), or the first one. */
    fun colorFor(paper: PaperSpec): PaperColor = paperColors.firstOrNull { it.paper == paper.color } ?: paperColors.first()

    /** Aspect (width / height) of a template's page for previews. */
    fun aspect(template: PageTemplate): Float {
        val w = template.widthPt()
        val h = template.heightPt()
        return if (w > 0f && h > 0f) w / h else 595f / 842f
    }
}
