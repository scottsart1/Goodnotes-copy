package com.inkwell.notes.export.backup

import com.inkwell.notes.core.model.AudioAnchor
import com.inkwell.notes.core.model.OutlineEntry
import com.inkwell.notes.core.model.PageContent
import com.inkwell.notes.core.model.PageMeta
import java.util.UUID

/**
 * Fresh identifiers for an imported package, so importing the same package twice (or
 * restoring a backup next to the originals) never collides with existing rows.
 */
class IdRemap(
    val documentId: String,
    val pages: Map<String, String>,
    val recordings: Map<String, String>,
    val outline: Map<String, String>,
) {
    fun page(oldId: String): String? = pages[oldId]
    fun recording(oldId: String): String? = recordings[oldId]

    /** Page metadata under the new document, with a new id and the given position. */
    fun pageMeta(old: PageMeta, index: Int): PageMeta? {
        val id = pages[old.id] ?: return null
        return old.copy(id = id, documentId = documentId, index = index, thumbnailPath = null)
    }

    /**
     * Outline entries re-pointed at the new pages; entries whose page is gone are dropped and
     * their children move up to the nearest kept ancestor (or the root).
     */
    fun outlineEntries(old: List<OutlineEntry>): List<OutlineEntry> {
        val byId = old.associateBy { it.id }
        val kept = old.filter { outline[it.id] != null && pages[it.pageId] != null }.associateBy { it.id }
        fun newParent(e: OutlineEntry): String? {
            var p = e.parentId
            val seen = HashSet<String>()
            while (p != null && p != e.id && seen.add(p)) {
                if (p in kept) return outline[p]
                p = byId[p]?.parentId
            }
            return null
        }
        return kept.values.map { e ->
            e.copy(id = outline[e.id]!!, documentId = documentId, pageId = pages[e.pageId]!!, parentId = newParent(e))
        }
    }

    /** Re-points audio anchors at the new recordings; anchors to recordings not imported are cleared. */
    fun content(c: PageContent): PageContent {
        fun anchor(a: AudioAnchor?): AudioAnchor? = a?.let { old -> recordings[old.recordingId]?.let { AudioAnchor(it, old.offsetMs) } }
        if (c.strokes.none { it.audio != null } && c.shapes.none { it.audio != null } &&
            c.texts.none { it.audio != null } && c.images.none { it.audio != null }
        ) return c
        return c.copy(
            strokes = c.strokes.map { if (it.audio == null) it else it.copy(audio = anchor(it.audio)) },
            shapes = c.shapes.map { if (it.audio == null) it else it.copy(audio = anchor(it.audio)) },
            texts = c.texts.map { if (it.audio == null) it else it.copy(audio = anchor(it.audio)) },
            images = c.images.map { if (it.audio == null) it else it.copy(audio = anchor(it.audio)) },
        )
    }

    companion object {
        fun create(
            pageIds: List<String>,
            recordingIds: List<String>,
            outlineIds: List<String>,
            newId: () -> String = { UUID.randomUUID().toString() },
        ): IdRemap = IdRemap(
            documentId = newId(),
            pages = pageIds.distinct().associateWith { newId() },
            recordings = recordingIds.distinct().associateWith { newId() },
            outline = outlineIds.distinct().associateWith { newId() },
        )
    }
}
