package com.inkwell.notes.core.storage

import android.util.Log
import com.inkwell.notes.core.model.PageContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import java.util.concurrent.ConcurrentHashMap

/**
 * Loads and saves the vector content of pages. Writes are atomic and keep the previous
 * version; a corrupt or truncated file falls back to that backup, so an unexpected
 * shutdown can lose at most the edit in flight.
 */
class PageContentStore(private val files: FileStore) {
    private val locks = ConcurrentHashMap<String, Mutex>()
    private fun lock(pageId: String) = locks.getOrPut(pageId) { Mutex() }

    suspend fun load(docId: String, pageId: String): PageContent = withContext(Dispatchers.IO) {
        lock(pageId).withLock {
            val file = files.pageFile(docId, pageId)
            decodeOrNull(file.takeIf { it.exists() }?.readBytes())
                ?: decodeOrNull(FileStore.backupOf(file).takeIf { it.exists() }?.readBytes()).also {
                    if (it != null) Log.w(TAG, "Recovered page $pageId from backup")
                }
                ?: PageContent.EMPTY
        }
    }

    suspend fun save(docId: String, pageId: String, content: PageContent) = withContext(Dispatchers.IO) {
        lock(pageId).withLock {
            val bytes = encode(content)
            FileStore.writeAtomically(files.pageFile(docId, pageId)) { it.write(bytes) }
        }
    }

    suspend fun delete(docId: String, pageId: String) = withContext(Dispatchers.IO) {
        lock(pageId).withLock {
            val f = files.pageFile(docId, pageId)
            f.delete()
            FileStore.backupOf(f).delete()
        }
    }

    suspend fun copy(fromDoc: String, fromPage: String, toDoc: String, toPage: String) {
        save(toDoc, toPage, load(fromDoc, fromPage))
    }

    companion object {
        private const val TAG = "PageContentStore"
        val proto = ProtoBuf { encodeDefaults = false }

        fun encode(content: PageContent): ByteArray = proto.encodeToByteArray(content)

        fun decodeOrNull(bytes: ByteArray?): PageContent? {
            if (bytes == null || bytes.isEmpty()) return if (bytes != null) PageContent.EMPTY else null
            return try {
                proto.decodeFromByteArray<PageContent>(bytes)
            } catch (t: Throwable) {
                Log.e(TAG, "Unreadable page content (${bytes.size} bytes)", t)
                null
            }
        }
    }
}
