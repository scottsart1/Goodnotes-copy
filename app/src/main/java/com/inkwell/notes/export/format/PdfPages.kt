package com.inkwell.notes.export.format

import com.inkwell.notes.core.model.PageMeta
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle

/** Creating the pages of an exported PDF and the transform from page points to their user space. */
internal object PdfPages {
    /**
     * Copies [src] (a page of the source PDF) into [target] unchanged, so its text stays
     * selectable, with the document page's rotation added to the original /Rotate. Inherited
     * resources are copied onto the page. Returns the page and the page-point transform.
     */
    fun importOriginal(target: PDDocument, src: PDPage, page: PageMeta): Pair<PDPage, Affine> {
        val imported = target.importPage(src)
        if (!imported.cosObject.containsKey(COSName.RESOURCES)) src.resources?.let { imported.resources = it }
        val box = imported.cropBox
        val r0 = src.rotation
        val toUser = PdfGeometry.displayToUser(box.lowerLeftX, box.lowerLeftY, box.upperRightX, box.upperRightY, r0, page.widthPt, page.heightPt)
        imported.rotation = PdfGeometry.combinedRotation(r0, page.rotation)
        return imported to toUser
    }

    /** A new page of the document page's size and rotation. */
    fun newPage(target: PDDocument, page: PageMeta): Pair<PDPage, Affine> {
        val w = page.widthPt.coerceAtLeast(1f)
        val h = page.heightPt.coerceAtLeast(1f)
        val p = PDPage(PDRectangle(w, h))
        target.addPage(p)
        p.rotation = PdfGeometry.normalizeRotation(page.rotation)
        return p to PdfGeometry.pageToUser(w, h)
    }
}
