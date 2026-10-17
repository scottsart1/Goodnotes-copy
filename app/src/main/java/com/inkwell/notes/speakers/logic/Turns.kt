package com.inkwell.notes.speakers.logic

import com.inkwell.notes.core.model.SpeakerTurn

/** Clean-up of raw diarization turns into the stored "who spoke when". Pure. */
object TurnPostProcessor {
    /** Same-voice turns closer than this are one turn. */
    const val MERGE_GAP_MS = 500L

    /** Turns shorter than this are dropped as noise. */
    const val MIN_ISLAND_MS = 300L

    data class Result(
        /** Sorted, non-overlapping, labelled "S1", "S2", ... by first appearance. */
        val turns: List<SpeakerTurn>,
        /** Input label to output label, for the labels that survived. */
        val labels: Map<String, String>,
    )

    /**
     * Resolves overlaps (the most recently started voice holds the floor until it stops, so an
     * interjection keeps its words), merges same-voice turns separated by short gaps, drops
     * islands shorter than [MIN_ISLAND_MS], and relabels by first appearance.
     */
    fun process(raw: List<SpeakerTurn>, relabel: Boolean = true): Result {
        var turns = flatten(raw)
        turns = mergeAdjacent(turns, MERGE_GAP_MS)
        // Dropping an island can make its neighbours adjacent; repeat until stable.
        while (true) {
            val kept = turns.filter { it.endMs - it.startMs >= MIN_ISLAND_MS }
            val merged = mergeAdjacent(kept, MERGE_GAP_MS)
            if (merged == turns) break
            turns = merged
        }
        if (!relabel) return Result(turns, turns.map { it.label }.distinct().associateWith { it })
        val map = LinkedHashMap<String, String>()
        for (t in turns) if (t.label !in map) map[t.label] = "S${map.size + 1}"
        return Result(turns.map { it.copy(label = map.getValue(it.label)) }, map)
    }

    /** Sorted, non-overlapping turns; where turns overlap the later-starting one wins. */
    fun flatten(raw: List<SpeakerTurn>): List<SpeakerTurn> {
        val valid = raw.filter { it.endMs > it.startMs && it.startMs >= 0 }
            .sortedWith(compareBy<SpeakerTurn> { it.startMs }.thenBy { it.endMs })
        if (valid.isEmpty()) return emptyList()
        val bounds = (valid.map { it.startMs } + valid.map { it.endMs }).distinct().sorted()
        val out = ArrayList<SpeakerTurn>()
        for (i in 0 until bounds.size - 1) {
            val a = bounds[i]
            val b = bounds[i + 1]
            // Active turns over [a, b): the latest start wins (ties: the shorter turn).
            var best: SpeakerTurn? = null
            for (t in valid) {
                if (t.startMs > a) break
                if (t.endMs <= a) continue
                val cur = best
                if (cur == null || t.startMs > cur.startMs || (t.startMs == cur.startMs && t.endMs < cur.endMs)) best = t
            }
            val w = best ?: continue
            val last = out.lastOrNull()
            if (last != null && last.label == w.label && last.endMs == a) {
                out[out.lastIndex] = last.copy(endMs = b)
            } else {
                out += SpeakerTurn(w.label, a, b)
            }
        }
        return out
    }

    /** Joins consecutive turns of the same label separated by less than [gapMs]. */
    fun mergeAdjacent(turns: List<SpeakerTurn>, gapMs: Long): List<SpeakerTurn> {
        val out = ArrayList<SpeakerTurn>(turns.size)
        for (t in turns) {
            val last = out.lastOrNull()
            if (last != null && last.label == t.label && t.startMs - last.endMs < gapMs) {
                out[out.lastIndex] = last.copy(endMs = maxOf(last.endMs, t.endMs))
            } else {
                out += t
            }
        }
        return out
    }

    /** Total speech per label. */
    fun speechByLabel(turns: List<SpeakerTurn>): Map<String, Long> {
        val m = LinkedHashMap<String, Long>()
        for (t in turns) m[t.label] = (m[t.label] ?: 0L) + (t.endMs - t.startMs).coerceAtLeast(0)
        return m
    }
}

/** User corrections to stored turns. Pure. */
object TurnEditor {
    /**
     * Gives [startMs, endMs) to [label]: overlapping parts of other turns are cut away and the
     * range becomes one turn of [label], merged with touching turns of the same label. The
     * user's correction is the ground truth, so nothing is dropped as an island here.
     */
    fun assignRange(turns: List<SpeakerTurn>, startMs: Long, endMs: Long, label: String): List<SpeakerTurn> {
        if (endMs <= startMs) return turns
        val out = ArrayList<SpeakerTurn>()
        for (t in turns) {
            if (t.endMs <= startMs || t.startMs >= endMs) {
                out += t
                continue
            }
            if (t.startMs < startMs) out += t.copy(endMs = startMs)
            if (t.endMs > endMs) out += t.copy(startMs = endMs)
        }
        out += SpeakerTurn(label, startMs, endMs)
        out.sortWith(compareBy<SpeakerTurn> { it.startMs }.thenBy { it.endMs })
        return TurnPostProcessor.mergeAdjacent(out, 1)
    }

