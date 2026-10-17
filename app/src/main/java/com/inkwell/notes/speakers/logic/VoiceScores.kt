package com.inkwell.notes.speakers.logic

/**
 * Thresholds for the WeSpeaker ResNet34-LM embeddings (256-d), measured offline with the
 * pinned models on four 16 kHz test conversations (2 and 4 speakers, 16-57 s, English and
 * Chinese), sherpa-onnx 1.13.8, embeddings averaged over turns >= 1 s weighted by duration:
 *
 * - Same voice, embedding of one half of a recording vs the other half: 0.69-0.93
 *   (median 0.92; the low end is a voice with only 3 s in one half).
 * - Different voices in the same recording (same room and microphone): 0.73-0.85
 *   (whole-recording centroids 0.78 and 0.82 for the two English conversations). The
 *   Chinese four-speaker file is harder: 0.80-0.90 between its voices.
 * - Different people in different recordings: 0.25-0.81 (most below 0.75).
 *
 * This model scores everyone high on the raw cosine scale, so a raw cosine is a poor
 * "similarity 0..1" for people. [similarity] stretches the useful band (0.6..1.0 raw) onto
 * 0..1: the default SpeakerPrefs.matchThreshold of 0.55 then means a raw cosine of 0.82, which
 * accepted every same-voice pair with enough speech above and rejected every cross-recording
 * pair measured.
 *
 * Per-window clustering inside sherpa (complete linkage on cosine distance) was swept over
 * 0.3/0.4/0.5: 0.5 merged the two voices of one English test file, 0.3 split every file into
 * 4-8 voices; 0.4 keeps voices apart and lets [LINK_THRESHOLD] join the over-split ones.
 *
 * End to end (this pipeline, 1-2 windows per file, link threshold swept 0.80-0.88): voices
 * the window clustering split off linked back at 0.80-0.85 (a 6 s piece at 0.84-0.86, a 3 s
 * piece at 0.80-0.82); at 0.84 both two-speaker English conversations came out as two
 * voices in every window layout but one (a 3 s piece stayed apart: under the 4 s needed to
 * be asked about). Below 0.84 the Chinese four-speaker file collapsed into one voice.
 * Recognition with the default threshold: second halves matched their own first-half voices
 * at 0.71-0.88 (calibrated), every cross-recording score was at most 0.42.
 * Pure.
 */
object VoiceScores {
    /** Distance threshold for sherpa's per-window FastClustering (cosine distance, complete linkage). */
    const val WINDOW_CLUSTER_THRESHOLD = 0.4f

    /** Raw cosine at or above which two window-local voices are the same voice. */
    const val LINK_THRESHOLD = 0.84f

    /** Raw cosine at or above which a new voiceprint sample adds nothing over a stored one. */
    const val DUPLICATE_SAMPLE = 0.97f

    /** Raw cosine that maps to similarity 0. */
    const val RAW_FLOOR = 0.6f

    /** Raw cosine (-1..1) to the calibrated similarity (0..1) people are matched with. */
    fun similarity(rawCosine: Float): Float =
        if (!rawCosine.isFinite()) 0f else ((rawCosine - RAW_FLOOR) / (1f - RAW_FLOOR)).coerceIn(0f, 1f)

    /** Inverse of [similarity] for thresholds (similarity 0..1 to raw cosine). */
    fun rawFor(similarity: Float): Float = RAW_FLOOR + similarity.coerceIn(0f, 1f) * (1f - RAW_FLOOR)
}
