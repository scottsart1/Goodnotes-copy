package com.inkwell.notes.export

import android.graphics.Bitmap
import com.inkwell.notes.app.AppGraph

/** SCAFFOLD STUB — replaced by the export implementation. */
class ThumbnailServiceImpl(private val graph: AppGraph) : ThumbnailService {
    override fun refresh(docId: String) {}
    override suspend fun pageThumbnail(docId: String, pageId: String, widthPx: Int): Bitmap? = null
}
