package com.inkwell.notes.export.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import com.inkwell.notes.app.AppGraph
import com.inkwell.notes.core.model.DocumentMeta
import com.inkwell.notes.core.model.Recording
import com.inkwell.notes.core.settings.SecretStore
import com.inkwell.notes.export.ExportFormat
import com.inkwell.notes.export.ExportOptions
import java.io.File

/** [SyncSource] over the app graph: library, export service and the recordings tables. */
internal class GraphSyncSource(private val graph: AppGraph) : SyncSource {
    override suspend fun document(docId: String): DocumentMeta? = graph.library.getDocument(docId)

    override suspend fun allDocuments(): List<DocumentMeta> = graph.db.documents().getAll().map { it.toModel() }

    override suspend fun folderPath(folderId: String?): String = if (folderId == null) "" else graph.library.folderPath(folderId)

    override suspend fun folderNames(folderId: String?): List<String> {
        val names = ArrayList<String>()
        val seen = HashSet<String>()
        var id = folderId
        while (id != null && seen.add(id) && names.size < MAX_DEPTH) {
            val folder = graph.db.folders().get(id) ?: break
            names += folder.name
            id = folder.parentId
        }
        return names.asReversed()
    }

    override suspend fun markdown(docId: String): String = graph.export.documentMarkdown(docId, includeTranscripts = false)

    override suspend fun exportPdf(docId: String): File = graph.export.export(docId, ExportOptions(ExportFormat.PDF))

    override suspend fun exportAudio(recordingId: String): File = graph.export.exportRecording(recordingId)

    override fun isTemporary(file: File): Boolean = runCatching {
        // Only ever delete exports in the cache, never an original (a single-segment recording
        // may be handed back as-is from the document folder).
        val path = file.canonicalPath
        val cache = graph.files.cacheDir.canonicalPath + File.separator
        val docs = graph.files.root.canonicalPath + File.separator
        path.startsWith(cache) && !path.startsWith(docs)
    }.getOrDefault(false)

    override suspend fun recording(recordingId: String): Recording? = graph.db.recordings().get(recordingId)?.toModel()

    override suspend fun recordings(docId: String): List<Recording> = graph.db.recordings().forDocument(docId).map { it.toModel() }

    override suspend fun transcript(recordingId: String): StoredTranscript? {
        val rows = graph.db.transcripts().forRecording(recordingId)
        if (rows.isEmpty()) return null
        val source = rows.firstOrNull { it.source.isNotBlank() }?.source ?: "unknown"
        return StoredTranscript(rows.map { it.toModel() }, source)
    }

    override suspend fun markSynced(docId: String, at: Long) {
        try {
            // Only this column: writing the whole document back could undo a concurrent edit
            // (title, folder, tags...) or bring back a document deleted meanwhile.
            graph.db.documents().setLastSyncedAt(docId, at)
        } catch (e: Exception) {
            Log.w("InkwellSync", "Couldn't record the sync time: ${e.javaClass.simpleName}")
        }
    }

    private companion object {
        const val MAX_DEPTH = 32
    }
}

/** Sync bookkeeping in a private preferences file (no secrets, no note content). */
internal class PrefsSyncStateStore(context: Context) : SyncStateStore {
    private val prefs = context.getSharedPreferences("inkwell_sync", Context.MODE_PRIVATE)
    private val locations = context.getSharedPreferences("inkwell_sync_locations", Context.MODE_PRIVATE)

    override var lastSuccessAt: Long
        get() = prefs.getLong(KEY_LAST_SUCCESS, 0)
        set(value) = prefs.edit().putLong(KEY_LAST_SUCCESS, value).apply()

    override var lastError: String?
        get() = prefs.getString(KEY_LAST_ERROR, null)
        set(value) = prefs.edit().apply { if (value == null) remove(KEY_LAST_ERROR) else putString(KEY_LAST_ERROR, value) }.apply()

