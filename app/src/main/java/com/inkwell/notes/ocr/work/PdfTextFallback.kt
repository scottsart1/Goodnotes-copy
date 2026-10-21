package com.inkwell.notes.ocr.work

/** What the indexer does with a PDF page whose text layer could not be read for now. */
internal object PdfTextFallback {
    /**
     * True to OCR the page now (and mark it indexed), false to keep its earlier PDF text and OCR
     * rows and try the text layer again later. Waiting only makes sense when there are earlier
     * rows to keep ([indexedBefore]) and the failure may pass; a page that was never indexed, or
     * a file whose text extraction keeps running out of memory ([givenUp]), would otherwise stay
     * unsearchable and be retried on every run.
     */
    fun ocrInstead(indexedBefore: Boolean, givenUp: Boolean): Boolean = !indexedBefore || givenUp
}
