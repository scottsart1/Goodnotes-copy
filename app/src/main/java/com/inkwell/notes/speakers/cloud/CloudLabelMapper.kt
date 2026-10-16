package com.inkwell.notes.speakers.cloud

import com.inkwell.notes.core.model.TranscriptSegment

/**
 * Puts the provider's per-file speaker labels onto the recording's voices. A label that echoes
 * a heard voice's reference ("S2") keeps that voice; one that echoes a person's reference maps
 * to the voice already linked to that person, or a new voice linked to them; any other label
 * ("A", "B", local to the file) becomes a new voice, the same one for every block of that file.
 */
object CloudLabelMapper {
    class Assigned(
        /** Blocks with [TranscriptSegment.speakerLabel] = recording label and [TranscriptSegment.speaker] = display name. */
        val segments: List<TranscriptSegment>,
        /** Voices after this file, including new ones. */
        val voices: List<CloudVoice>,
    )

    /**
     * [segments] are one file's blocks with the provider label in `speaker`. Blocks without a
     * label stay unattributed. [unnamed] names a voice nobody has named ("Speaker 3").
     */
    fun assign(
        voices: List<CloudVoice>,
        refs: ReferenceSet,
        segments: List<TranscriptSegment>,
        unnamed: (Int) -> String = { "Speaker $it" },
    ): Assigned {
        val all = voices.toMutableList()
        val local = HashMap<String, String>()
        val mapped = segments.sortedBy { it.startMs }.map { s ->
            val raw = s.speaker?.trim()?.takeIf { it.isNotEmpty() }
                ?: return@map s.copy(speaker = null, speakerLabel = null)
            val label = local.getOrPut(raw.lowercase()) { resolve(raw, refs, all) }
            val voice = all.first { it.label == label }
            s.copy(speaker = displayName(voice, unnamed), speakerLabel = label)
        }
        return Assigned(mapped, all)
    }

    fun displayName(voice: CloudVoice, unnamed: (Int) -> String = { "Speaker $it" }): String =
        voice.personName?.trim()?.takeIf { it.isNotEmpty() } ?: unnamed(CloudLabels.indexOf(voice.label) ?: 0)

    private fun resolve(raw: String, refs: ReferenceSet, voices: MutableList<CloudVoice>): String {
        when (val t = refs.target(raw)) {
            is ReferenceTarget.Voice -> if (voices.any { it.label == t.label }) return t.label
            is ReferenceTarget.Person -> {
                voices.firstOrNull { it.personId == t.personId }?.let { return it.label }
                return CloudLabels.next(voices).also { voices += CloudVoice(it, t.personId, t.name.takeIf { n -> n.isNotBlank() }) }
            }
            null -> Unit
        }
        return CloudLabels.next(voices).also { voices += CloudVoice(it) }
    }
}
