package com.inkwell.notes.speakers.ui.logic

import com.inkwell.notes.core.model.Person
import com.inkwell.notes.core.model.RecordingSpeaker

/**
 * "Change speaker" on one transcript block, expressed as SpeakerService calls: move the block
 * to a voice of this recording, or to a voice added for a person not heard in it yet. Pure.
 */
sealed interface BlockMove {
    /** reassignSegment(startMs, label). */
    data class ToVoice(val label: String) : BlockMove

    /** reassignSegment(startMs, newLabel), then assignSpeaker(newLabel, personId). */
    data class ToPerson(val newLabel: String, val personId: String) : BlockMove

    /** reassignSegment(startMs, newLabel), then nameSpeaker(newLabel, name). */
    data class ToNewName(val newLabel: String, val name: String) : BlockMove
}

object SpeakerEdits {
    /** Moving a block to [person]: their voice in this recording when they have one. */
    fun moveToPerson(speakers: List<RecordingSpeaker>, person: Person): BlockMove {
        val voice = speakers.firstOrNull { it.personId == person.id && it.confirmed }
            ?: speakers.firstOrNull { it.personId == person.id }
        return voice?.let { BlockMove.ToVoice(it.label) }
            ?: BlockMove.ToPerson(SpeakerTranscript.nextFreeLabel(speakers.map { it.label }), person.id)
    }

    /**
     * Moving a block to a typed [name]: a voice of this recording already called that, a known
     * person of that name, or a new voice. Null for a blank name.
     */
    fun moveToName(speakers: List<RecordingSpeaker>, people: List<Person>, name: String): BlockMove? {
        val clean = NameSuggestions.clean(name)
        if (clean.isEmpty()) return null
        speakers.firstOrNull { it.personId != null && NameSuggestions.clean(it.displayName).equals(clean, ignoreCase = true) }
            ?.let { return BlockMove.ToVoice(it.label) }
        NameSuggestions.exact(people, clean)?.let { return moveToPerson(speakers, it) }
        return BlockMove.ToNewName(SpeakerTranscript.nextFreeLabel(speakers.map { it.label }), clean)
    }

    /** Voices [label] can be merged into: every other voice of the recording. */
    fun mergeTargets(speakers: List<RecordingSpeaker>, label: String): List<RecordingSpeaker> = speakers.filter { it.label != label }
}
