package com.inkwell.notes.speakers.logic

import com.inkwell.notes.core.model.Person
import com.inkwell.notes.core.model.RecordingSpeaker
import com.inkwell.notes.core.model.SpeakerTurn
import com.inkwell.notes.core.storage.db.PersonEntity
import com.inkwell.notes.core.storage.db.RecordingSpeakerEntity
import com.inkwell.notes.core.storage.db.SpeakerTurnEntity
import com.inkwell.notes.core.storage.db.TranscriptSegmentEntity
import com.inkwell.notes.speakers.DiarizationResult

/** Mapping and bookkeeping between stored speaker rows and the model. Pure. */
object SpeakerRecords {
    /** Engine of a voice the user split off by hand: it has no voiceprint of its own. */
    const val MANUAL_ENGINE = "manual"

    fun person(e: PersonEntity, recordingCount: Int = 0) =
        Person(e.id, e.name, e.isMe, e.colorIndex, e.createdAt, e.updatedAt, e.voiceSamples, recordingCount)

    fun turn(e: SpeakerTurnEntity) = SpeakerTurn(e.label, e.startMs, e.endMs)

    fun turnEntities(recordingId: String, turns: List<SpeakerTurn>) =
        turns.map { SpeakerTurnEntity(recordingId = recordingId, label = it.label, startMs = it.startMs, endMs = it.endMs) }

    /** Label to display name for one recording. */
    fun names(rows: List<RecordingSpeakerEntity>, people: Map<String, PersonEntity>): Map<String, String> =
        rows.associate { it.label to SpeakerNames.display(it.label, it.personId?.let { id -> people[id]?.name }) }

    fun recordingSpeakers(rows: List<RecordingSpeakerEntity>, people: Map<String, PersonEntity>): List<RecordingSpeaker> {
        val sorted = rows.sortedWith(compareBy<RecordingSpeakerEntity> { SpeakerNames.number(it.label) ?: Int.MAX_VALUE }.thenBy { it.label })
        val colors = SpeakerColors.forRecording(sorted.map { it.label }, sorted.associate { it.label to it.personId?.let { id -> people[id]?.colorIndex } })
        return sorted.map { r ->
            val p = r.personId?.let { people[it] }
            RecordingSpeaker(
                recordingId = r.recordingId,
                label = r.label,
                personId = p?.id,
                displayName = SpeakerNames.display(r.label, p?.name),
                confidence = r.confidence,
                confirmed = r.confirmed,
                speechMs = r.speechMs,
                sampleStartMs = r.sampleStartMs,
                sampleEndMs = r.sampleEndMs,
                colorIndex = colors[r.label] ?: 0,
            )
        }
    }

    // ------------------------------------------------------------------ transcript

    /**
     * The transcript with voice labels and names, or null when nothing changes. With
     * [keepExistingLabels] and blocks that already carry labels (a cloud engine labelled them)
     * only the names are refreshed; otherwise blocks are aligned with [turns] (long blocks may
     * be split; each piece keeps its block's source). Without turns nothing changes.
     */
    fun relabel(
        segments: List<TranscriptSegmentEntity>,
        turns: List<SpeakerTurn>,
        names: Map<String, String>,
        keepExistingLabels: Boolean,
    ): List<TranscriptSegmentEntity>? {
        if (segments.isEmpty()) return null
        val sorted = segments.sortedBy { it.startMs }
        val next = if (keepExistingLabels && sorted.any { it.speakerLabel != null }) {
            sorted.map { e -> e.speakerLabel?.let { e.copy(speaker = names[it] ?: SpeakerNames.unnamed(it)) } ?: e }
        } else {
            if (turns.isEmpty()) return null
            val ordered = turns.sortedBy { it.startMs }
            sorted.flatMap { e ->
                TurnAligner.align(listOf(e.toModel()), ordered).map { s ->
                    TranscriptSegmentEntity(
                        recordingId = e.recordingId,
                        startMs = s.startMs,
                        endMs = s.endMs,
                        text = s.text,
                        speaker = s.speakerLabel?.let { names[it] ?: SpeakerNames.unnamed(it) },
                        source = e.source,
                        speakerLabel = s.speakerLabel,
                    )
                }
            }
        }
        val distinct = distinctStarts(next)
        return if (sameContent(sorted, distinct)) null else distinct.map { it.copy(rowId = 0) }
    }

    /**
     * Splitting can round two pieces onto one start; blocks are addressed by start, so keep them
     * strictly increasing in reading order (the order of [rows] is kept as is).
     */
    private fun distinctStarts(rows: List<TranscriptSegmentEntity>): List<TranscriptSegmentEntity> {
        var prev = Long.MIN_VALUE
        return rows.map { r ->
            val start = if (prev != Long.MIN_VALUE && r.startMs <= prev) prev + 1 else r.startMs
            prev = start
            if (start == r.startMs) r else r.copy(startMs = start, endMs = maxOf(r.endMs, start))
        }
    }

