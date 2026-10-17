package com.inkwell.notes.speakers.ui.logic

import com.inkwell.notes.core.model.Person
import com.inkwell.notes.core.model.RecordingSpeaker
import kotlinx.serialization.Serializable

/** What the user entered for one voice in the naming sheet. */
@Serializable
data class NamingDraft(
    val text: String = "",
    /** Set when a suggestion was picked, and kept only while [text] still is that person's name. */
    val personId: String? = null,
    /** "This is me" (at most one voice). */
    val isMe: Boolean = false,
    /** The voice already was the owner's when the sheet opened. */
    val wasMe: Boolean = false,
    /** The user changed this row; untouched rows follow the stored speaker and are never written. */
    val touched: Boolean = false,
)

/** One change the naming sheet applies through SpeakerService, in order. */
sealed interface NamingOp {
    /** assignSpeaker: a known person was picked. */
    data class Assign(val label: String, val personId: String) : NamingOp
    /** nameSpeaker: a typed name (reuses a person of that name or creates one). */
    data class Name(val label: String, val name: String, val isMe: Boolean) : NamingOp
    /** confirmSpeaker: the picked person is the automatic match. */
    data class Confirm(val label: String) : NamingOp
    /** setMe: "This is me" changed for a voice whose person stays the same. */
    data class SetMe(val personId: String?) : NamingOp
    /** dismissNaming: voices were left unnamed on purpose. */
    data object Dismiss : NamingOp
}

/**
 * The naming sheet ("after the meeting") as a pure state machine: drafts per voice label and
 * the service calls "Done" turns them into.
 */
object NamingPlan {
    fun initialDraft(s: RecordingSpeaker, meId: String?): NamingDraft {
        // A confirmed voice shows its name; an automatic match is offered as "Sounds like X" instead.
        if (s.personId != null && s.confirmed) {
            val me = s.personId == meId
            return NamingDraft(text = s.displayName, personId = s.personId, isMe = me, wasMe = me)
        }
        return NamingDraft()
    }

    fun initial(speakers: List<RecordingSpeaker>, meId: String?): Map<String, NamingDraft> =
        speakers.associate { it.label to initialDraft(it, meId) }

    /**
     * Follows the stored speakers: rows the user touched keep their edits, the others are
     * rebuilt (a voice confirmed or rejected meanwhile), rows of vanished voices are dropped.
     */
    fun merge(drafts: Map<String, NamingDraft>, speakers: List<RecordingSpeaker>, meId: String?): Map<String, NamingDraft> {
        val out = LinkedHashMap<String, NamingDraft>()
        for (s in speakers) {
            val d = drafts[s.label]
            out[s.label] = if (d != null && d.touched) d else initialDraft(s, meId)
        }
        // "This is me" stays exclusive when an untouched row picked it up from the store.
        val touchedMe = out.entries.firstOrNull { it.value.touched && it.value.isMe }?.key
        if (touchedMe != null) {
            for ((k, v) in out) if (k != touchedMe && v.isMe) out[k] = v.copy(isMe = false)
        }
        return out
    }

    /** The user typed in [label]'s field. A picked person is forgotten once the text differs from their name. */
    fun edit(drafts: Map<String, NamingDraft>, label: String, text: String, people: List<Person>): Map<String, NamingDraft> {
        val d = drafts[label] ?: NamingDraft()
        val picked = d.personId?.let { id -> people.firstOrNull { it.id == id } }
        val keep = picked != null && NameSuggestions.clean(text).equals(NameSuggestions.clean(picked.name), ignoreCase = true)
        return drafts + (label to d.copy(text = text, personId = if (keep) d.personId else null, touched = true))
    }

    /** The user picked [person] from the suggestions for [label]. Picking the owner sets "This is me". */
    fun pick(drafts: Map<String, NamingDraft>, label: String, person: Person): Map<String, NamingDraft> {
        val d = drafts[label] ?: NamingDraft()
        val next = drafts + (label to d.copy(text = person.name, personId = person.id, touched = true))
        return if (person.isMe) toggleMe(next, label, true) else next
    }

