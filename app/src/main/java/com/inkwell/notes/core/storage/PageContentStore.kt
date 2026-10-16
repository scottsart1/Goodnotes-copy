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
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.CRC32

/**
 * Loads and saves the vector content of pages. Writes are atomic and keep the previous
 * version; a corrupt or truncated file falls back to that backup, so an unexpected
 * shutdown can lose at most the edit in flight.
 *
 * On disk a page is a small header ([MAGIC], payload length, CRC-32) followed by the ProtoBuf
 * payload. ProtoBuf alone is self-delimiting per field, so a file cut between two strokes would
 * decode "successfully" as fewer strokes and a 0-byte file as an empty page; the header makes
 * both detectable so that the backup is used instead. Files without the header are read as raw
 * ProtoBuf (the format of native packages and of files written before the header existed).
 */
class PageContentStore(private val files: FileStore) {
    private val locks = ConcurrentHashMap<String, Mutex>()
    private fun lock(pageId: String) = locks.getOrPut(pageId) { Mutex() }

    suspend fun load(docId: String, pageId: String): PageContent = withContext(Dispatchers.IO) {
        lock(pageId).withLock {
            val file = files.pageFile(docId, pageId)
            decodeFile(file.takeIf { it.exists() }?.readBytes())
                ?: decodeFile(FileStore.backupOf(file).takeIf { it.exists() }?.readBytes()).also {
                    if (it != null) Log.w(TAG, "Recovered page $pageId from backup")
                }
                ?: PageContent.EMPTY
        }
    }

    suspend fun save(docId: String, pageId: String, content: PageContent) = withContext(Dispatchers.IO) {
        lock(pageId).withLock {
            val bytes = frame(encode(content))
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

        /** "INK1" */
        private val MAGIC = byteArrayOf(0x49, 0x4E, 0x4B, 0x31)
        const val HEADER_BYTES = 12

        fun encode(content: PageContent): ByteArray = proto.encodeToByteArray(content)

        /** Raw ProtoBuf bytes to content; empty bytes are an empty page, unreadable bytes are null. */
        fun decodeOrNull(bytes: ByteArray?): PageContent? {
            if (bytes == null || bytes.isEmpty()) return if (bytes != null) PageContent.EMPTY else null
            return try {
                proto.decodeFromByteArray<PageContent>(bytes)
            } catch (t: Throwable) {
                Log.e(TAG, "Unreadable page content (${bytes.size} bytes)", t)
                null
            }
        }

        /** Wraps a ProtoBuf payload in the on-disk header (magic, length, CRC-32). */
        fun frame(payload: ByteArray): ByteArray {
            val crc = CRC32().apply { update(payload) }.value.toInt()
            return ByteBuffer.allocate(HEADER_BYTES + payload.size)
                .put(MAGIC).putInt(payload.size).putInt(crc).put(payload)
                .array()
        }

        /** True when the bytes start with the header magic (a raw ProtoBuf PageContent never does). */
        fun isFramed(bytes: ByteArray): Boolean =
            bytes.size >= MAGIC.size && (0 until MAGIC.size).all { bytes[it] == MAGIC[it] }

        /**
         * The payload of a framed file, or null when the header, length or checksum do not match
         * (truncated or corrupt). Unframed input is returned unchanged.
         */
        fun unframe(bytes: ByteArray): ByteArray? {
            if (!isFramed(bytes)) return bytes
            if (bytes.size < HEADER_BYTES) return null
            val buf = ByteBuffer.wrap(bytes, MAGIC.size, 8)
            val length = buf.int
            val crc = buf.int
            if (length < 0 || bytes.size != HEADER_BYTES + length) return null
            val payload = bytes.copyOfRange(HEADER_BYTES, bytes.size)
            return if (CRC32().apply { update(payload) }.value.toInt() == crc) payload else null
        }

        /**
         * Content of one file on disk, or null when the file is missing, truncated or corrupt
         * (so the caller tries the backup). A 0-byte file is damaged, not an empty page: empty
         * pages are written with a header.
         */
        fun decodeFile(bytes: ByteArray?): PageContent? {
            if (bytes == null || bytes.isEmpty()) return null
            val payload = unframe(bytes)
            if (payload == null) {
                Log.e(TAG, "Page file failed its integrity check (${bytes.size} bytes)")
                return null
            }
            return decodeOrNull(payload)
        }
    }
}