    override var vaultLockedUntil: Long
        get() = prefs.getLong(KEY_LOCKED_UNTIL, 0)
        set(value) = prefs.edit().putLong(KEY_LOCKED_UNTIL, value).apply()

    override var vaultCredentialsRefusedAt: Long
        get() = prefs.getLong(KEY_REFUSED_AT, 0)
        set(value) = prefs.edit().putLong(KEY_REFUSED_AT, value).apply()

    override fun folderLocation(docId: String): List<String>? = decode(locations.getString(docId, null))

    override fun folderLocations(): Map<String, List<String>> =
        locations.all.mapNotNull { (docId, value) -> decode(value as? String)?.let { docId to it } }.toMap()

    private fun decode(stored: String?): List<String>? =
        stored?.split(SEP)?.filter { it.isNotEmpty() }?.takeIf { it.isNotEmpty() }

    override fun setFolderLocation(docId: String, segments: List<String>?) {
        locations.edit().apply { if (segments == null) remove(docId) else putString(docId, segments.joinToString(SEP)) }.apply()
    }

    fun clearAllLocations() = locations.edit().clear().apply()

    private companion object {
        const val KEY_LAST_SUCCESS = "lastSuccessAt"
        const val KEY_LAST_ERROR = "lastError"
        const val KEY_LOCKED_UNTIL = "vaultLockedUntil"
        const val KEY_REFUSED_AT = "vaultCredentialsRefusedAt"
        const val SEP = "\u001F"
    }
}

internal class AndroidNetworkState(context: Context) : NetworkState {
    private val cm = context.getSystemService(ConnectivityManager::class.java)

    private fun caps(): NetworkCapabilities? = runCatching { cm?.getNetworkCapabilities(cm.activeNetwork) }.getOrNull()

    override fun isOnline(): Boolean = caps()?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

    override fun isUnmetered(): Boolean {
        val c = caps() ?: return false
        if (c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) return true
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_TEMPORARILY_NOT_METERED)
    }
}

/**
 * The saved vault password belongs to the vault link it was entered for: it is never sent to
 * another link (a new vault, a typo, a look-alike host). A password saved before it was tied to
 * a link, or while no link was set, belongs to the saved link. The same vault on the other host
 * of its site (`www.` added or dropped, see [VaultRedirect]) counts as the same link. Changing
 * the saved link to another vault forgets the password ([linkChanged]), so what settings show
 * as saved is always what sync sends.
 */
internal object VaultPassword {
    /** True when a password tied to [boundTo] (a fingerprint; null = untied) may be sent to [endpoint]. */
    fun fits(boundTo: String?, endpoint: VaultEndpoint, savedLink: String): Boolean {
        val host = endpoint.base.host
        val sibling = runCatching { endpoint.withHost(if (host.startsWith("www.")) host.removePrefix("www.") else "www.$host") }.getOrNull()
        val same = listOfNotNull(endpoint, sibling)
        return if (boundTo != null) same.any { it.fingerprint == boundTo } else VaultUrl.endpointOrNull(savedLink) in same
    }

    /**
     * True when a password tied to [boundTo] (null = untied) stays saved as the saved link changes
     * from [oldLink] to [newLink]: unless [newLink] is another vault. Clearing the link keeps it
     * (until another vault's link is entered); an untied password with no old link belongs to the new one.
     */
    fun keptAcross(boundTo: String?, oldLink: String, newLink: String): Boolean {
        val new = VaultUrl.endpointOrNull(newLink) ?: return true
        val owner = boundTo ?: VaultUrl.endpointOrNull(oldLink)?.fingerprint ?: return true
        return fits(owner, new, newLink)
    }

    /** The saved password if it may be sent to [endpoint]; [savedLink] is the vault link in settings. */
    @Synchronized
    fun storedFor(secrets: SecretStore, endpoint: VaultEndpoint, savedLink: String): String? {
        val password = secrets.get(SecretStore.VAULT_PASSWORD) ?: return null
        return password.takeIf { fits(secrets.get(SecretStore.VAULT_PASSWORD_VAULT), endpoint, savedLink) }
    }

