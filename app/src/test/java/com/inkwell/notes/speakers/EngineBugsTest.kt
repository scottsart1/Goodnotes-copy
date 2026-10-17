package com.inkwell.notes.speakers

import androidx.work.WorkInfo
import androidx.work.workDataOf
import com.inkwell.notes.core.model.SpeakerTurn
import com.inkwell.notes.core.model.TranscriptSegment
import com.inkwell.notes.speakers.logic.SpeakerNames
import com.inkwell.notes.speakers.logic.SpeakerPhases
import com.inkwell.notes.speakers.logic.TurnAligner
import com.inkwell.notes.speakers.work.Diarizations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/** Regression tests for bugs found in the speaker engine. */
class EngineBugsTest {
    private fun info(state: WorkInfo.State, waiting: Boolean) =
        WorkInfo(UUID.randomUUID(), state, emptySet(), if (waiting) workDataOf(Diarizations.KEY_WAITING to true) else androidx.work.Data.EMPTY)

    @Test
    fun aJobThatOnlyWaitedForTheModelsIsNotDoneOnceTheWaitEnds() {
        val ready = SpeakerModelState.Ready
        // The worker found the models missing and ended with KEY_WAITING; the recording waits.
        assertTrue(Diarizations.waitingOf(info(WorkInfo.State.SUCCEEDED, waiting = true)))
        assertFalse(Diarizations.waitingOf(info(WorkInfo.State.SUCCEEDED, waiting = false)))
        assertFalse(Diarizations.waitingOf(info(WorkInfo.State.FAILED, waiting = true)))
        assertEquals(
            DiarizationPhase.WaitingForModels,
            SpeakerPhases.of(WorkInfo.State.SUCCEEDED, null, null, hasResult = false, waitingForModels = true, ready, jobWaiting = true),
        )
        // The user cancelled while waiting: nothing was analysed, so the recording is not "done"
        // (the panel would otherwise say no voices were found).
        assertEquals(
            DiarizationPhase.None,
            SpeakerPhases.of(WorkInfo.State.SUCCEEDED, null, null, hasResult = false, waitingForModels = false, ready, jobWaiting = true),
        )
        // An earlier result still counts; a job that really finished is done.
        assertEquals(
            DiarizationPhase.Done,
            SpeakerPhases.of(WorkInfo.State.SUCCEEDED, null, null, hasResult = true, waitingForModels = false, ready, jobWaiting = true),
        )
        assertEquals(
            DiarizationPhase.Done,
            SpeakerPhases.of(WorkInfo.State.SUCCEEDED, null, null, hasResult = false, waitingForModels = false, ready, jobWaiting = false),
        )
    }

    @Test
    fun oneCharacterBlockOverTwoVoicesIsLabelledNotCut() {
        // A single-character block ("好", "♪") longer than MIN_SPLIT_MS over a change of voice
        // used to throw from charCut (coerceIn(1, 0)) and fail the whole labelling pass.
        val turns = listOf(SpeakerTurn("S1", 0, 5_000), SpeakerTurn("S2", 5_000, 12_000))
        for (text in listOf("好", "♪", "😀", "ab")) {
            val out = TurnAligner.align(listOf(TranscriptSegment(0, 12_000, text)), turns)
            assertEquals(text, out.joinToString("") { it.text })
            assertTrue(out.all { it.text.isNotEmpty() })
            if (out.size == 1) assertEquals("S2", out.single().speakerLabel)
        }
    }

    @Test
    fun namesKeepJoinersButDropOtherInvisibleCharacters() {
        // Persian "Alireza": a zero-width non-joiner keeps the two parts from joining.
        assertEquals("علی‌رضا", SpeakerNames.clean("علی‌رضا"))
        // An emoji ZWJ sequence stays one glyph.
        assertEquals("👩‍💻", SpeakerNames.clean("👩‍💻"))
        // Zero-width space, BOM and bidi marks are still removed; joiners never lead or trail.
        assertEquals("Mary Ann", SpeakerNames.clean("﻿ Mary​ ‏Ann‍"))
        assertEquals("Ann", SpeakerNames.clean("‍Ann ‌"))
        assertNull(SpeakerNames.clean("‌‍"))
        val long = "a".repeat(79) + "‍" + "b"
        assertEquals("a".repeat(79), SpeakerNames.clean(long))
    }
}
