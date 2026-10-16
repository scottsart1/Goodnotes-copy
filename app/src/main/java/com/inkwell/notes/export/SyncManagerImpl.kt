package com.inkwell.notes.export

import android.net.Uri
import com.inkwell.notes.app.AppGraph
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** SCAFFOLD STUB — replaced by the sync implementation. */
class SyncManagerImpl(private val graph: AppGraph) : SyncManager {
    override val status: StateFlow<SyncStatus> = MutableStateFlow(SyncStatus())
    override fun onDocumentChanged(docId: String) {}
    override fun onTranscriptReady(docId: String, recordingId: String) {}
    override fun onRecordingFinished(docId: String, recordingId: String) {}
    override fun onDocumentDeleted(docId: String) {}
    override suspend fun syncNow(): SyncStatus = status.value
    override suspend fun testVault(url: String, password: String): Result<String> = Result.failure(NotImplementedError())
    override suspend fun testFolder(treeUri: Uri): Result<String> = Result.failure(NotImplementedError())
}
