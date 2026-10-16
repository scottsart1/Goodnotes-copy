package com.inkwell.notes.export.sync.ui

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.inkwell.notes.R
import com.inkwell.notes.app.AppGraph
import com.inkwell.notes.core.model.AppSettings
import com.inkwell.notes.core.model.DestinationPrefs
import com.inkwell.notes.core.model.NotesExportFormat
import com.inkwell.notes.core.model.OutputTarget
import com.inkwell.notes.core.settings.SecretStore
import com.inkwell.notes.export.SyncManagerImpl
import com.inkwell.notes.export.SyncStatus
import com.inkwell.notes.export.sync.SafFolderAccess
import com.inkwell.notes.export.sync.SyncKind
import com.inkwell.notes.export.sync.VaultUrl
import com.inkwell.notes.export.sync.targetsFor
import com.inkwell.notes.export.sync.withTargets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Folder chosen for automatic backups (the backup service keeps its own schedule). */
internal data class AutoBackupPref(val uri: String, val label: String)

/** Small per-device UI memory for this screen (no secrets). */
internal class StorageUiPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("inkwell_storage_ui", Context.MODE_PRIVATE)

    fun autoBackup(): AutoBackupPref? {
        val uri = prefs.getString(KEY_URI, null) ?: return null
        return AutoBackupPref(uri, prefs.getString(KEY_LABEL, null).orEmpty())
    }

    fun setAutoBackup(value: AutoBackupPref?) {
        prefs.edit().apply {
            if (value == null) remove(KEY_URI).remove(KEY_LABEL) else putString(KEY_URI, value.uri).putString(KEY_LABEL, value.label)
        }.apply()
    }

    private companion object {
        const val KEY_URI = "autoBackupUri"
        const val KEY_LABEL = "autoBackupLabel"
    }
}

/**
 * State and actions of "Storage, sync & backup". Never holds the vault password after it is
 * saved; [hasPassword] only says whether one exists.
 */
internal class StorageSettingsViewModel(private val graph: AppGraph) : ViewModel() {
    private val context: Context get() = graph.context
    private val impl: SyncManagerImpl? = graph.sync as? SyncManagerImpl
    private val uiPrefs = StorageUiPrefs(graph.context)

    val settings: StateFlow<AppSettings> = graph.settings.settings
    val status: StateFlow<SyncStatus> = graph.sync.status
    val failedCount: StateFlow<Int> = (impl?.observeFailedCount() ?: flowOf(0))
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    val backup: StateFlow<BackupState> = BackupJobs.state

    var hasPassword by mutableStateOf(false)
        private set
    var vaultCheck by mutableStateOf<CheckState>(CheckState.Idle)
        private set
    var folderCheck by mutableStateOf<CheckState>(CheckState.Idle)
        private set
    var syncRequested by mutableStateOf(false)
        private set
    var autoBackup by mutableStateOf<AutoBackupPref?>(null)
        private set
    /** One-off problem to show (picker unavailable, permission refused...). */
    var notice by mutableStateOf<String?>(null)

    private var passwordJob: Job? = null
    private var pendingPassword: String? = null
    private var urlJob: Job? = null
    private var pendingUrl: String? = null

    init {
        viewModelScope.launch {
            val (has, auto) = withContext(Dispatchers.IO) {
                runCatching { graph.secrets.has(SecretStore.VAULT_PASSWORD) }.getOrDefault(false) to runCatching { uiPrefs.autoBackup() }.getOrNull()
            }
            hasPassword = has
            autoBackup = auto
        }
    }

    // ------------------------------------------------------------------ destinations

    fun setTarget(kind: SyncKind, target: OutputTarget, on: Boolean) = updateDestinations { d ->
        d.withTargets(kind, if (on) d.targetsFor(kind) + target else d.targetsFor(kind) - target)
    }

    fun setFormat(format: NotesExportFormat) = updateDestinations { it.copy(notesFormat = format) }
    fun setAutoSync(on: Boolean) = updateDestinations { it.copy(autoSync = on) }
    fun setAudioWifiOnly(on: Boolean) = updateDestinations { it.copy(audioOnUnmeteredOnly = on) }