    private fun sameContent(a: List<TranscriptSegmentEntity>, b: List<TranscriptSegmentEntity>): Boolean =
        a.size == b.size && a.indices.all { i ->
            val x = a[i]
            val y = b[i]
            x.startMs == y.startMs && x.endMs == y.endMs && x.text == y.text && x.speaker == y.speaker &&
                x.speakerLabel == y.speakerLabel && x.source == y.source
        }

    // ------------------------------------------------------------------ results and edits

    data class Assignment(val personId: String?, val confidence: Float?, val confirmed: Boolean)

    /**
     * Who the voices of a new [result] are, before voiceprint matching. The user's earlier
     * decisions win: a voice that continues a named or dismissed voice of the previous analysis
     * keeps it; then the engine's own recognition (cloud references). [knownPeople] guards
     * against people deleted meanwhile.
     */
    fun carryOver(
        result: DiarizationResult,
        oldRows: List<RecordingSpeakerEntity>,
        oldTurns: List<SpeakerTurn>,
        knownPeople: Set<String>,
    ): Map<String, Assignment> {
        val map = LabelCarryOver.map(oldTurns, result.turns)
        val old = oldRows.associateBy { it.label }
        val out = HashMap<String, Assignment>()
        val usedPeople = HashSet<String>()
        // User decisions first, then the engine's recognitions for the rest.
        for (s in result.speakers) {
            val prev = map[s.label]?.let { old[it] } ?: continue
            if (!prev.confirmed) continue
            val pid = prev.personId?.takeIf { it in knownPeople }
            if (pid != null && !usedPeople.add(pid)) continue
            out[s.label] = Assignment(pid, prev.confidence.takeIf { pid != null }, true)
        }
        for (s in result.speakers) {
            if (s.label in out) continue
            val pid = s.personId?.takeIf { it in knownPeople } ?: continue
            if (!usedPeople.add(pid)) continue
            out[s.label] = Assignment(pid, s.confidence?.coerceIn(0f, 1f), false)
        }
        return out
    }

    /** New speaker rows for [result] with [assignments]; turn labels without a detected speaker get a row too. */
    fun rowsFor(result: DiarizationResult, turns: List<SpeakerTurn>, assignments: Map<String, Assignment>, totalMs: Long): List<RecordingSpeakerEntity> {
        val speech = TurnPostProcessor.speechByLabel(turns)
        val detected = result.speakers.associateBy { it.label }
        val labels = (result.speakers.map { it.label } + turns.map { it.label }).distinct()
        return labels.map { label ->
            val s = detected[label]
            val a = assignments[label]
            val window = s?.let { SampleWindows.Window(it.sampleStartMs, it.sampleEndMs) }
                ?.takeIf { it.durationMs in SampleWindows.MIN_MS..SampleWindows.MAX_MS }
                ?: SampleWindows.pick(turns, label, totalMs)
            RecordingSpeakerEntity(
                recordingId = result.recordingId,
                label = label,
                personId = a?.personId,
                confidence = a?.confidence,
                confirmed = a?.confirmed ?: false,
                speechMs = speech[label] ?: s?.speechMs ?: 0L,
                sampleStartMs = window?.startMs ?: 0L,
                sampleEndMs = window?.endMs ?: 0L,
                engine = result.engine,
            )
        }
    }

    data class Refresh(val upserts: List<RecordingSpeakerEntity>, val deletes: List<String>)

    /**
     * Speaker rows after the turns changed (a correction): speech recomputed, sample windows
     * re-picked where they no longer hold the voice, and voices left with neither turns nor
     * transcript blocks removed.
     */
    fun refresh(rows: List<RecordingSpeakerEntity>, turns: List<SpeakerTurn>, transcriptLabels: Set<String>, totalMs: Long): Refresh {
        val speech = TurnPostProcessor.speechByLabel(turns)
        val upserts = ArrayList<RecordingSpeakerEntity>()
        val deletes = ArrayList<String>()
        for (r in rows) {
            val ms = speech[r.label]
            if (ms == null && r.label !in transcriptLabels) {
                deletes += r.label
                continue
            }
            val w = SampleWindows.Window(r.sampleStartMs, r.sampleEndMs)
            val holds = w.durationMs in SampleWindows.MIN_MS..SampleWindows.MAX_MS && SampleWindows.speechIn(turns, r.label, w) * 2 >= w.durationMs
            val window = if (holds) w else SampleWindows.pick(turns, r.label, totalMs) ?: w
            val next = r.copy(speechMs = ms ?: 0L, sampleStartMs = window.startMs, sampleEndMs = window.endMs)
            if (next != r) upserts += next
        }
        return Refresh(upserts, deletes)
    }

    /** The row of [into] after [from] was merged into it (same voice). */
    fun merged(from: RecordingSpeakerEntity, into: RecordingSpeakerEntity): RecordingSpeakerEntity {
        val personId = into.personId ?: from.personId
        val confirmed = if (into.personId != null) into.confirmed else from.confirmed || into.confirmed
        return into.copy(
            personId = personId,
            confirmed = confirmed,
            confidence = if (into.personId != null) into.confidence else from.confidence,
            engine = if (into.engine == MANUAL_ENGINE) from.engine else into.engine,
        )
    }
}
