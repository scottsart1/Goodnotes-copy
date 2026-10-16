package com.inkwell.notes.export

import android.net.Uri
import com.inkwell.notes.app.AppGraph

/** SCAFFOLD STUB — replaced by the export implementation. */
class BackupServiceImpl(private val graph: AppGraph) : BackupService {
    override suspend fun backupTo(treeOrFileUri: Uri, includeAudio: Boolean): BackupReport = BackupReport(0, 0, null)
    override suspend fun restoreFrom(uri: Uri): Int = 0
    override fun scheduleAutomatic(treeUri: Uri?, intervalDays: Int) {}
}