    /** Renames [from] to [into] and joins the turns that become adjacent. */
    fun merge(turns: List<SpeakerTurn>, from: String, into: String): List<SpeakerTurn> {
        val relabelled = turns.map { if (it.label == from) it.copy(label = into) else it }
        return TurnPostProcessor.mergeAdjacent(TurnPostProcessor.flatten(relabelled), TurnPostProcessor.MERGE_GAP_MS)
    }
}

/** Picks the representative clip of a voice for "play sample" and reference clips. Pure. */
object SampleWindows {
    const val MIN_MS = 2_000L
    const val MAX_MS = 10_000L
    const val TARGET_MS = 8_000L

    /** Kept clear of each end of a turn, where the previous or next voice may bleed in. */
    const val MARGIN_MS = 250L

    data class Window(val startMs: Long, val endMs: Long) {
        val durationMs: Long get() = endMs - startMs
    }

    /**
     * A 2-10 s window (about [targetMs]) from the longest turn of [label], clear of the turn's
     * edges when the turn is long enough; padded around a short turn (clamped to
     * [0, totalMs] when [totalMs] > 0). Null when [label] has no turns.
     */
    fun pick(turns: List<SpeakerTurn>, label: String, totalMs: Long = 0, targetMs: Long = TARGET_MS): Window? {
        val longest = turns.filter { it.label == label && it.endMs > it.startMs }
            .maxWithOrNull(compareBy<SpeakerTurn> { it.endMs - it.startMs }.thenByDescending { it.startMs })
            ?: return null
        val target = targetMs.coerceIn(MIN_MS, MAX_MS)
        val clean = (longest.endMs - longest.startMs) - 2 * MARGIN_MS
        val (start, end) = if (clean >= MIN_MS) {
            val w = minOf(clean, target)
            val mid = (longest.startMs + longest.endMs) / 2
            (mid - w / 2) to (mid - w / 2 + w)
        } else {
            // Too short for a clean 2 s: centre a 2 s window on the turn.
            val mid = (longest.startMs + longest.endMs) / 2
            (mid - MIN_MS / 2) to (mid + MIN_MS / 2)
        }
        return clamp(Window(start, end), totalMs)
    }

    /** Speech of [label] inside [w]. */
    fun speechIn(turns: List<SpeakerTurn>, label: String, w: Window): Long =
        turns.filter { it.label == label }.sumOf { (minOf(it.endMs, w.endMs) - maxOf(it.startMs, w.startMs)).coerceAtLeast(0) }

    private fun clamp(w: Window, totalMs: Long): Window {
        if (totalMs <= 0) return if (w.startMs < 0) Window(0, w.durationMs) else w
        val len = minOf(w.durationMs, totalMs)
        val start = w.startMs.coerceIn(0, (totalMs - len).coerceAtLeast(0))
        return Window(start, start + len)
    }
}

/** Which new label continues which old one when a recording is analysed again. Pure. */
object LabelCarryOver {
    /**
     * Pairs each new label with the old label it shares the most speech with (one-to-one,
     * largest overlap first). A pair needs at least half of the new voice's speech to overlap.
     */
    fun map(oldTurns: List<SpeakerTurn>, newTurns: List<SpeakerTurn>): Map<String, String> {
        if (oldTurns.isEmpty() || newTurns.isEmpty()) return emptyMap()
        val overlap = HashMap<Pair<String, String>, Long>()
        for (n in newTurns) for (o in oldTurns) {
            val ov = minOf(n.endMs, o.endMs) - maxOf(n.startMs, o.startMs)
            if (ov > 0) overlap.merge(n.label to o.label, ov, Long::plus)
        }
        val speech = TurnPostProcessor.speechByLabel(newTurns)
        val out = HashMap<String, String>()
        val usedOld = HashSet<String>()
        for ((pair, ov) in overlap.entries.sortedWith(compareByDescending<Map.Entry<Pair<String, String>, Long>> { it.value }.thenBy { it.key.first }.thenBy { it.key.second })) {
            val (n, o) = pair
            if (n in out || o in usedOld) continue
            if (ov * 2 < (speech[n] ?: 0L)) continue
            out[n] = o
            usedOld += o
        }
        return out
    }
}
