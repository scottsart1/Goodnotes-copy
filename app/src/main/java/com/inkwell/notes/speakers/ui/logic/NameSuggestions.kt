package com.inkwell.notes.speakers.ui.logic

import com.inkwell.notes.core.model.Person
import java.util.Locale

/** Cleaning typed names and suggesting known people while a name is typed. Pure. */
object NameSuggestions {
    const val MAX_NAME = 60
    private val SPACES = Regex("\\s+")
    private val WORD_BREAK = Regex("[\\s\\-]+")

    /** Single-spaced, trimmed, at most [MAX_NAME] characters (never cutting a surrogate pair). */
    fun clean(raw: String): String {
        val s = raw.replace(SPACES, " ").trim()
        if (s.length <= MAX_NAME) return s
        var end = MAX_NAME
        if (Character.isHighSurrogate(s[end - 1])) end--
        return s.substring(0, end).trim()
    }

    /** The person called [name] (case-insensitive, after cleaning), or null. */
    fun exact(people: List<Person>, name: String): Person? {
        val n = clean(name)
        if (n.isEmpty()) return null
        return people.firstOrNull { clean(it.name).equals(n, ignoreCase = true) }
    }

    /**
     * Up to [limit] people for the typed [query]: names starting with it, then names with a later
     * word starting with it ("sh" finds "Priya Shah"). The tablet's owner comes first, then
     * people heard in more recordings. A blank query suggests everyone in that order.
     */
    fun rank(people: List<Person>, query: String, limit: Int = 5, excludeIds: Set<String> = emptySet()): List<Person> {
        if (limit <= 0) return emptyList()
        val q = clean(query).lowercase(Locale.ROOT)
        val scored = ArrayList<Pair<Person, Int>>()
        for (p in people) {
            if (p.id in excludeIds) continue
            val name = clean(p.name).lowercase(Locale.ROOT)
            if (name.isEmpty()) continue
            val tier = when {
                q.isEmpty() -> 0
                name.startsWith(q) -> 0
                name.split(WORD_BREAK).drop(1).any { it.startsWith(q) } -> 1
                else -> continue
            }
            scored += p to tier
        }
        return scored
            .sortedWith(
                compareByDescending<Pair<Person, Int>> { it.first.isMe }
                    .thenBy { it.second }
                    .thenByDescending { it.first.recordingCount }
                    .thenBy { it.first.name.lowercase(Locale.ROOT) },
            )
            .take(limit)
            .map { it.first }
    }
}
