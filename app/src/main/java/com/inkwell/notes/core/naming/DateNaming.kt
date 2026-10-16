package com.inkwell.notes.core.naming

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The "YYYYMMDD_Topic" naming convention. The date prefix always comes from the
 * document's original creation time and is never recomputed from edits.
 */
object DateNaming {
    const val UNTITLED = "Untitled"
    private val FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
    private val PREFIX = Regex("^(\\d{8})_(.*)$")
    private val SUFFIX = Regex("^(.*) \\((\\d+)\\)$")
    private val ILLEGAL = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]")
    const val MAX_TOPIC_WORDS = 5
    const val MAX_TOPIC_CHARS = 48

    fun datePrefix(createdAtMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        FORMAT.format(Instant.ofEpochMilli(createdAtMs).atZone(zone))

    fun initialTitle(createdAtMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        "${datePrefix(createdAtMs, zone)}_$UNTITLED"

    fun titleFor(createdAtMs: Long, topic: String, zone: ZoneId = ZoneId.systemDefault()): String =
        "${datePrefix(createdAtMs, zone)}_${cleanTopic(topic) ?: UNTITLED}"

    /** True for "YYYYMMDD_Untitled" and its numbered variants ("YYYYMMDD_Untitled (2)"). */
    fun isUntitled(title: String): Boolean {
        val m = PREFIX.matchEntire(title) ?: return false
        val rest = SUFFIX.matchEntire(m.groupValues[2])?.groupValues?.get(1) ?: m.groupValues[2]
        return rest == UNTITLED
    }

    /** The topic part of a date-named title, or null if the title does not follow the convention. */
    fun topicOf(title: String): String? = PREFIX.matchEntire(title)?.groupValues?.get(2)

    /**
     * Normalises an inferred topic: strips quotes/punctuation and illegal file characters,
     * Title Cases it, limits it to [MAX_TOPIC_WORDS] words. Returns null when nothing
     * meaningful is left or the topic is too generic.
     */
    fun cleanTopic(raw: String): String? {
        var t = raw.trim().lineSequence().firstOrNull()?.trim() ?: return null
        t = t.removePrefix("Topic:").removePrefix("topic:").trim()
        t = t.trim('"', '\'', '`', '*', '.', ',', ';', ':', '-', '_', ' ', '“', '”', '‘', '’')
        t = t.replace(ILLEGAL, " ").replace('_', ' ').replace(Regex("\\s+"), " ").trim()
        if (t.isEmpty()) return null
        val words = t.split(' ').filter { it.isNotBlank() }.take(MAX_TOPIC_WORDS)
        t = words.mapIndexed { i, w ->
            when {
                w.any { it.isUpperCase() } -> w
                // Title Case keeps short connectives lowercase inside a topic ("Bank of England").
                i > 0 && i < words.lastIndex && w in MINOR_WORDS -> w
                else -> w.replaceFirstChar { it.titlecase() }
            }
        }.joinToString(" ")
        if (t.length > MAX_TOPIC_CHARS) t = t.take(MAX_TOPIC_CHARS).substringBeforeLast(' ').ifEmpty { t.take(MAX_TOPIC_CHARS) }
        if (t.lowercase() in GENERIC) return null
        return t
    }

    /**
     * Makes [desired] unique against [existing] titles by appending " (2)", " (3)", ...
     * Comparison ignores case.
     */
    fun unique(desired: String, existing: Collection<String>): String {
        val taken = existing.mapTo(HashSet()) { it.lowercase() }
        if (desired.lowercase() !in taken) return desired
        var n = 2
        while ("$desired ($n)".lowercase() in taken) n++
        return "$desired ($n)"
    }

    private val MINOR_WORDS = setOf("a", "an", "and", "as", "at", "by", "for", "in", "of", "on", "or", "the", "to", "vs", "with")

    private val GENERIC = setOf(
        "untitled", "notes", "note", "my notes", "general", "misc", "miscellaneous", "document", "notebook",
        "page", "pages", "stuff", "things", "various", "random", "new notebook", "new note", "draft", "test",
        "unknown", "none", "n/a", "na", "null",
    )
}
