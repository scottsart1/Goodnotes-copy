package com.inkwell.notes.editor

import android.graphics.Bitmap
import android.os.Build
import com.inkwell.notes.app.AppGraph
import com.inkwell.notes.core.model.PageContent
import com.inkwell.notes.core.storage.FileStore
import com.inkwell.notes.core.storage.PageContentStore
import com.inkwell.notes.core.storage.db.ReusableElementEntity
import com.inkwell.notes.editor.logic.FragmentOps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * "My elements": reusable drawings, diagrams and signatures saved from a selection. Their
 * images live in filesDir/elements/assets so they survive the source document being deleted.
 */
class ElementsStore(private val graph: AppGraph) {
    private val root = File(graph.context.filesDir, "elements")
    private val assets get() = File(root, "assets").apply { mkdirs() }
    private val thumbs get() = File(root, "thumbs").apply { mkdirs() }
    private val dao get() = graph.db.reusableElements()

    fun observe(): Flow<List<ReusableElementEntity>> = dao.observeAll()

    suspend fun save(name: String, fragment: PageContent, sourceDocId: String, thumbnail: Bitmap?): ReusableElementEntity = withContext(Dispatchers.IO) {
        val (normalized, size) = FragmentOps.normalizeToOrigin(FragmentOps.withFreshIds(fragment, keepAudio = false))
        for (assetId in FragmentOps.assetIds(normalized)) {
            val src = graph.files.assetFile(sourceDocId, assetId)
            val dst = File(assets, FileStore.safe(assetId))
            if (src.isFile && !dst.exists()) src.copyTo(dst)
        }
        val id = UUID.randomUUID().toString()
        val thumbPath = thumbnail?.let { bmp ->
            val f = File(thumbs, "$id.webp")
            FileStore.writeAtomically(f, keepBackup = false) { out ->
                @Suppress("DEPRECATION")
                val format = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
                bmp.compress(format, 85, out)
            }
            f.absolutePath
        }
        val entity = ReusableElementEntity(
            id = id,
            name = name.trim().ifEmpty { "Element" },
            kind = "drawing",
            payload = PageContentStore.encode(normalized),
            widthPt = size.width,
            heightPt = size.height,
            thumbnailPath = thumbPath,
            createdAt = System.currentTimeMillis(),
        )
        dao.upsert(entity)
        entity
    }

    suspend fun rename(element: ReusableElementEntity, name: String) = withContext(Dispatchers.IO) {
        dao.upsert(element.copy(name = name.trim().ifEmpty { element.name }))
    }

    suspend fun delete(element: ReusableElementEntity) = withContext(Dispatchers.IO) {
        dao.delete(element.id)
        element.thumbnailPath?.let { File(it).delete() }
        // Remove images no other element still uses.
        val mine = decode(element)?.let(FragmentOps::assetIds).orEmpty()
        if (mine.isNotEmpty()) {
            val stillUsed = dao.observeAll().first().flatMapTo(HashSet()) { e -> decode(e)?.let(FragmentOps::assetIds).orEmpty() }
            (mine - stillUsed).forEach { File(assets, FileStore.safe(it)).delete() }
        }
    }

    /** The element as a fragment ready to insert into [docId] (fresh ids, images copied in). */
    suspend fun prepareForInsert(element: ReusableElementEntity, docId: String): PageContent? = withContext(Dispatchers.IO) {
        val content = decode(element) ?: return@withContext null
        for (assetId in FragmentOps.assetIds(content)) {
            val src = File(assets, FileStore.safe(assetId))
            val dst = graph.files.assetFile(docId, assetId)
            if (src.isFile && !dst.exists()) src.copyTo(dst)
        }
        FragmentOps.withFreshIds(content, keepAudio = false)
    }

    private fun decode(e: ReusableElementEntity): PageContent? = PageContentStore.decodeOrNull(e.payload)
}