    /**
     * "This is me" on [label] (switching it off on every other voice). Ticking it on an empty
     * field fills in the [owner]'s existing name, so the voice is saved as them rather than
     * silently dropped for lack of a name.
     */
    fun toggleMe(drafts: Map<String, NamingDraft>, label: String, on: Boolean, owner: Person? = null): Map<String, NamingDraft> {
        val out = LinkedHashMap<String, NamingDraft>()
        for ((k, v) in drafts) {
            out[k] = when {
                k == label -> v.copy(isMe = on, touched = true)
                on && v.isMe -> v.copy(isMe = false, touched = true)
                else -> v
            }
        }
        if (label !in out) out[label] = NamingDraft(isMe = on, touched = true)
        if (on && owner != null) {
            val d = out.getValue(label)
            if (NameSuggestions.clean(d.text).isEmpty() && d.personId == null) out[label] = d.copy(text = owner.name, personId = owner.id)
        }
        return out
    }

    /**
     * Voices ticked "This is me" that have no name to save (nothing typed, nobody picked, no
     * person on the voice yet): "Done" must ask for the name instead of ignoring the tick.
     */
    fun missingNames(speakers: List<RecordingSpeaker>, drafts: Map<String, NamingDraft>): Set<String> {
        val out = LinkedHashSet<String>()
        for (s in speakers) {
            val d = drafts[s.label] ?: continue
            val nameless = d.touched && d.isMe && d.personId == null && NameSuggestions.clean(d.text).isEmpty()
            if (nameless && (s.personId == null || !s.confirmed)) out += s.label
        }
        return out
    }

    /**
     * The secondary action ("Skip" / "Save, skip the rest"): everything the user entered is
     * saved exactly as "Done" would, and the recording is dismissed so the app stops asking
     * about the voices left unnamed. Typed names are never discarded by skipping.
     */
    fun skipOperations(speakers: List<RecordingSpeaker>, drafts: Map<String, NamingDraft>): List<NamingOp> =
        operations(speakers, drafts).filter { it != NamingOp.Dismiss } + NamingOp.Dismiss

    /**
     * The service calls for "Done": names first, then owner changes, then [NamingOp.Dismiss] when
     * a voice stays unnamed (so the app does not ask about this recording again).
     */
    fun operations(speakers: List<RecordingSpeaker>, drafts: Map<String, NamingDraft>): List<NamingOp> {
        val names = ArrayList<NamingOp>()
        val me = ArrayList<NamingOp>()
        var unnamedLeft = false
        for (s in speakers) {
            val d = drafts[s.label] ?: NamingDraft()
            if (!d.touched) {
                if (s.personId == null) unnamedLeft = true
                continue
            }
            val text = NameSuggestions.clean(d.text)
            val picked = d.personId
            when {
                picked != null -> {
                    if (picked == s.personId) {
                        if (!s.confirmed) names += NamingOp.Confirm(s.label)
                        meChange(d, picked)?.let { me += it }
                    } else {
                        names += NamingOp.Assign(s.label, picked)
                        if (d.isMe && !d.wasMe) me += NamingOp.SetMe(picked)
                    }
                }
                text.isNotEmpty() -> {
                    val unchanged = s.personId != null && s.confirmed && text.equals(NameSuggestions.clean(s.displayName), ignoreCase = true)
                    if (unchanged) meChange(d, s.personId!!)?.let { me += it }
                    else names += NamingOp.Name(s.label, text, d.isMe)
                }
                else -> when {
                    s.personId == null -> unnamedLeft = true
                    s.confirmed -> meChange(d, s.personId)?.let { me += it }
                    // An automatic match left alone stays an automatic match.
                    else -> Unit
                }
            }
        }
        // One owner change at most: a voice marked "This is me" wins over another one unmarked
        // (moving the toggle unmarks the old row), and a typed name with "This is me" sets it itself.
        val meOn = me.filterIsInstance<NamingOp.SetMe>().lastOrNull { it.personId != null }
        val nameSetsMe = names.any { it is NamingOp.Name && it.isMe }
        return buildList {
            addAll(names)
            when {
                meOn != null && !nameSetsMe -> add(meOn)
                meOn == null && !nameSetsMe && me.isNotEmpty() -> add(NamingOp.SetMe(null))
            }
            if (unnamedLeft) add(NamingOp.Dismiss)
        }
    }

    private fun meChange(d: NamingDraft, personId: String): NamingOp? = when {
        d.isMe && !d.wasMe -> NamingOp.SetMe(personId)
        !d.isMe && d.wasMe -> NamingOp.SetMe(null)
        else -> null
    }

    /** Whether "Done" would change anything beyond dismissing. */
    fun hasChanges(speakers: List<RecordingSpeaker>, drafts: Map<String, NamingDraft>): Boolean =
        operations(speakers, drafts).any { it != NamingOp.Dismiss }
}
