package com.inkwell.notes.settings.logic

import java.util.Locale

/** One handwriting recognition language offered in settings. */
data class HandwritingLanguage(val tag: String, val name: String, val supported: Boolean = true)

/**
 * The handwriting language selection ([com.inkwell.notes.core.model.OcrPrefs.handwritingLanguages]).
 * The first tag is the primary language; at least one language always stays selected.
 */
object HandwritingLanguages {
    const val DEFAULT = "en-US"

    val supported: List<HandwritingLanguage> = listOf(
        HandwritingLanguage("en-US", "English (US)"),
        HandwritingLanguage("en-GB", "English (UK)"),
        HandwritingLanguage("de-DE", "German"),
        HandwritingLanguage("fr-FR", "French"),
        HandwritingLanguage("es-ES", "Spanish"),
        HandwritingLanguage("it-IT", "Italian"),
        HandwritingLanguage("pt-BR", "Portuguese (Brazil)"),
        HandwritingLanguage("nl-NL", "Dutch"),
        HandwritingLanguage("sv-SE", "Swedish"),
        HandwritingLanguage("pl-PL", "Polish"),
        HandwritingLanguage("tr-TR", "Turkish"),
        HandwritingLanguage("ru-RU", "Russian"),
        HandwritingLanguage("ja-JP", "Japanese"),
        HandwritingLanguage("ko-KR", "Korean"),
        HandwritingLanguage("zh-CN", "Chinese (Simplified)"),
        HandwritingLanguage("hi-IN", "Hindi"),
        HandwritingLanguage("ar", "Arabic"),
    )

    private val byLowerTag: Map<String, HandwritingLanguage> = supported.associateBy { it.tag.lowercase(Locale.ROOT) }

    sealed interface Change {
        data class Updated(val tags: List<String>) : Change
        /** The change was refused; [reason] is shown to the user. */
        data class Refused(val reason: String) : Change
    }

    /**
     * Canonical BCP-47 spelling: trims, accepts "_" separators, and matches the supported
     * list case-insensitively ("EN_us" -> "en-US"). Unknown tags get language lower-case and
     * region upper-case. Returns null for blank input.
     */
    fun canonical(tag: String): String? {
        val t = tag.trim().replace('_', '-')
        if (t.isEmpty()) return null
        byLowerTag[t.lowercase(Locale.ROOT)]?.let { return it.tag }
        val parts = t.split('-').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        return parts.mapIndexed { i, p ->
            when {
                i == 0 -> p.lowercase(Locale.ROOT)
                p.length == 2 -> p.uppercase(Locale.ROOT)
                p.length == 4 -> p.lowercase(Locale.ROOT).replaceFirstChar { it.titlecase(Locale.ROOT) }
                else -> p
            }
        }.joinToString("-")
    }

    /** Canonical, de-duplicated (first occurrence wins), never empty. */
    fun normalize(tags: List<String>): List<String> {
        val out = LinkedHashSet<String>()
        for (t in tags) canonical(t)?.let { out += it }
        return if (out.isEmpty()) listOf(DEFAULT) else out.toList()
    }

    fun displayName(tag: String): String {
        val c = canonical(tag) ?: return tag
        byLowerTag[c.lowercase(Locale.ROOT)]?.let { return it.name }
        return runCatching { Locale.forLanguageTag(c).getDisplayName(Locale.ENGLISH) }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: c
    }

    fun isSupported(tag: String): Boolean = canonical(tag)?.lowercase(Locale.ROOT)?.let { it in byLowerTag } == true

    fun isSelected(selection: List<String>, tag: String): Boolean {
        val c = canonical(tag) ?: return false
        return normalize(selection).any { it.equals(c, ignoreCase = true) }
    }

    /** Adds [tag] at the end, or removes it. The last remaining language cannot be removed. */
    fun toggle(selection: List<String>, tag: String): Change {
        val c = canonical(tag) ?: return Change.Refused("That language code isn't valid.")
        val current = normalize(selection)
        val present = current.any { it.equals(c, ignoreCase = true) }
        if (!present) return Change.Updated(current + c)
        if (current.size == 1) return Change.Refused("Keep at least one handwriting language selected.")
        return Change.Updated(current.filterNot { it.equals(c, ignoreCase = true) })
    }

    /** Moves [tag] to the front (selecting it if needed). */
    fun makePrimary(selection: List<String>, tag: String): List<String> {
        val c = canonical(tag) ?: return normalize(selection)
        val rest = normalize(selection).filterNot { it.equals(c, ignoreCase = true) }
        return listOf(c) + rest
    }

    fun primary(selection: List<String>): String = normalize(selection).first()

    /**
     * Rows to display: every supported language, followed by any selected tag that is not in
     * the supported list (kept so the user can see and remove it).
     */
    fun rows(selection: List<String>): List<HandwritingLanguage> {
        val extras = normalize(selection)
            .filterNot { isSupported(it) }
            .map { HandwritingLanguage(it, displayName(it), supported = false) }
        return supported + extras
    }

    /** "English (US), German" style summary of the selection, primary first. */
    fun summary(selection: List<String>): String = normalize(selection).joinToString(", ") { displayName(it) }
}
