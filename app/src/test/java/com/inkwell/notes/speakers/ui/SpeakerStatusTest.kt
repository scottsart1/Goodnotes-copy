package com.inkwell.notes.speakers.ui

import com.inkwell.notes.core.model.SpeakerEngine
import com.inkwell.notes.speakers.DiarizationPhase
import com.inkwell.notes.speakers.SpeakerModelState
import com.inkwell.notes.speakers.ui.logic.SpeakerStatus
import com.inkwell.notes.speakers.ui.logic.SpeakerStatusLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeakerStatusTest {
    private fun of(
        engine: SpeakerEngine = SpeakerEngine.DEVICE,
        phase: DiarizationPhase = DiarizationPhase.None,
        models: SpeakerModelState = SpeakerModelState.Ready,
        hasSpeakers: Boolean = false,
        hasAudio: Boolean = true,
        wifiOnly: Boolean = false,
    ) = SpeakerStatusLogic.of(engine, phase, models, hasSpeakers, hasAudio, wifiOnly)

    @Test
    fun engineOffShowsNothing() {
        assertEquals(SpeakerStatus.Hidden, of(engine = SpeakerEngine.OFF, phase = DiarizationPhase.Running(1, 2)))
        assertEquals(SpeakerStatus.Hidden, of(engine = SpeakerEngine.OFF))
    }

    @Test
    fun busyPhasesShowProgressAndCanBeCancelled() {
        val running = of(phase = DiarizationPhase.Running(3, 12))
        assertEquals(SpeakerStatus.Analysing(3, 12), running)
        assertTrue(running.cancellable)
        assertEquals(SpeakerStatus.Queued, of(phase = DiarizationPhase.Queued))
        assertEquals(
            SpeakerStatus.Downloading(12_000_000, 32_000_000),
            of(phase = DiarizationPhase.WaitingForModels, models = SpeakerModelState.Downloading(12_000_000, 32_000_000)),
        )
        assertEquals(
            SpeakerStatus.WaitingForModels(wifiOnly = true),
            of(phase = DiarizationPhase.WaitingForModels, models = SpeakerModelState.NotDownloaded, wifiOnly = true),
        )
        assertEquals(SpeakerStatus.ModelsFailed("offline"), of(phase = DiarizationPhase.WaitingForModels, models = SpeakerModelState.Failed("offline")))
        assertFalse(SpeakerStatus.ModelsFailed("x").cancellable)
    }

    @Test
    fun failuresOfferARetryOnlyOnTheDevice() {
        assertEquals(SpeakerStatus.Failed("boom", canRetry = true), of(phase = DiarizationPhase.Failed("boom")))
        assertEquals(SpeakerStatus.Failed(null, canRetry = true), of(phase = DiarizationPhase.Failed(" ")))
        assertEquals(SpeakerStatus.Failed(null, canRetry = false), of(engine = SpeakerEngine.OPENAI, phase = DiarizationPhase.Failed(null)))
        assertEquals(SpeakerStatus.Failed(null, canRetry = false), of(phase = DiarizationPhase.Failed(null), hasAudio = false))
    }

    @Test
    fun theOfferAppearsOnlyForDeviceRecordingsWithoutSpeakers() {
        assertEquals(SpeakerStatus.Offer(needsDownload = false), of())
        assertEquals(SpeakerStatus.Offer(needsDownload = true), of(models = SpeakerModelState.NotDownloaded))
        assertEquals(SpeakerStatus.Offer(needsDownload = true), of(models = SpeakerModelState.Downloading(1, 2)))
        assertEquals(SpeakerStatus.Hidden, of(hasSpeakers = true))
        assertEquals("cloud speakers come with the transcript", SpeakerStatus.Hidden, of(engine = SpeakerEngine.OPENAI))
        assertEquals(SpeakerStatus.Hidden, of(hasAudio = false))
    }

    @Test
    fun aFinishedRunWithoutVoicesSaysSo() {
        assertEquals(SpeakerStatus.NoneFound(canRetry = true), of(phase = DiarizationPhase.Done))
        assertEquals(SpeakerStatus.Hidden, of(phase = DiarizationPhase.Done, hasSpeakers = true))
    }
}
