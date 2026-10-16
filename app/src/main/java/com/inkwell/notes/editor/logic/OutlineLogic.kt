package com.inkwell.notes.editor.logic

import com.inkwell.notes.core.model.OutlineEntry
import com.inkwell.notes.core.model.PageMeta
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.util.UUID

/** One row of the outline as displayed: the entry, its nesting depth and target page. */
data class OutlineNode(val entry: OutlineEntry, val depth: Int, val pageIndex: Int?)

object OutlineTree {
    /**
     * Flattens entries into display order (depth-first, siblings by sortOrder). Entries whose
     * parent is missing, or that sit in a parent cycle, are shown at the top level.
     */
    fun flatten(entries: List<OutlineEntry>, pageIndexById: Map<String, Int>): List<OutlineNode> {
        val byId = entries.associateBy { it.id }
        fun validParent(e: OutlineEntry): String? {
            val p = e.parentId ?: return null
            if (!byId.containsKey(p)) return null
            // Every ancestor chain must end at a root; a chain that loops makes the entry a root.
            val seen = hashSetOf(e.id)
            var cur: String? = p
            while (cur != null) {
                if (!seen.add(cur)) return null
                cur = byId[cur]?.parentId?.takeIf { byId.containsKey(it) }
            }
            return p
        }
        val parentOf = entries.associate { it.id to validParent(it) }
        val children = entries.groupBy { parentOf[it.id] }
            .mapValues { (_, list) -> list.sortedWith(compareBy<OutlineEntry> { it.sortOrder }.thenBy { pageIndexById[it.pageId] ?: Int.MAX_VALUE }) }
        val out = ArrayList<OutlineNode>(entries.size)
        fun visit(parent: String?, depth: Int) {
            children[parent]?.forEach { e ->
                out += OutlineNode(e, depth, pageIndexById[e.pageId])
                visit(e.id, depth + 1)
            }
        }
        visit(null, 0)
        return out
    }
}

/** An outline entry proposed by the AI: [pageIndex] 0-based, [level] 1 = top. */
data class AiOutlineItem(val title: String, val pageIndex: Int, val level: Int)

/** Prompt building and lenient parsing for "Generate outline with AI". */
object AiOutline {
    const val MAX_CHARS_PER_PAGE = 1_200
    const val MAX_TOTAL_CHARS = 24_000
    const val MAX_LEVEL = 3
    const val MAX_TITLE_CHARS = 80

    val SYSTEM_PROMPT = """
        You create a table of contents for a notebook. You receive the recognised text of each page,
        each introduced by a line "### pageIndex N". Reply with ONLY a JSON array, no prose, where each
        element is {"title": string, "pageIndex": integer, "level": integer}. pageIndex is the number from
        the page marker where the section starts. level is 1 for main sections, 2 for subsections, 3 at most.
        Use short, specific titles in the language of the notes. Keep entries in page order. Do not invent
        sections that are not supported by the text.
    """.trimIndent()

    /** Builds the user message from (pageIndex, text) pairs, trimming long pages and the total. */
    fun buildPrompt(pages: List<Pair<Int, String>>): String {
        val sb = StringBuilder()
        for ((index, raw) in pages.sortedBy { it.first }) {
            val text = raw.replace(Regex("\\s+"), " ").trim()
            if (text.isEmpty()) continue
            val chunk = "### pageIndex $index\n" + text.take(MAX_CHARS_PER_PAGE) + "\n\n"
            if (sb.length + chunk.length > MAX_TOTAL_CHARS) break
            sb.append(chunk)
        }
        return sb.toString().trim()
    }

    private val json = Json { isLenient = true; ignoreUnknownKeys = true }

    /** Extracts outline items from a model reply; tolerates code fences, prose and key variants. */
    fun parse(reply: String, pageCount: Int): List<AiOutlineItem> {
        val element = extractJson(reply) ?: return emptyList()
        val array: JsonArray = when (element) {
            is JsonArray -> element
            is JsonObject -> (element["outline"] ?: element["entries"] ?: element["items"] ?: element["toc"]) as? JsonArray ?: return emptyList()
            else -> return emptyList()
        }
        return array.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val title = (obj["title"] as? JsonPrimitive)?.contentOrNull?.trim()?.replace(Regex("\\s+"), " ")?.take(MAX_TITLE_CHARS)
            if (title.isNullOrEmpty()) return@mapNotNull null
            val index = intOf(obj["pageIndex"]) ?: intOf(obj["page_index"]) ?: intOf(obj["page"])?.minus(1) ?: return@mapNotNull null
            if (index !in 0 until pageCount) return@mapNotNull null
            val level = (intOf(obj["level"]) ?: 1).coerceIn(1, MAX_LEVEL)
            AiOutlineItem(title, index, level)
        }
    }

    private fun intOf(e: JsonElement?): Int? {
        val p = e as? JsonPrimitive ?: return null
        return p.intOrNull ?: p.contentOrNull?.trim()?.toDoubleOrNull()?.toInt()
    }

    private fun extractJson(reply: String): JsonElement? {
        val cleaned = reply.replace(Regex("```[a-zA-Z]*"), "").trim()
        runCatching { return json.parseToJsonElement(cleaned) }
        val start = cleaned.indexOf('[')
        val end = cleaned.lastIndexOf(']')
        if (start >= 0 && end > start) runCatching { return json.parseToJsonElement(cleaned.substring(start, end + 1)) }
        val os = cleaned.indexOf('{')
        val oe = cleaned.lastIndexOf('}')
        if (os >= 0 && oe > os) runCatching { return json.parseToJsonElement(cleaned.substring(os, oe + 1)) }
        return null
    }

    /**
     * Turns items into outline entries nested by level: an entry's parent is the closest
     * preceding entry with a lower level. Only adds entries; page content is never touched.
     */
    fun toEntries(
        items: List<AiOutlineItem>,
        pages: List<PageMeta>,
        docId: String,
        startSortOrder: Int,
        newId: () -> String = { UUID.randomUUID().toString() },
    ): List<OutlineEntry> {
        val stack = ArrayList<Pair<Int, String>>() // level -> id
        val out = ArrayList<OutlineEntry>(items.size)
        var sort = startSortOrder
        for (item in items) {
            val page = pages.getOrNull(item.pageIndex) ?: continue
            while (stack.isNotEmpty() && stack.last().first >= item.level) stack.removeAt(stack.lastIndex)
            val id = newId()
            out += OutlineEntry(id = id, documentId = docId, title = item.title, pageId = page.id, parentId = stack.lastOrNull()?.second, sortOrder = sort++)
            stack += item.level to id
        }
        return out
    }
}