    /** True when a saved password applies to [savedLink] (no link set: when one is saved at all). */
    @Synchronized
    fun savedFor(secrets: SecretStore, savedLink: String): Boolean {
        if (!secrets.has(SecretStore.VAULT_PASSWORD)) return false
        val endpoint = VaultUrl.endpointOrNull(savedLink) ?: return true
        return fits(secrets.get(SecretStore.VAULT_PASSWORD_VAULT), endpoint, savedLink)
    }

    /** Saves [password] for [endpoint] (null: for whichever link is saved when it is first used). */
    @Synchronized
    fun save(secrets: SecretStore, password: String, endpoint: VaultEndpoint?) {
        secrets.put(SecretStore.VAULT_PASSWORD, password)
        secrets.put(SecretStore.VAULT_PASSWORD_VAULT, endpoint?.fingerprint)
    }

    /** The saved link is about to change from [oldLink]: an untied password stays with it. */
    @Synchronized
    fun keepWith(secrets: SecretStore, oldLink: String) {
        val old = VaultUrl.endpointOrNull(oldLink) ?: return
        if (secrets.has(SecretStore.VAULT_PASSWORD) && !secrets.has(SecretStore.VAULT_PASSWORD_VAULT)) {
            secrets.put(SecretStore.VAULT_PASSWORD_VAULT, old.fingerprint)
        }
    }

    /**
     * The saved link is about to change from [oldLink] to [newLink]: a password for another vault
     * is forgotten (see [keptAcross]), one that stays is tied to its vault. True when it was forgotten.
     */
    @Synchronized
    fun linkChanged(secrets: SecretStore, oldLink: String, newLink: String): Boolean {
        if (!secrets.has(SecretStore.VAULT_PASSWORD)) return false
        val boundTo = if (secrets.has(SecretStore.VAULT_PASSWORD_VAULT)) {
            // Unreadable for now (Keystore hiccup): leave everything as it is.
            secrets.get(SecretStore.VAULT_PASSWORD_VAULT) ?: return false
        } else {
            null
        }
        if (keptAcross(boundTo, oldLink, newLink)) {
            keepWith(secrets, oldLink)
            return false
        }
        forget(secrets)
        return true
    }

    /** The same vault answers from [to] now (VaultRedirect): its password follows it. */
    @Synchronized
    fun moved(secrets: SecretStore, from: VaultEndpoint, to: VaultEndpoint) {
        if (secrets.get(SecretStore.VAULT_PASSWORD_VAULT) == from.fingerprint) secrets.put(SecretStore.VAULT_PASSWORD_VAULT, to.fingerprint)
    }

    @Synchronized
    fun forget(secrets: SecretStore) {
        secrets.remove(SecretStore.VAULT_PASSWORD)
        secrets.remove(SecretStore.VAULT_PASSWORD_VAULT)
        secrets.remove(SecretStore.VAULT_SESSION)
    }
}

/** Vault password and the cached session token, both in [SecretStore] (Keystore-encrypted). */
internal class SecretVaultCredentials(
    private val secrets: SecretStore,
    private val endpoint: VaultEndpoint,
    /** The vault link in settings, for a password not tied to a link yet (see [VaultPassword]). */
    private val savedLink: () -> String = { "" },
) : VaultCredentials {
    override fun password(): String? = VaultPassword.storedFor(secrets, endpoint, savedLink())

    override fun session(): VaultSession? = VaultSessionCodec.decode(secrets.get(SecretStore.VAULT_SESSION), endpoint.fingerprint)

    override fun saveSession(session: VaultSession?) {
        secrets.put(SecretStore.VAULT_SESSION, session?.let { VaultSessionCodec.encode(it, endpoint.fingerprint) })
    }
}
