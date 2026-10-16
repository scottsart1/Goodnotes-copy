package com.inkwell.notes.speakers.cloud

import com.inkwell.notes.speakers.SpeakerReference
import kotlinx.coroutines.CancellationException

/** What a reference name the provider may echo back stands for. */
sealed interface ReferenceTarget {
    /** A voice already heard in this recording. */
    data class Voice(val label: String) : ReferenceTarget

    /** A known person not heard in this recording yet. */
    data class Person(val personId: String, val name: String) : ReferenceTarget
}

/** The references of one request, and what each name in it stands for. */
class ReferenceSet(val references: List<SpeakerReference>, private val targets: Map<String, ReferenceTarget>) {
    /** The target of a label the provider returned (case-insensitive), or null for a fresh voice. */
    fun target(providerLabel: String?): ReferenceTarget? {
        val l = providerLabel?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return targets[l] ?: targets.entries.firstOrNull { it.key.equals(l, ignoreCase = true) }?.value
    }

    val names: Set<String> get() = targets.keys

    companion object {
        val EMPTY = ReferenceSet(emptyList(), emptyMap())
    }
}

/**
 * Chooses the known voices sent with each audio segment. The provider's labels are local to one
 * file, so voices heard earlier in the recording go first (most speech first, each with a clip
 * from where it spoke longest, named by its recording label "S1"): echoed back, they keep their
 * label across segments. Remaining slots go to known people not heard yet, sent under an alias
 * ("P1"): names never leave the device and can never collide with the provider's own "A", "B".
 */
object ReferencePlanner {
    const val MAX_REFERENCES = 4
    private const val PERSON_ALIAS = "P"

    /**
     * [people] come from the speaker service (owner first, then recent people); entries without
     * a person are ignored. [clip] cuts a WAV of the recording timeline (null: cannot);
     * [accept] rejects clips the provider would refuse.
     */
    suspend fun plan(
        voices: List<CloudVoice>,
        people: List<SpeakerReference>,
        max: Int = MAX_REFERENCES,
        accept: (ByteArray) -> Boolean = { it.isNotEmpty() },
        clip: suspend (startMs: Long, endMs: Long) -> ByteArray?,
    ): ReferenceSet {
        val limit = max.coerceIn(0, MAX_REFERENCES)
        if (limit == 0) return ReferenceSet.EMPTY
        val known = people.filter { !it.personId.isNullOrBlank() && it.name.isNotBlank() && accept(it.wav) }
            .distinctBy { it.personId }
        val refs = ArrayList<SpeakerReference>(limit)
        val targets = LinkedHashMap<String, ReferenceTarget>()

        for (v in voices.sortedWith(compareByDescending<CloudVoice> { it.speechMs }.thenBy { CloudLabels.indexOf(it.label) ?: Int.MAX_VALUE })) {
            if (refs.size == limit) break
            val start = v.clipStartMs
            val end = v.clipEndMs
            var wav = if (start != null && end != null && end > start) clip(start, end)?.takeIf(accept) else null
            // A recognised person without a long enough stretch here yet: their stored voice stands in.
            if (wav == null && v.personId != null) wav = known.firstOrNull { it.personId == v.personId }?.wav
            if (wav == null) continue
            refs += SpeakerReference(v.label, v.personId, wav)
            targets[v.label] = ReferenceTarget.Voice(v.label)
        }

        val heardPeople = voices.mapNotNull { it.personId }.toSet()
        var alias = 0
        for (p in known) {
            if (refs.size == limit) break
            val id = p.personId ?: continue
            if (id in heardPeople) continue
            val name = "$PERSON_ALIAS${++alias}"
            refs += SpeakerReference(name, id, p.wav)
            targets[name] = ReferenceTarget.Person(id, p.name.trim())
        }
        return ReferenceSet(refs, targets)
    }
}

/**
 * Supplies [ReferencePlanner] for one job: known people are loaded once, and clips of this
 * recording are cut once per window and dropped when no longer used. Clips stay in memory only.
 */
class CloudReferenceSource(
    private val loadPeople: suspend () -> List<SpeakerReference>,
    private val cutClip: suspend (startMs: Long, endMs: Long) -> ByteArray?,
    private val accept: (ByteArray) -> Boolean = { it.isNotEmpty() },
) {
    private var people: List<SpeakerReference>? = null
    private val clips = HashMap<Pair<Long, Long>, ByteArray?>()

    suspend fun referencesFor(voices: List<CloudVoice>): ReferenceSet {
        val known = people ?: quietly { loadPeople() }.orEmpty().also { people = it }
        val used = HashSet<Pair<Long, Long>>()
        val set = ReferencePlanner.plan(voices, known, accept = accept) { s, e ->
            val key = s to e
            used += key
            if (clips.containsKey(key)) clips[key] else quietly { cutClip(s, e) }.also { clips[key] = it }
        }
        clips.keys.retainAll(used)
        return set
    }

    /** A missing reference only costs label consistency; it must never fail the transcript. */
    private suspend fun <T> quietly(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
}
