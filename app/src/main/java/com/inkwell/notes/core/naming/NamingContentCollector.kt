package com.inkwell.notes.core.naming

import android.util.Log
import com.inkwell.notes.app.AppGraph
import com.inkwell.notes.core.model.BlockType
import com.inkwell.notes.core.model.DocKind
import com.inkwell.notes.core.model.DocumentMeta
import com.inkwell.notes.core.model.TextSource
import com.inkwell.notes.core.model.TypedDocument
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Gathers what a document says, for topic inference: the search index (recognised
 * handwriting, typed text, OCR, PDF text, transcripts), typed blocks, and text boxes / sticky
 * notes stored in page content. Text already present in the index is not counted twice.
 * Never logs content.
 */
internal class NamingContentCollector(private val graph: AppGraph) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun collect(doc: DocumentMeta): NamingContent = withContext(Dispatchers.IO) {
        val segments = ArrayList<NamingSegment>()
        var budget = MAX_TOTAL_CHARS
        fun add(text: String, kind: SegmentKind) {
            if (budget <= 0) return
            val t = text.trim()
            if (t.isEmpty()) return
            val clipped = if (t.length > budget) t.take(budget) else t
            budget -= clipped.length
            segments += NamingSegment(clipped, kind)
        }

        doc.tags.forEach { add(it, SegmentKind.HEADING) }

        // 1. Everything the indexer recognised, page by page in reading order.
        val indexedLines = HashSet<String>()
        val passages = try {
            graph.search.documentText(doc.id)
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            Log.w(TAG, "Index unavailable for naming: ${e.javaClass.simpleName}")
            emptyList()
        }
        var lastPage: String? = "\u0000"
        for (p in passages) {
            if (p.source == TextSource.TITLE || p.source == TextSource.FOLDER) continue
            if (p.source == TextSource.TAG) {
                add(p.text, SegmentKind.HEADING)
                continue
            }
            var firstOfPage = p.pageId != lastPage
            lastPage = p.pageId
            for (line in p.text.lineSequence()) {
                if (line.isBlank()) continue
                indexedLines += normalize(line)
                add(line, if (firstOfPage) SegmentKind.LEAD else SegmentKind.BODY)
                firstOfPage = false
            }
        }

        // 2. Typed documents: headings weigh most, code is ignored.
        if (doc.kind == DocKind.TYPED) {
            val typed = readTyped(doc.id)
            var lead = true
            for (block in typed?.blocks.orEmpty()) {
                val lines = when (block.type) {
                    BlockType.TABLE -> block.rows.map { it.joinToString(" ") }
                    BlockType.CODE, BlockType.DIVIDER -> emptyList()
                    else -> listOf(block.text)
                }
                for (line in lines) {
                    if (line.isBlank() || normalize(line) in indexedLines) continue
                    val kind = when {
                        block.type == BlockType.HEADING -> SegmentKind.HEADING
                        lead -> SegmentKind.LEAD
                        else -> SegmentKind.BODY
                    }
                    add(line, kind)
                    lead = false
                }
            }
        }

        // 3. Text boxes, sticky notes and connector labels on the pages.
        val pages = try {
            graph.library.getPages(doc.id).take(MAX_PAGES)
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            emptyList()
        }
        for (page in pages) {
            if (budget <= 0) break
            val content = try {
                graph.pageStore.load(doc.id, page.id)
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                continue
            }
            for (t in content.texts.sortedWith(compareBy({ it.y }, { it.x }))) {
                val heading = t.fontSizePt >= HEADING_FONT_PT || t.bold
                for (line in t.text.lineSequence()) {
                    if (line.isBlank() || normalize(line) in indexedLines) continue
                    add(line, if (heading) SegmentKind.HEADING else SegmentKind.BODY)
                }
            }
            for (s in content.stickies) {
                for (line in s.text.lineSequence()) {
                    if (line.isNotBlank() && normalize(line) !in indexedLines) add(line, SegmentKind.BODY)
                }
            }
            for (c in content.connectors) {
                if (c.label.isNotBlank() && normalize(c.label) !in indexedLines) add(c.label, SegmentKind.BODY)
            }
        }
        NamingContent(segments)
    }

    private fun readTyped(docId: String): TypedDocument? {
        val file = graph.files.typedFile(docId)
        if (!file.isFile) return null
        return try {
            json.decodeFromString(TypedDocument.serializer(), file.readText())
        } catch (e: Exception) {
            Log.w(TAG, "Typed document unreadable for naming: ${e.javaClass.simpleName}")
            null
        }
    }

    private fun normalize(s: String) = s.trim().lowercase().replace(WHITESPACE, " ")

    private companion object {
        const val TAG = "NamingContent"
        const val MAX_TOTAL_CHARS = 200_000
        const val MAX_PAGES = 400
        const val HEADING_FONT_PT = 20f
        val WHITESPACE = Regex("\\s+")
    }
}
