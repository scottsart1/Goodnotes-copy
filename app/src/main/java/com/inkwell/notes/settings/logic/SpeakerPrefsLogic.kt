package com.inkwell.notes.settings.logic

import com.inkwell.notes.core.model.SpeakerEngine
import com.inkwell.notes.core.model.SpeakerPrefs
import kotlin.math.abs

/** How readily a voice is labelled with a known person, as the cosine threshold the matcher uses. */
internal enum class MatchSensitivity(val threshold: Float) {
    /** Fewer automatic labels, almost never the wrong person. */
    STRICT(0.65f),
    BALANCED(0.55f),
    /** More automatic labels, more to correct. */
    LOOSE(0.45f);

    companion object {
        /** The choice closest to a stored [threshold] (any value, e.g. one written by an older version). */
        fun of(threshold: Float): MatchSensitivity =
            if (threshold.isNaN()) BALANCED else entries.minBy { abs(it.threshold - threshold) }
    }
}

/** Choices and clamping for the "Voices & speakers" settings. Pure. */
internal object SpeakerPrefsLogic {
    const val MAX_EXPECTED = 8

    /** 0 = detect automatically, then 2..8 people. */
    val expectedSpeakerChoices: List<Int> = listOf(0) + (2..MAX_EXPECTED)

    /** The stored expected-speaker count as one of [expectedSpeakerChoices] (below 2 reads as automatic). */
    fun expected(prefs: SpeakerPrefs): Int = prefs.expectedSpeakers.let { if (it < 2) 0 else it.coerceAtMost(MAX_EXPECTED) }

    /**
     * Whether a voice sample can be recorded for a person: voiceprints are stored only while
     * voices are remembered, and are pointless with speakers off. The on-device models make the
     * voiceprint whichever engine labels recordings, so this also holds with engine OPENAI.
     */
    fun canEnroll(prefs: SpeakerPrefs): Boolean = prefs.rememberVoices && prefs.engine != SpeakerEngine.OFF

    /** The "Voice models" row is shown whenever the models are used: engine DEVICE, or any enrollment. */
    fun showModels(prefs: SpeakerPrefs): Boolean = prefs.engine == SpeakerEngine.DEVICE || canEnroll(prefs)
}
