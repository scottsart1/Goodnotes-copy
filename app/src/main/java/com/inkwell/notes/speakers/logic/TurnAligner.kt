package com.inkwell.notes.speakers.logic

import com.inkwell.notes.core.model.SpeakerTurn
import com.inkwell.notes.core.model.TranscriptSegment
import kotlin.math.abs

/**
 * Labels transcript blocks with the voices of a recording's turns. Pure.
 *
 * A block takes the label it overlaps most; a block between turns takes the nearest turn
 * within [NEAREST_MS]. A long block that spans a change of voice (gpt-4o-transcribe returns one
 * block per two-minute file) is split at sentence boundaries, else clause boundaries, else
 * word boundaries, nearest to each change; piece times are proportional to character
 * position. Every character of the text ends up in exactly one piece: splitting only moves
 * whitespace at the cuts.
 */
object TurnAligner {
    const val NEAREST_MS = 1_500L

    /** Blocks shorter than this are never split. */
    const val MIN_SPLIT_MS = 4_000L

    /** A voice must hold the floor this long inside a block to get its own piece. */
    const val MIN_RUN_MS = 1_200L

    private const val SENTENCE_ASCII = ".!?…"
    private const val SENTENCE_WIDE = "。！？؟"
    private const val CLAUSE_ASCII = ",;:–—"
    private const val CLAUSE_WIDE = "，；：、"
    private const val CLOSERS = "\"')]}”’»」』）"

    fun align(segments: List<TranscriptSegment>, turns: List<SpeakerTurn>): List<TranscriptSegment> {
        val sorted = turns.filter { it.endMs > it.startMs }.sortedBy { it.startMs }
        if (sorted.isEmpty()) return segments
        return segments.flatMap { alignOne(it, sorted) }
    }

    /** The label of [s] alone, without splitting (null when no turn is near). */
    fun labelOf(s: TranscriptSegment, turns: List<SpeakerTurn>): String? {
        val start = s.startMs
        val end = maxOf(s.endMs, s.startMs)
        val overlaps = overlapByLabel(turns, start, end)
        if (overlaps.isNotEmpty()) return overlaps.maxWith(compareBy<Map.Entry<String, Long>> { it.value }.thenByDescending { it.key }).key
        return nearest(turns, start, end)
    }

    private fun alignOne(s: TranscriptSegment, turns: List<SpeakerTurn>): List<TranscriptSegment> {
        val start = s.startMs
        val end = maxOf(s.endMs, s.startMs)
        val label = labelOf(s, turns)
        if (end - start < MIN_SPLIT_MS || s.text.isBlank()) return listOf(s.copy(speakerLabel = label))
        val runs = runsWithin(turns, start, end)
        if (runs.size < 2) return listOf(s.copy(speakerLabel = label))
        val pieces = split(s, runs, turns)
        return if (pieces.size < 2) listOf(s.copy(speakerLabel = label)) else pieces
    }

    private data class Run(val label: String, var startMs: Long, var endMs: Long) {
        val length: Long get() = endMs - startMs
    }

    /** Who holds the floor inside [start, end): consecutive runs of one label, short runs absorbed. */
    private fun runsWithin(turns: List<SpeakerTurn>, start: Long, end: Long): List<Run> {
        val clipped = turns.filter { it.endMs > start && it.startMs < end }
            .map { Run(it.label, maxOf(it.startMs, start), minOf(it.endMs, end)) }
        val runs = ArrayList<Run>()
        for (r in clipped) {
            val last = runs.lastOrNull()
            if (last != null && last.label == r.label) last.endMs = maxOf(last.endMs, r.endMs) else runs += r.copy()
        }
        while (runs.size > 1) {
            val i = runs.indices.filter { runs[it].length < MIN_RUN_MS }.minByOrNull { runs[it].length } ?: break
            val left = runs.getOrNull(i - 1)
            val right = runs.getOrNull(i + 1)
            val into = when {
                left == null -> right!!
                right == null -> left
                left.label == right.label -> left
                left.length >= right.length -> left
                else -> right
            }
            if (into === left) left.endMs = maxOf(left.endMs, runs[i].endMs) else right!!.startMs = minOf(right.startMs, runs[i].startMs)
            runs.removeAt(i)
            // Join neighbours that now carry the same label.
            var k = 0
            while (k < runs.size - 1) {
                if (runs[k].label == runs[k + 1].label) {
                    runs[k].endMs = maxOf(runs[k].endMs, runs[k + 1].endMs)
                    runs.removeAt(k + 1)
                } else {
                    k++
                }
            }
        }
        return runs
    }