    private fun updateDestinations(transform: (DestinationPrefs) -> DestinationPrefs) {
        viewModelScope.launch { graph.settings.update { it.copy(destinations = transform(it.destinations)) } }
    }

    // ------------------------------------------------------------------ folder

    /** A tree was picked: keep read/write access, store it, optionally switch it on for [enableFor], test it. */
    fun onFolderPicked(uri: Uri, enableFor: SyncKind?) {
        viewModelScope.launch {
            val label = withContext(Dispatchers.IO) { persistTree(uri) }
            if (label == null) {
                folderCheck = CheckState.Failed(context.getString(R.string.sync_folder_permission_error))
                return@launch
            }
            val previous = settings.value.destinations.folderUri
            graph.settings.update { s ->
                var d = s.destinations.copy(folderUri = uri.toString(), folderLabel = label)
                if (enableFor != null) d = d.withTargets(enableFor, d.targetsFor(enableFor) + OutputTarget.FOLDER)
                s.copy(destinations = d)
            }
            releaseIfUnused(previous, keep = uri.toString())
            testFolder()
        }
    }

    fun testFolder() {
        val uri = settings.value.destinations.folderUri ?: return
        if (folderCheck == CheckState.Running) return
        folderCheck = CheckState.Running
        viewModelScope.launch {
            val r = graph.sync.testFolder(Uri.parse(uri))
            folderCheck = r.fold({ CheckState.Ok(it) }, { CheckState.Failed(it.message ?: context.getString(R.string.sync_folder_not_writable)) })
        }
    }

