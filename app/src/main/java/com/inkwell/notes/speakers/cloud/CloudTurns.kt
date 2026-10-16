package com.inkwell.notes.speakers.cloud

import com.inkwell.notes.core.model.SpeakerTurn
import com.inkwell.notes.core.model.TranscriptSegment
import com.inkwell.notes.speakers.DetectedSpeaker
import com.inkwell.notes.speakers.DiarizationResult

/** Who spoke when, worked out from speaker-labelled transcript blocks. */
object CloudTurns {
    /** Blocks of one voice closer than this form one turn. */
    const val MERGE_GAP_MS = 1_500L
    /** Shortest stretch usable as a reference clip (the provider's minimum). */
    const val CLIP_MIN_MS = 2_000L
    /** Longest clip: the provider's maximum, and plenty for "play sample". */
    const val CLIP_MAX_MS = 10_000L

    /** Turns from labelled blocks: same-voice blocks merged across short gaps; sorted and non-overlapping. */
    fun turns(segments: List<TranscriptSegment>): List<SpeakerTurn> {
        val out = ArrayList<SpeakerTurn>()
        val labelled = segments.filter { it.speakerLabel != null && it.endMs > it.startMs }
            .sortedWith(compareBy<TranscriptSegment> { it.startMs }.thenBy { it.endMs })
        for (s in labelled) {
            val label = s.speakerLabel ?: continue
            val last = out.lastOrNull()
            if (last != null && last.label == label && s.startMs - last.endMs <= MERGE_GAP_MS) {
                if (s.endMs > last.endMs) out[out.lastIndex] = last.copy(endMs = s.endMs)
                continue
            }
            // Crosstalk: the later voice's turn starts where the earlier one ends.
            val start = if (last != null) maxOf(s.startMs, last.endMs) else s.startMs
            if (s.endMs <= start) continue
            out += SpeakerTurn(label, start, s.endMs)
        }
        return out
    }

    /** Speech of [label] in ms: the sum of its blocks. */
    fun speechMs(segments: List<TranscriptSegment>, label: String): Long =
        segments.filter { it.speakerLabel == label }.sumOf { (it.endMs - it.startMs).coerceAtLeast(0) }

    /**
     * Where [label] spoke longest, at most [CLIP_MAX_MS] around the middle of that turn (usually
     * 4-10 s; a voice that only spoke briefly still gets its longest stretch down to the
     * provider's minimum). Null when no turn reaches [CLIP_MIN_MS]: a shorter clip is refused.
     */
    fun clipWindow(turns: List<SpeakerTurn>, label: String): Pair<Long, Long>? {
        val best = longest(turns, label) ?: return null
        val len = best.endMs - best.startMs
        if (len < CLIP_MIN_MS) return null
        if (len <= CLIP_MAX_MS) return best.startMs to best.endMs
        val start = best.startMs + (len - CLIP_MAX_MS) / 2
        return start to start + CLIP_MAX_MS
    }

    /**
     * A 2-10 s sample of [label] for "play sample": its clip window, otherwise its longest
     * stretch (or first block) widened to 2 s inside the recording.
     */
    fun sampleWindow(turns: List<SpeakerTurn>, segments: List<TranscriptSegment>, label: String, totalMs: Long): Pair<Long, Long> {
        clipWindow(turns, label)?.let { return it }
        val anchor = longest(turns, label)?.let { (it.startMs + it.endMs) / 2 }
            ?: segments.filter { it.speakerLabel == label }.minByOrNull { it.startMs }?.let { (it.startMs + it.endMs) / 2 }
            ?: return 0L to 0L
        var start = (anchor - CLIP_MIN_MS / 2).coerceAtLeast(0)
        var end = start + CLIP_MIN_MS
        if (totalMs > 0 && end > totalMs) {
            end = totalMs
            start = (end - CLIP_MIN_MS).coerceAtLeast(0)
        }
        return start to end
    }

    /** [voices] with speech and clip windows recomputed from every labelled block so far. */
    fun refresh(voices: List<CloudVoice>, segments: List<TranscriptSegment>): List<CloudVoice> {
        val t = turns(segments)
        return voices.map { v ->
            val w = clipWindow(t, v.label)
            v.copy(speechMs = speechMs(segments, v.label), clipStartMs = w?.first, clipEndMs = w?.second)
        }
    }

    /**
     * The engine result handed to the speaker service: turns, and every voice that has blocks,
     * in label order, with its person link (no confidence or voiceprint: the cloud has none).
     */
    fun result(recordingId: String, segments: List<TranscriptSegment>, voices: List<CloudVoice>, totalMs: Long): DiarizationResult {
        val t = turns(segments)
        val present = segments.mapNotNull { it.speakerLabel }.toSet()
        val speakers = voices.filter { it.label in present }
            .sortedBy { CloudLabels.indexOf(it.label) ?: Int.MAX_VALUE }
            .map { v ->
                val (s, e) = sampleWindow(t, segments, v.label, totalMs)
                DetectedSpeaker(
                    label = v.label, personId = v.personId, confidence = null, centroid = null,
                    speechMs = speechMs(segments, v.label), sampleStartMs = s, sampleEndMs = e,
                )
            }
        return DiarizationResult(recordingId, CloudLabels.ENGINE, t, speakers)
    }

    private fun longest(turns: List<SpeakerTurn>, label: String): SpeakerTurn? =
        turns.filter { it.label == label }.maxByOrNull { it.endMs - it.startMs }
}
