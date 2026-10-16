package com.inkwell.notes.core.naming

import android.util.Log
import com.inkwell.notes.core.storage.FileStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** Per-document bookkeeping of the background naming job that has no column in the database. */
@Serializable
data class NamingDocState(
    /** Last automatic analysis (epoch ms); spaces out INSUFFICIENT re-checks. */
    val lastCheckAt: Long = 0,
    /** Transient AI failures so far; after [AutoNamingRules.MAX_AI_FAILURES] naming falls back to local inference. */
    val aiFailures: Int = 0,
)

@Serializable
private data class NamingStateFile(val version: Int = 1, val docs: Map<String, NamingDocState> = emptyMap())

/**
 * Small JSON file (files/naming/state.json) holding [NamingDocState] by document id. Writes are
 * atomic; a missing or unreadable file simply means "never checked", which is always safe.
 */
class NamingStateStore(private val file: File) {
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    suspend fun all(): Map<String, NamingDocState> = mutex.withLock { read() }

    suspend fun get(docId: String): NamingDocState = all()[docId] ?: NamingDocState()

    suspend fun update(docId: String, transform: (NamingDocState) -> NamingDocState?) {
        mutex.withLock {
            val current = read()
            val next = transform(current[docId] ?: NamingDocState())
            val updated = if (next == null || next == NamingDocState()) current - docId else current + (docId to next)
            if (updated != current) write(updated)
        }
    }

    /** Drops entries of documents that are no longer waiting for a name. */
    suspend fun retainOnly(docIds: Set<String>) {
        mutex.withLock {
            val current = read()
            val kept = current.filterKeys { it in docIds }
            if (kept.size != current.size) write(kept)
        }
    }

    private suspend fun read(): Map<String, NamingDocState> = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext emptyMap()
        try {
            json.decodeFromString(NamingStateFile.serializer(), file.readText()).docs
        } catch (e: Exception) {
            Log.w(TAG, "Naming state unreadable; starting fresh (${e.javaClass.simpleName})")
            emptyMap()
        }
    }

    private suspend fun write(docs: Map<String, NamingDocState>): Unit = withContext(Dispatchers.IO) {
        try {
            val bytes = json.encodeToString(NamingStateFile.serializer(), NamingStateFile(docs = docs)).encodeToByteArray()
            FileStore.writeAtomically(file, keepBackup = false) { it.write(bytes) }
        } catch (e: Exception) {
            Log.w(TAG, "Could not save naming state (${e.javaClass.simpleName})")
        }
    }

    private companion object {
        const val TAG = "NamingState"
    }
}