    /** Takes persistable read/write access; returns a display label or null when refused. */
    private fun persistTree(uri: Uri): String? = try {
        context.contentResolver.takePersistableUriPermission(uri, SafFolderAccess.RW_FLAGS)
        runCatching { DocumentFile.fromTreeUri(context, uri)?.name }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast(':')?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: "Folder"
    } catch (e: SecurityException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    private suspend fun releaseIfUnused(uri: String?, keep: String?) {
        if (uri == null || uri == keep) return
        val stillUsed = uri == settings.value.destinations.folderUri || uri == autoBackup?.uri
        if (stillUsed) return
        withContext(Dispatchers.IO) {
            runCatching { context.contentResolver.releasePersistableUriPermission(Uri.parse(uri), SafFolderAccess.RW_FLAGS) }
        }
    }

    // ------------------------------------------------------------------ vault

    /** Saves a valid link shortly after typing stops; an empty field clears it. */
    fun onUrlChanged(text: String) {
        urlJob?.cancel()
        vaultCheck = CheckState.Idle
        pendingUrl = text
        urlJob = viewModelScope.launch {
            delay(URL_SAVE_DELAY_MS)
            saveUrl(text)
        }
    }

    fun commitUrl(text: String) {
        urlJob?.cancel()
        viewModelScope.launch { saveUrl(text) }
    }

    private suspend fun saveUrl(text: String) {
        pendingUrl = null
        val value = when (val p = VaultUrl.parse(text)) {
            is VaultUrl.Parsed.Valid -> p.endpoint.baseUrl
            VaultUrl.Parsed.Empty -> ""
            is VaultUrl.Parsed.Invalid -> return
        }
        if (value != settings.value.destinations.vaultUrl) {
            graph.settings.update { it.copy(destinations = it.destinations.copy(vaultUrl = value)) }
        }
    }

    /** Saves the password to SecretStore shortly after typing stops. An empty field keeps the saved one. */
    fun onPasswordChanged(text: String) {
        passwordJob?.cancel()
        vaultCheck = CheckState.Idle
        if (text.isEmpty()) {
            pendingPassword = null
            return
        }
        pendingPassword = text
        passwordJob = viewModelScope.launch {
            delay(PASSWORD_SAVE_DELAY_MS)
            flushPassword()
        }
    }

    fun commitPassword() {
        passwordJob?.cancel()
        viewModelScope.launch { flushPassword() }
    }

    private suspend fun flushPassword() {
        val text = pendingPassword ?: return
        pendingPassword = null
        val saved = withContext(Dispatchers.IO) {
            runCatching { graph.secrets.put(SecretStore.VAULT_PASSWORD, text) }.isSuccess
        }
        if (saved) {
            hasPassword = true
            impl?.onVaultCredentialsChanged()
        } else {
            notice = context.getString(R.string.sync_password_store_failed)
        }
    }

    fun forgetPassword() {
        passwordJob?.cancel()
        pendingPassword = null
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    graph.secrets.remove(SecretStore.VAULT_PASSWORD)
                    graph.secrets.remove(SecretStore.VAULT_SESSION)
                }
            }
            hasPassword = false
            vaultCheck = CheckState.Idle
        }
    }

    /** Test connection with what is in the fields (an empty password field uses the saved one). */
    fun testVault(urlText: String, passwordText: String) {
        if (vaultCheck == CheckState.Running) return
        vaultCheck = CheckState.Running
        urlJob?.cancel()
        passwordJob?.cancel()
        viewModelScope.launch {
            saveUrl(urlText)
            flushPassword()
            val r = graph.sync.testVault(urlText, passwordText)
            vaultCheck = r.fold({ CheckState.Ok(it) }, { CheckState.Failed(it.message ?: "The connection test failed.") })
        }
    }

    // ------------------------------------------------------------------ sync

    /** Runs in the app scope so leaving the screen does not cancel it. */
    fun syncNow() {
        if (syncRequested) return
        syncRequested = true
        graph.appScope.launch {
            try {
                graph.sync.syncNow()
            } finally {
                withContext(Dispatchers.Main) { syncRequested = false }
            }
        }
    }

    // ------------------------------------------------------------------ backup

    fun backupTo(uri: Uri) = BackupJobs.backup(graph, uri)
    fun restoreFrom(uri: Uri) = BackupJobs.restore(graph, uri)
    fun dismissBackupResult() = BackupJobs.dismiss()

    fun enableAutoBackup(uri: Uri) {
        viewModelScope.launch {
            val label = withContext(Dispatchers.IO) { persistTree(uri) }
            if (label == null) {
                notice = context.getString(R.string.sync_folder_permission_error)
                return@launch
            }
            val previous = autoBackup?.uri
            val ok = withContext(Dispatchers.IO) {
                runCatching { graph.backup.scheduleAutomatic(uri, AUTO_BACKUP_DAYS) }.isSuccess
            }
            if (!ok) {
                notice = context.getString(R.string.sync_backup_schedule_failed)
                return@launch
            }
            val pref = AutoBackupPref(uri.toString(), label)
            withContext(Dispatchers.IO) { uiPrefs.setAutoBackup(pref) }
            autoBackup = pref
            releaseIfUnused(previous, keep = uri.toString())
        }
    }

    fun disableAutoBackup() {
        viewModelScope.launch {
            val previous = autoBackup?.uri
            withContext(Dispatchers.IO) {
                runCatching { graph.backup.scheduleAutomatic(null) }
                uiPrefs.setAutoBackup(null)
            }
            autoBackup = null
            releaseIfUnused(previous, keep = null)
        }
    }

    override fun onCleared() {
        // Keep what was typed: finish pending saves outside the closing screen.
        pendingUrl?.let { text -> graph.appScope.launch { saveUrl(text) } }
        val password = pendingPassword
        pendingPassword = null
        if (password != null) {
            graph.appScope.launch(Dispatchers.IO) {
                runCatching { graph.secrets.put(SecretStore.VAULT_PASSWORD, password) }
                impl?.onVaultCredentialsChanged()
            }
        }
        super.onCleared()
    }

    private companion object {
        const val URL_SAVE_DELAY_MS = 900L
        const val PASSWORD_SAVE_DELAY_MS = 700L
        const val AUTO_BACKUP_DAYS = 1
    }
}
