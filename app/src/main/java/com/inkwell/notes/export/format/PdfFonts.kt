package com.inkwell.notes.export.format

import android.content.Context
import com.inkwell.notes.R
import com.tom_roush.fontbox.ttf.CmapLookup
import com.tom_roush.fontbox.ttf.TTFParser
import com.tom_roush.fontbox.ttf.TrueTypeFont
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import java.io.Closeable
import java.io.File

/** Copies the bundled Figtree fonts to the cache once so PDFBox can read them as files. */
internal object FontFiles {
    private val lock = Any()

    fun regular(context: Context): File = copy(context, R.font.figtree_regular, "figtree_regular.ttf")
    fun bold(context: Context): File = copy(context, R.font.figtree_bold, "figtree_bold.ttf")

    private fun copy(context: Context, res: Int, name: String): File = synchronized(lock) {
        val dir = File(context.cacheDir, "export-fonts").apply { mkdirs() }
        val target = File(dir, name)
        // Written via a temp file and rename, so an existing target is always complete.
        if (target.isFile && target.length() > 0) return target
        val tmp = File(dir, "$name.tmp")
        context.resources.openRawResource(res).use { input -> tmp.outputStream().use { input.copyTo(it) } }
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
        target
    }
}

/**
 * Figtree regular and bold embedded as subset Type0 (Identity-H) fonts: Unicode text that
 * stays selectable and searchable. Glyph coverage is checked per code point so callers can
 * draw characters Figtree lacks some other way.
 */
internal class PdfFonts(private val doc: PDDocument, private val regularFile: File, private val boldFile: File) : Closeable {
    private var regularTtf: TrueTypeFont? = null
    private var boldTtf: TrueTypeFont? = null
    private var regularFont: PDType0Font? = null
    private var boldFont: PDType0Font? = null
    private var regularCmap: CmapLookup? = null
    private var boldCmap: CmapLookup? = null

    fun font(bold: Boolean): PDType0Font {
        if (bold) {
            boldFont?.let { return it }
            val ttf = TTFParser().parse(boldFile).also { boldTtf = it }
            boldCmap = ttf.getUnicodeCmapLookup(false)
            return PDType0Font.load(doc, ttf, true).also { boldFont = it }
        }
        regularFont?.let { return it }
        val ttf = TTFParser().parse(regularFile).also { regularTtf = it }
        regularCmap = ttf.getUnicodeCmapLookup(false)
        return PDType0Font.load(doc, ttf, true).also { regularFont = it }
    }

    /** True when [codePoint] has a glyph in the regular (or bold) face. */
    fun covers(codePoint: Int, bold: Boolean): Boolean {
        font(bold)
        val cmap = (if (bold) boldCmap else regularCmap) ?: return false
        if (codePoint == ' '.code) return true
        return cmap.getGlyphId(codePoint) > 0
    }

    /** Width of [text] (fully covered) at [size] points. */
    fun width(text: String, size: Float, bold: Boolean): Float = font(bold).getStringWidth(text) / 1000f * size

    /** Closes the parsed font files; call after the document was saved. */
    override fun close() {
        runCatching { regularTtf?.close() }
        runCatching { boldTtf?.close() }
    }
}
