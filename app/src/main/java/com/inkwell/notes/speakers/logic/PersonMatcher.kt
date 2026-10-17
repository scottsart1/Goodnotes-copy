package com.inkwell.notes.speakers.logic

/** A person's stored voice: up to [VoiceSamples.MAX_SAMPLES] L2-normalised samples. */
class PersonPrint(
    val personId: String,
    val isMe: Boolean,
    val samples: List<FloatArray>,
) {
    /** L2-normalised mean of the samples. */
    val mean: FloatArray? = VectorMath.mean(samples)
}

/**
 * Recognises known people among the voices of a recording. Pure.
 *
 * A voice's score against a person is max(cosine to the person's mean, mean of the three best
 * sample cosines): the mean is robust, the best samples catch a person who sounds different
 * in different rooms. Scores are calibrated with [VoiceScores.similarity] so they are
 * comparable with SpeakerPrefs.matchThreshold. Each voice gets at most one person and each
 * person at most one voice, best scores first; on (near) ties the tablet's owner wins.
 */
object PersonMatcher {
    private const val TOP = 3

    data class Match(val label: String, val personId: String, val score: Float)

    /** Calibrated similarity (0..1) of [centroid] to [print]. */
    fun score(centroid: FloatArray, print: PersonPrint): Float {
        if (print.samples.isEmpty()) return 0f
        val toMean = print.mean?.let { VectorMath.cosine(centroid, it) } ?: -1f
        val best = print.samples.map { VectorMath.cosine(centroid, it) }.sortedDescending().take(TOP)
        val topMean = best.average().toFloat()
        return VoiceScores.similarity(maxOf(toMean, topMean))
    }

    /**
     * Matches [voices] (label to L2-normalised centroid) against [people]. Only scores at or
     * above [threshold] are accepted. [taken] people are already in this recording.
     */
    fun match(
        voices: Map<String, FloatArray>,
        people: List<PersonPrint>,
        threshold: Float,
        taken: Set<String> = emptySet(),
    ): List<Match> {
        data class Candidate(val label: String, val person: PersonPrint, val score: Float)
        val candidates = ArrayList<Candidate>()
        for ((label, c) in voices) for (p in people) {
            if (p.personId in taken || p.samples.isEmpty()) continue
            if (p.samples.any { it.size != c.size }) continue
            val s = score(c, p)
            if (s >= threshold && s > 0f) candidates += Candidate(label, p, s)
        }
        // Scores within 1e-4 count as a tie, broken in favour of the owner, then deterministically.
        val sorted = candidates.sortedWith(
            compareByDescending<Candidate> { Math.round(it.score * 10_000.0) }
                .thenByDescending { it.person.isMe }
                .thenByDescending { it.score }
                .thenBy { it.label }
                .thenBy { it.person.personId },
        )
        val usedLabels = HashSet<String>()
        val usedPeople = HashSet<String>()
        val out = ArrayList<Match>()
        for (c in sorted) {
            if (c.label in usedLabels || c.person.personId in usedPeople) continue
            usedLabels += c.label
            usedPeople += c.person.personId
            out += Match(c.label, c.person.personId, c.score.coerceIn(0f, 1f))
        }
        return out.sortedBy { it.label }
    }
}

/** Keeps a person's voiceprint samples useful and bounded. Pure. */
object VoiceSamples {
    const val MAX_SAMPLES = 30

    /**
     * [samples] with [sample] added. A sample with the same tag is replaced (re-confirming a
     * voice refines it instead of counting twice); a near-duplicate of a stored sample
     * ([VoiceScores.DUPLICATE_SAMPLE]) is not added. Beyond [max], the most redundant sample
     * (highest similarity to its nearest neighbour; the oldest on ties) is dropped.
     */
    fun add(samples: List<VoiceVector>, sample: VoiceVector, max: Int = MAX_SAMPLES): List<VoiceVector> {
        val v = VectorMath.normalize(sample.vector)
        if (VectorMath.norm(v) == 0f) return samples
        val fresh = VoiceVector(sample.tag, sample.weight, v)
        val others = samples.filter { it.tag != sample.tag || sample.tag.isEmpty() }
        val replacing = others.size != samples.size
        if (!replacing && others.any { it.vector.size == v.size && VectorMath.cosine(it.vector, v) >= VoiceScores.DUPLICATE_SAMPLE }) {
            return samples
        }
        return cap(others + fresh, max)
    }

    /** Drops the most redundant samples until at most [max] remain. */
    fun cap(samples: List<VoiceVector>, max: Int = MAX_SAMPLES): List<VoiceVector> {
        val list = samples.toMutableList()
        while (list.size > max.coerceAtLeast(1)) {
            var worst = 0
            var worstScore = Float.NEGATIVE_INFINITY
            for (i in list.indices) {
                var nearest = Float.NEGATIVE_INFINITY
                for (j in list.indices) if (i != j) nearest = maxOf(nearest, VectorMath.cosine(list[i].vector, list[j].vector))
                // Strictly greater: the earliest (oldest) wins ties.
                if (nearest > worstScore) {
                    worstScore = nearest
                    worst = i
                }
            }
            list.removeAt(worst)
        }
        return list
    }

    /** Samples without the ones whose tag starts with [prefix]. */
    fun removeTagged(samples: List<VoiceVector>, prefix: String): List<VoiceVector> = samples.filterNot { it.tag.startsWith(prefix) }

    /** Tag of a sample enrolled from voice [label] of [recordingId]. */
    fun recordingTag(recordingId: String, label: String): String = "rec:$recordingId:$label"

    fun recordingPrefix(recordingId: String): String = "rec:$recordingId:"
}

/** Which reference clip to keep for a person (cloud recognition needs 2-10 s clips). Pure. */
object ReferencePolicy {
    const val MIN_CLIP_MS = 2_000L
    const val MAX_CLIP_MS = 10_000L
    const val PREFERRED_MS = 8_000L

    /** Below this gain a stored clip is kept (avoids churn between similar clips). */
    const val MIN_GAIN_MS = 500L

    /** Value of a clip: its speech up to ~8 s; past that, longer clips are slightly worse. */
    fun score(speechMs: Long, clipMs: Long): Long {
        if (clipMs !in MIN_CLIP_MS..MAX_CLIP_MS) return Long.MIN_VALUE
        return minOf(speechMs, PREFERRED_MS) - (clipMs - PREFERRED_MS).coerceAtLeast(0) / 4
    }

    /** True when a candidate clip should replace the stored one (null: nothing stored). */
    fun shouldReplace(storedSpeechMs: Long?, storedClipMs: Long?, speechMs: Long, clipMs: Long): Boolean {
        val candidate = score(speechMs, clipMs)
        if (candidate == Long.MIN_VALUE || speechMs < MIN_CLIP_MS) return false
        if (storedSpeechMs == null || storedClipMs == null) return true
        val stored = score(storedSpeechMs, storedClipMs)
        return stored == Long.MIN_VALUE || candidate >= stored + MIN_GAIN_MS
    }

    /** Duration of a 16 kHz mono 16-bit WAV of [bytes] (header included). */
    fun wavDurationMs(bytes: Int): Long = ((bytes - WavWriter.HEADER_BYTES).coerceAtLeast(0) / 2).toLong() * 1000 / 16_000
}