    private fun split(s: TranscriptSegment, runs: List<Run>, turns: List<SpeakerTurn>): List<TranscriptSegment> {
        val text = s.text
        val len = text.length
        val start = s.startMs
        val dur = maxOf(s.endMs, s.startMs) - start
        fun timeOf(c: Int): Long = start + (dur.toDouble() * c / len).toLong()

        val (sentence, clause, word) = cutCandidates(text)
        val sentenceTol = maxOf(2_500L, dur * 8 / 100)
        val clauseTol = maxOf(1_500L, dur * 4 / 100)
        val cuts = sortedSetOf<Int>()
        for (k in 0 until runs.size - 1) {
            // A change of voice halfway through the silence between the two runs.
            val change = (runs[k].endMs + runs[k + 1].startMs) / 2
            val pick = nearestCut(sentence, change, sentenceTol, ::timeOf)
                ?: nearestCut(clause, change, clauseTol, ::timeOf)
                ?: nearestCut(word, change, Long.MAX_VALUE, ::timeOf)
                ?: charCut(text, ((change - start).toDouble() / dur.coerceAtLeast(1) * len).toInt())
            if (pick != null && pick in 1 until len) cuts += pick
        }
        if (cuts.isEmpty()) return emptyList()

        // Pieces over [from, to) character ranges, labelled by overlap with the turns.
        data class Piece(val from: Int, val to: Int, val label: String?)
        val bounds = listOf(0) + cuts.toList() + listOf(len)
        val raw = ArrayList<Piece>()
        for (i in 0 until bounds.size - 1) {
            val a = bounds[i]
            val b = bounds[i + 1]
            if (text.substring(a, b).isBlank()) continue
            val ta = if (a == 0) start else timeOf(a)
            val tb = if (b == len) start + dur else timeOf(b)
            val ov = overlapByLabel(turns, ta, tb)
            val label = if (ov.isNotEmpty()) {
                ov.maxWith(compareBy<Map.Entry<String, Long>> { it.value }.thenByDescending { it.key }).key
            } else {
                val mid = (ta + tb) / 2
                runs.firstOrNull { mid < it.endMs }?.label ?: runs.last().label
            }
            raw += Piece(a, b, label)
        }
        // Blank stretches dropped above are re-attached by extending the previous piece.
        val pieces = ArrayList<Piece>()
        for (p in raw) {
            val last = pieces.lastOrNull()
            if (last != null && (last.label == p.label || timeOf(p.from) <= timeOf(last.from))) {
                pieces[pieces.lastIndex] = last.copy(to = p.to)
            } else {
                pieces += if (last == null) p.copy(from = 0) else p
            }
        }
        if (pieces.isEmpty()) return emptyList()
        // Cover the whole text: the first piece starts at 0, each ends where the next begins.
        val covered = pieces.mapIndexed { i, p -> p.copy(from = if (i == 0) 0 else p.from, to = pieces.getOrNull(i + 1)?.from ?: len) }
        return covered.mapIndexed { i, p ->
            TranscriptSegment(
                startMs = if (i == 0) start else timeOf(p.from),
                endMs = if (i == covered.lastIndex) start + dur else timeOf(p.to),
                text = text.substring(p.from, p.to).trim(),
                speaker = s.speaker,
                speakerLabel = p.label,
            )
        }
    }

    private fun nearestCut(cuts: List<Int>, change: Long, tol: Long, timeOf: (Int) -> Long): Int? =
        cuts.minByOrNull { abs(timeOf(it) - change) }?.takeIf { abs(timeOf(it) - change) <= tol }

    /** A cut at character [at] that never splits a surrogate pair; null when impossible. */
    private fun charCut(text: String, at: Int): Int? {
        // One character (a lone "好" or "♪" over a long stretch) cannot be cut in two.
        if (text.length < 2) return null
        var c = at.coerceIn(1, text.length - 1)
        if (Character.isLowSurrogate(text[c]) && Character.isHighSurrogate(text[c - 1])) c++
        return c.takeIf { it in 1 until text.length && text.substring(0, it).isNotBlank() && text.substring(it).isNotBlank() }
    }

    /** Character positions where a new piece may begin: after sentences, clauses and words. */
    internal fun cutCandidates(text: String): Triple<List<Int>, List<Int>, List<Int>> {
        val sentence = ArrayList<Int>()
        val clause = ArrayList<Int>()
        val word = ArrayList<Int>()
        val len = text.length
        fun skipClosers(i: Int): Int {
            var j = i
            while (j < len && text[j] in CLOSERS) j++
            return j
        }
        fun skipSpace(i: Int): Int {
            var j = i
            while (j < len && text[j].isWhitespace()) j++
            return j
        }
        for (i in 0 until len) {
            val ch = text[i]
            when {
                ch in SENTENCE_ASCII -> {
                    val after = skipClosers(i + 1)
                    if (after < len && text[after].isWhitespace()) skipSpace(after).takeIf { it < len }?.let(sentence::add)
                }
                ch in SENTENCE_WIDE -> skipSpace(skipClosers(i + 1)).takeIf { it < len }?.let(sentence::add)
                ch in CLAUSE_ASCII -> {
                    val after = i + 1
                    if (after < len && text[after].isWhitespace()) skipSpace(after).takeIf { it < len }?.let(clause::add)
                }
                ch in CLAUSE_WIDE -> skipSpace(i + 1).takeIf { it < len }?.let(clause::add)
                ch.isWhitespace() && i > 0 && !text[i - 1].isWhitespace() -> skipSpace(i).takeIf { it < len }?.let(word::add)
            }
        }
        return Triple(sentence.distinct(), clause.distinct(), word.distinct())
    }

    private fun overlapByLabel(turns: List<SpeakerTurn>, start: Long, end: Long): Map<String, Long> {
        val m = HashMap<String, Long>()
        for (t in turns) {
            val ov = minOf(t.endMs, end) - maxOf(t.startMs, start)
            if (ov > 0) m[t.label] = (m[t.label] ?: 0L) + ov
        }
        return m
    }

    private fun nearest(turns: List<SpeakerTurn>, start: Long, end: Long): String? {
        var best: SpeakerTurn? = null
        var bestGap = Long.MAX_VALUE
        for (t in turns) {
            val gap = when {
                t.endMs <= start -> start - t.endMs
                t.startMs >= end -> t.startMs - end
                else -> 0L
            }
            if (gap < bestGap) {
                bestGap = gap
                best = t
            }
        }
        return best?.takeIf { bestGap <= NEAREST_MS }?.label
    }
}
