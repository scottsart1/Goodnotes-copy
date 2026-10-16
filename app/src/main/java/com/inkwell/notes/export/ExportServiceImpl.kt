package com.inkwell.notes.export

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import com.inkwell.notes.app.AppGraph
import com.inkwell.notes.core.model.DocumentMeta
import java.io.File

/** SCAFFOLD STUB — replaced by the export implementation. */
class ExportServiceImpl(private val graph: AppGraph) : ExportService {
    private fun stub(): Nothing = throw NotImplementedError("ExportServiceImpl")
    override suspend fun export(docId: String, options: ExportOptions): File = stub()
    override suspend fun exportTo(docId: String, options: ExportOptions, target: Uri) = stub()
    override suspend fun exportMany(docIds: List<String>, options: ExportOptions, treeUri: Uri): Int = stub()
    override suspend fun exportRecording(recordingId: String): File = stub()
    override fun shareIntent(file: File, mime: String): Intent = Intent(Intent.ACTION_SEND)
    override suspend fun importPackage(uri: Uri, folderId: String?): DocumentMeta = stub()
    override suspend fun renderPage(docId: String, pageIndex: Int, widthPx: Int): Bitmap = stub()
    override suspend fun documentMarkdown(docId: String, includeTranscripts: Boolean): String = ""
}
