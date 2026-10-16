package com.inkwell.notes.export

import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.inkwell.notes.app.AppGraph
import com.inkwell.notes.core.model.DestinationPrefs
import com.inkwell.notes.core.model.OutputTarget
import com.inkwell.notes.core.settings.SecretStore
import com.inkwell.notes.export.sync.AndroidNetworkState
import com.inkwell.notes.export.sync.Debouncer
import com.inkwell.notes.export.sync.DocumentFolderNode
import com.inkwell.notes.export.sync.FolderRevokedException
import com.inkwell.notes.export.sync.FolderWriter
import com.inkwell.notes.export.sync.GraphSyncSource
import com.inkwell.notes.export.sync.PrefsSyncStateStore
import com.inkwell.notes.export.sync.SafFolderAccess
import com.inkwell.notes.export.sync.SecretVaultCredentials
import com.inkwell.notes.export.sync.SyncEngine
import com.inkwell.notes.export.sync.SyncKind
import com.inkwell.notes.export.sync.SyncRunReport
import com.inkwell.notes.export.sync.SyncScheduler
import com.inkwell.notes.export.sync.VaultClient
import com.inkwell.notes.export.sync.VaultConnectionTest
import com.inkwell.notes.export.sync.VaultException
import com.inkwell.notes.export.sync.SyncCheckFailure
import com.inkwell.notes.export.sync.VaultUrl
import com.inkwell.notes.export.sync.hasAnyTarget
import com.inkwell.notes.export.sync.includesMarkdown
import com.inkwell.notes.export.sync.includesPdf
import com.inkwell.notes.export.sync.syncNeeds
import com.inkwell.notes.export.sync.targetsFor
import com.inkwell.notes.export.sync.withoutFolder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Copies notes, transcripts and audio to the destinations chosen in Settings (a SAF folder and/or
 * the yourpitbox.com notes vault). This device always keeps the original; nothing here deletes
 * local data, and deleting a document never deletes its copies (sync is not backup).
 *
 * Work is queued in `sync_queue` and processed by [SyncEngine], in the background through
 * WorkManager ([com.inkwell.notes.export.sync.SyncWorker]) or right away by [syncNow].
 */
class SyncManagerImpl(private val graph: AppGraph) : SyncManager {
    private val _status = MutableStateFlow(SyncStatus())
    override val status: StateFlow<SyncStatus> = _status.asStateFlow()

    private val stateStore by lazy { PrefsSyncStateStore(graph.context) }
    private val scheduler by lazy { SyncScheduler(graph.context) }
    private val debouncer = Debouncer(graph.appScope, EDIT_SETTLE_MS)

    internal val engine: SyncEngine by lazy {
        SyncEngine(
            queue = { graph.db.syncQueue() },
            source = GraphSyncSource(graph),
            prefs = { graph.settings.current.destinations },
            vaultClient = { endpoint -> VaultClient(graph.http, endpoint, SecretVaultCredentials(graph.secrets, endpoint)) },
            folders = SafFolderAccess(graph.context),
            network = AndroidNetworkState(graph.context),
            state = stateStore,
            onFolderRevoked = { clearFolderDestination() },
        )
    }

    init {
        graph.appScope.launch(Dispatchers.IO) { start() }
    }

