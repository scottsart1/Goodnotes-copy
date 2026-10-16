package com.inkwell.notes.speakers

import androidx.work.WorkInfo
import com.inkwell.notes.speakers.logic.ModelFiles
import com.inkwell.notes.speakers.logic.ModelSpec
import com.inkwell.notes.speakers.logic.NamingNotice
import com.inkwell.notes.speakers.logic.NamingRules
import com.inkwell.notes.speakers.logic.RangeResume
import com.inkwell.notes.speakers.logic.ReferenceOrder
import com.inkwell.notes.speakers.logic.SpeakerModelSpecs
import com.inkwell.notes.speakers.logic.SpeakerPhases
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

class SpeakerRulesTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun specFor(bytes: ByteArray, name: String = "m.onnx") = ModelSpec(
        fileName = name,
        url = "https://example.invalid/$name",
        sizeBytes = bytes.size.toLong(),
        sha256 = ModelFiles.hex(MessageDigest.getInstance("SHA-256").digest(bytes)),
        description = "test",
        license = "MIT",
    )

    @Test
    fun pinnedModelsAreConsistent() {
        assertEquals(32_523_463L, SpeakerModelSpecs.TOTAL_BYTES)
        for (s in SpeakerModelSpecs.ALL) {
            assertTrue(s.url.startsWith("https://huggingface.co/"))
            assertTrue("revision-pinned", Regex("/resolve/[0-9a-f]{40}/").containsMatchIn(s.url))
            assertEquals(64, s.sha256.length)
        }
        assertEquals("device:wespeaker_resnet34_lm", SpeakerModelSpecs.ENGINE_ID)
    }

    @Test
    fun verifiedDownloadIsPromotedAtomically() {
        val bytes = ByteArray(10_000) { (it * 31).toByte() }
        val spec = specFor(bytes)
        val dir = tmp.newFolder()
        val part = ModelFiles.part(dir, spec).apply { writeBytes(bytes) }
        val target = ModelFiles.target(dir, spec)
        assertFalse(ModelFiles.present(dir, listOf(spec)))
        assertEquals(10_000L, ModelFiles.bytesOnDisk(dir, listOf(spec)))
        assertEquals(ModelFiles.Outcome.Ok, ModelFiles.promote(part, target, spec))
        assertFalse(part.exists())
        assertTrue(target.isFile)
        assertTrue(ModelFiles.present(dir, listOf(spec)))
        assertEquals(ModelFiles.Outcome.Ok, ModelFiles.verify(target, spec))
    }

    @Test
    fun hashMismatchDeletesThePartialFileAndFails() {
        val bytes = ByteArray(4_096) { it.toByte() }
        val spec = specFor(bytes)
        val dir = tmp.newFolder()
        val corrupt = bytes.copyOf().also { it[100] = (it[100] + 1).toByte() }
        val part = ModelFiles.part(dir, spec).apply { writeBytes(corrupt) }
        val target = ModelFiles.target(dir, spec)
        assertEquals(ModelFiles.Outcome.HashMismatch, ModelFiles.promote(part, target, spec))
        assertFalse(part.exists())
        assertFalse(target.exists())
        val short = ModelFiles.part(dir, spec).apply { writeBytes(bytes.copyOf(100)) }
        assertEquals(ModelFiles.Outcome.SizeMismatch(100), ModelFiles.promote(short, target, spec))
        assertFalse(short.exists())
        assertTrue(ModelFiles.verify(File(dir, "nope"), spec) is ModelFiles.Outcome.Unreadable)
    }

    @Test
    fun resumeOffsets() {
        val spec = specFor(ByteArray(1_000))
        assertEquals(0L, ModelFiles.resumeFrom(0, spec))
        assertEquals(400L, ModelFiles.resumeFrom(400, spec))
        assertEquals(0L, ModelFiles.resumeFrom(1_000, spec))
        assertEquals(0L, ModelFiles.resumeFrom(5_000, spec))
    }

    @Test
    fun rangeResponses() {
        assertEquals(RangeResume.Action.Append, RangeResume.onResponse(206, 400, "bytes 400-999/1000"))
        assertEquals(RangeResume.Action.Restart, RangeResume.onResponse(206, 400, "bytes 0-999/1000"))
        assertEquals(RangeResume.Action.Overwrite, RangeResume.onResponse(200, 400, null))
        assertEquals(RangeResume.Action.Append, RangeResume.onResponse(200, 0, null))
        assertEquals(RangeResume.Action.Restart, RangeResume.onResponse(416, 400, null))
        assertEquals(RangeResume.Action.Fail(true), RangeResume.onResponse(503, 0, null))
        assertEquals(RangeResume.Action.Fail(true), RangeResume.onResponse(429, 0, null))
        assertEquals(RangeResume.Action.Fail(false), RangeResume.onResponse(404, 0, null))
        assertEquals(400L, RangeResume.rangeStart("bytes 400-999/1000"))
        assertNull(RangeResume.rangeStart("items 1-2"))
    }

    @Test
    fun diarizationPhases() {
        val ready = SpeakerModelState.Ready
        assertEquals(DiarizationPhase.Running(2, 5), SpeakerPhases.of(WorkInfo.State.RUNNING, 2 to 5, null, false, false, ready))
        assertEquals(DiarizationPhase.Running(0, 0), SpeakerPhases.of(WorkInfo.State.RUNNING, null, null, true, false, ready))
        assertEquals(DiarizationPhase.Queued, SpeakerPhases.of(WorkInfo.State.ENQUEUED, null, null, true, false, ready))
        assertEquals(DiarizationPhase.WaitingForModels, SpeakerPhases.of(null, null, null, false, true, SpeakerModelState.Downloading(1, 2)))
        assertEquals(DiarizationPhase.Failed("no net"), SpeakerPhases.of(null, null, null, false, true, SpeakerModelState.Failed("no net")))
        assertEquals(DiarizationPhase.Done, SpeakerPhases.of(WorkInfo.State.FAILED, null, "x", true, false, ready))
        assertEquals(DiarizationPhase.Done, SpeakerPhases.of(WorkInfo.State.SUCCEEDED, null, null, false, false, ready))
        assertEquals(DiarizationPhase.Failed("x"), SpeakerPhases.of(WorkInfo.State.FAILED, null, "x", false, false, ready))
        assertEquals(DiarizationPhase.None, SpeakerPhases.of(WorkInfo.State.CANCELLED, null, null, false, false, ready))
        assertEquals(DiarizationPhase.None, SpeakerPhases.of(null, null, null, false, false, ready))
        assertEquals(DiarizationPhase.Done, SpeakerPhases.of(null, null, null, true, false, ready))
    }

    @Test
    fun modelStates() {
        val total = SpeakerModelSpecs.TOTAL_BYTES
        assertEquals(SpeakerModelState.Ready, SpeakerPhases.modelState(true, WorkInfo.State.FAILED, null, total, total, "x"))
        assertEquals(SpeakerModelState.Downloading(10, 20), SpeakerPhases.modelState(false, WorkInfo.State.RUNNING, 10L to 20L, 0, total, null))
        assertEquals(SpeakerModelState.Downloading(5, total), SpeakerPhases.modelState(false, WorkInfo.State.ENQUEUED, null, 5, total, null))
        assertEquals(SpeakerModelState.Failed("bad hash"), SpeakerPhases.modelState(false, WorkInfo.State.FAILED, null, 0, total, "bad hash"))
        assertEquals(SpeakerModelState.NotDownloaded, SpeakerPhases.modelState(false, null, null, 0, total, null))
        assertEquals(SpeakerModelState.NotDownloaded, SpeakerPhases.modelState(false, WorkInfo.State.SUCCEEDED, null, 0, total, null))
    }

    @Test
    fun namingRules() {
        val v = listOf(
            NamingRules.Voice("p1", false, 60_000),
            NamingRules.Voice(null, false, 5_000),
            NamingRules.Voice(null, false, 3_999),
            NamingRules.Voice(null, true, 50_000),
        )
        assertTrue(NamingRules.needsNaming(v, askToName = true))
        assertFalse(NamingRules.needsNaming(v, askToName = false))
        assertEquals(1, NamingRules.countToName(v))
        assertFalse(NamingRules.needsNaming(v.take(1) + v.drop(2), askToName = true))
    }

    @Test
    fun notificationNeverNamesAnythingWhileLocked() {
        assertEquals(NamingNotice.Text.Counted(3, "20261016_Standup"), NamingNotice.decide(3, " 20261016_Standup ", false, false, true))
        assertEquals(NamingNotice.Text.Generic, NamingNotice.decide(3, "20261016_Standup", appLocked = true, inForeground = false, canNotify = true))
        assertEquals(NamingNotice.Text.Generic, NamingNotice.decide(1, null, false, false, true))
        assertNull(NamingNotice.decide(3, "x", false, inForeground = true, canNotify = true))
        assertNull(NamingNotice.decide(3, "x", false, false, canNotify = false))
        assertNull(NamingNotice.decide(0, "x", false, false, true))
    }

    @Test
    fun referenceOrder() {
        val people = listOf(
            ReferenceOrder.Candidate("old", false, 1),
            ReferenceOrder.Candidate("me", true, 2),
            ReferenceOrder.Candidate("recent", false, 3),
            ReferenceOrder.Candidate("fresh", false, 9),
            ReferenceOrder.Candidate("noclip", false, 10),
            ReferenceOrder.Candidate("here", false, 0),
        )
        val clips = setOf("old", "me", "recent", "fresh", "here")
        assertEquals(
            listOf("here", "me", "recent", "fresh"),
            ReferenceOrder.people(people, listOf("here"), listOf("recent", "me"), clips, 4),
        )
        assertEquals(listOf("me", "fresh"), ReferenceOrder.people(people, emptyList(), listOf("ghost"), clips, 2))
        assertTrue(ReferenceOrder.people(people, emptyList(), emptyList(), clips, 0).isEmpty())
    }
}