    private suspend fun start() {
        try {
            refreshStatus()
            graph.appScope.launch { engine.running.collect { r -> _status.update { it.copy(running = r) } } }
            graph.appScope.launch(Dispatchers.IO) {
                graph.db.syncQueue().observePendingCount().collect { n -> _status.update { it.copy(pending = n) } }
            }
            graph.appScope.launch(Dispatchers.IO) { watchDestinations() }
            // Catch up on edits whose debounce was cut short by the process ending.
            delay(STARTUP_DELAY_MS)
            val prefs = graph.settings.current.destinations
            if (prefs.autoSync && prefs.hasAnyTarget) {
                engine.enqueueChangedDocuments(prefs)
                kick()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Sync start-up failed: ${e.javaClass.simpleName}")
        }
    }

    // ------------------------------------------------------------------ SyncManager

    override fun onDocumentChanged(docId: String) {
        val prefs = graph.settings.current.destinations
        if (!prefs.autoSync || prefs.notes.isEmpty()) return
        debouncer.submit(docId) {
            withContext(Dispatchers.IO) {
                val now = graph.settings.current.destinations
                if (now.autoSync && now.notes.isNotEmpty()) {
                    safely { engine.enqueue(docId, SyncKind.NOTES, now.notes) }
                    kick()
                }
            }
        }
    }

    override fun onTranscriptReady(docId: String, recordingId: String) = enqueueRecording(docId, recordingId, SyncKind.TRANSCRIPT)

    override fun onRecordingFinished(docId: String, recordingId: String) = enqueueRecording(docId, recordingId, SyncKind.AUDIO)

    private fun enqueueRecording(docId: String, recordingId: String, kind: SyncKind) {
        graph.appScope.launch(Dispatchers.IO) {
            val prefs = graph.settings.current.destinations
            val targets = prefs.targetsFor(kind)
            if (targets.isEmpty()) return@launch
            safely { engine.enqueue(docId, kind, targets, recordingId) }
            // With automatic sync off the item waits for "Sync now".
            if (prefs.autoSync) kick()
        }
    }

    override fun onDocumentDeleted(docId: String) {
        debouncer.cancel(docId)
        graph.appScope.launch(Dispatchers.IO) {
            // Copies in the folder and the vault stay: deleting locally never deletes them.
            safely { graph.db.syncQueue().dropOpenForDocument(docId) }
            safely { stateStore.setFolderLocation(docId, null) }
        }
    }

    override suspend fun syncNow(): SyncStatus = withContext(Dispatchers.IO) {
        val prefs = graph.settings.current.destinations
        val dao = graph.db.syncQueue()
        safely {
            dao.retryFailed()
            if (dao.pendingCount() == 0) engine.enqueueEverything(prefs) else engine.enqueueChangedDocuments(prefs)
        }
        val report = try {
            engine.run()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Sync now failed: ${e.javaClass.simpleName}")
            stateStore.lastError = "Sync stopped unexpectedly. Inkwell will try again."
            SyncRunReport(retryLater = true)
        }
        afterRun(report)
        // Anything still waiting (offline, Wi-Fi only, retry) continues in the background.
        kick()
        val pending = runCatching { dao.pendingCount() }.getOrDefault(_status.value.pending)
        _status.update { it.copy(pending = pending) }
        _status.value
    }

    override suspend fun testVault(url: String, password: String): Result<String> = withContext(Dispatchers.IO) {
        val endpoint = when (val parsed = VaultUrl.parse(url)) {
            is VaultUrl.Parsed.Valid -> parsed.endpoint
            VaultUrl.Parsed.Empty -> return@withContext Result.failure(SyncCheckFailure("Enter the vault link first."))
            is VaultUrl.Parsed.Invalid -> return@withContext Result.failure(SyncCheckFailure(parsed.reason))
        }
        val stored = runCatching { graph.secrets.get(SecretStore.VAULT_PASSWORD) }.getOrNull()
        val password = password.ifEmpty { stored.orEmpty() }
        val isSavedVault = VaultUrl.endpointOrNull(graph.settings.current.destinations.vaultUrl) == endpoint && password == stored
        VaultConnectionTest.run(graph.http, endpoint, password).fold(
            onSuccess = { ok ->
                if (isSavedVault) {
                    // The saved settings work: reuse this session and release anything that waited.
                    safely { SecretVaultCredentials(graph.secrets, endpoint).saveSession(ok.session) }
                    stateStore.vaultLockedUntil = 0
                    if (stateStore.lastError != null) stateStore.lastError = null
                    refreshStatus()
                    kick()
                }
                Result.success(ok.message)
            },
            onFailure = { e ->
                val locked = e.cause as? VaultException.Locked
                if (locked != null && isSavedVault) stateStore.vaultLockedUntil = System.currentTimeMillis() + locked.retryAfterSeconds * 1000
                Result.failure(e)
            },
        )
    }

    override suspend fun testFolder(treeUri: Uri): Result<String> = withContext(Dispatchers.IO) {
        try {
            val doc = DocumentFile.fromTreeUri(graph.context, treeUri)
                ?: return@withContext Result.failure(SyncCheckFailure("Inkwell can't open this folder."))
            FolderWriter.probe(DocumentFolderNode(graph.context, doc), System.currentTimeMillis())
            val name = runCatching { doc.name }.getOrNull()?.takeIf { it.isNotBlank() }
            Result.success(if (name != null) "Inkwell can write to $name." else "Inkwell can write to this folder.")
        } catch (e: FolderRevokedException) {
            Result.failure(SyncCheckFailure("Inkwell doesn't have permission to write here. Choose the folder again.", e))
        } catch (e: SecurityException) {
            Result.failure(SyncCheckFailure("Inkwell doesn't have permission to write here. Choose the folder again.", e))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(SyncCheckFailure(e.message?.takeIf { it.isNotBlank() } ?: "Inkwell can't write to this folder.", e))
        }
    }

    // ------------------------------------------------------------------ app-internal API (settings, worker)

    /** Items that gave up after repeated failures ("Sync now" retries them). */
    fun observeFailedCount(): Flow<Int> = runCatching { graph.db.syncQueue().observeFailedCount() }.getOrDefault(emptyFlow())

    /** The vault password or link changed: drop the cached session and retry what waited. */
    fun onVaultCredentialsChanged() {
        graph.appScope.launch(Dispatchers.IO) {
            safely { graph.secrets.put(SecretStore.VAULT_SESSION, null) }
            stateStore.vaultLockedUntil = 0
            stateStore.lastError = null
            refreshStatus()
            if (graph.settings.current.destinations.autoSync) kick()
        }
    }

    /** One background pass (called by the worker). */
    internal suspend fun runQueue(): SyncRunReport {
        val report = engine.run()
        afterRun(report)
        return report
    }

    /** Schedules background runs for whatever is waiting in the queue. */
    fun kick() {
        graph.appScope.launch(Dispatchers.IO) {
            safely {
                val items = graph.db.syncQueue().pending(QUEUE_SCAN_LIMIT)
                val needs = syncNeeds(items, graph.settings.current.destinations)
                if (needs.any) scheduler.schedule(needs)
            }
        }
    }

    private fun afterRun(report: SyncRunReport) {
        refreshStatus()
        val now = System.currentTimeMillis()
        if (report.vaultLockedUntil > now) scheduler.scheduleAfter(report.vaultLockedUntil - now + 1_000)
    }

    private fun refreshStatus() {
        val lastSuccess = runCatching { stateStore.lastSuccessAt }.getOrDefault(0)
        val lastError = runCatching { stateStore.lastError }.getOrNull()
        _status.update { it.copy(lastSuccessAt = lastSuccess, lastError = lastError) }
    }

    private suspend fun clearFolderDestination() {
        graph.settings.update { it.copy(destinations = it.destinations.withoutFolder()) }
    }

    // ------------------------------------------------------------------ destination changes

    private suspend fun watchDestinations() {
        var previous = graph.settings.current.destinations
        graph.settings.settings.map { it.destinations }.distinctUntilChanged().debounce(SETTINGS_SETTLE_MS).collect { next ->
            if (next != previous) {
                val old = previous
                previous = next
                safely { onDestinationsChanged(old, next) }
            }
        }
    }

    private suspend fun onDestinationsChanged(old: DestinationPrefs, new: DestinationPrefs) {
        val dao = graph.db.syncQueue()
        var enqueued = 0
        for (kind in SyncKind.entries) {
            val before = old.targetsFor(kind)
            val after = new.targetsFor(kind)
            for (removed in before - after) dao.dropOpenFor(kind.name, removed.name)
            for (added in after - before) enqueued += engine.enqueueEverything(new, onlyKind = kind, onlyTarget = added)
        }
        if (new.folderUri != old.folderUri) {
            stateStore.clearAllLocations()
            if (old.folderUri != null && new.folderUri != null) enqueued += engine.enqueueEverything(new, onlyTarget = OutputTarget.FOLDER)
        }
        val oldVault = VaultUrl.normalize(old.vaultUrl)
        val newVault = VaultUrl.normalize(new.vaultUrl)
        if (newVault != oldVault) {
            stateStore.vaultLockedUntil = 0
            if (newVault != null) enqueued += engine.enqueueEverything(new, onlyTarget = OutputTarget.VAULT)
        }
        val newFormats = (new.notesFormat.includesPdf && !old.notesFormat.includesPdf) ||
            (new.notesFormat.includesMarkdown && !old.notesFormat.includesMarkdown)
        if (newFormats) enqueued += engine.enqueueEverything(new, onlyKind = SyncKind.NOTES)
        if (new.autoSync && !old.autoSync) enqueued += engine.enqueueChangedDocuments(new)
        val conditionsChanged = enqueued > 0 || new.autoSync != old.autoSync ||
            new.audioOnUnmeteredOnly != old.audioOnUnmeteredOnly || newVault != oldVault || new.folderUri != old.folderUri
        if (new.autoSync && conditionsChanged) kick()
    }

    private suspend inline fun safely(block: () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Sync bookkeeping failed: ${e.javaClass.simpleName}")
        }
    }

    private companion object {
        const val TAG = "InkwellSync"
        /** Notes are copied this long after the last edit of a document. */
        const val EDIT_SETTLE_MS = 30_000L
        const val SETTINGS_SETTLE_MS = 1_200L
        const val STARTUP_DELAY_MS = 15_000L
        const val QUEUE_SCAN_LIMIT = 5000
    }
}
