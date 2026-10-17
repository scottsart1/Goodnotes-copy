package com.inkwell.notes.export

import com.inkwell.notes.core.model.AppSettings
import com.inkwell.notes.core.model.AutoNameStatus
import com.inkwell.notes.core.model.DocKind
import com.inkwell.notes.core.model.DocumentMeta
import com.inkwell.notes.core.model.Recording
import com.inkwell.notes.core.model.RecordingStatus
import com.inkwell.notes.core.model.SpeakerEngine
import com.inkwell.notes.core.model.SpeakerPrefs
import com.inkwell.notes.core.model.TranscriptSegment
import com.inkwell.notes.core.model.TranscriptStatus
import com.inkwell.notes.core.settings.SettingsRepository
import com.inkwell.notes.export.backup.PackageManifest
import com.inkwell.notes.export.backup.PackagedRecording
import com.inkwell.notes.export.backup.PackagedTranscripts
import com.inkwell.notes.export.backup.SettingsSanitizer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A backup or .inkwell package carries who said what (names and per-recording voice labels)
 * and nothing that could identify a voice (no embeddings, centroids, reference clips, sample
 * counts). Restoring keeps the names and labels so transcripts, search and exports read the
 * same, and nothing voice-related is restored because nothing was written.
 */
class BackupSpeakerDataTest {
    private val doc = DocumentMeta(
        id = "doc-1", title = "20261016_Standup", kind = DocKind.WHITEBOARD, folderId = null,
        createdAt = 1_760_000_000_000, modifiedAt = 1_760_100_000_000, autoNameStatus = AutoNameStatus.DONE,
    )
    private val recording = Recording(
        id = "rec-1", documentId = "doc-1", title = "Standup", segmentFiles = emptyList(), segmentDurationsMs = emptyList(),
        durationMs = 150_000, startedAt = 1, endedAt = 2, status = RecordingStatus.COMPLETE, transcriptStatus = TranscriptStatus.DONE, waveformPath = null,
    )
    private val segments = listOf(
        TranscriptSegment(0, 4_000, "Morning everyone", "Priya", "S1"),
        TranscriptSegment(4_000, 9_000, "Hi Priya", "Speaker 2", "S2"),
        TranscriptSegment(9_000, 12_000, "Unlabelled block", null, null),
        TranscriptSegment(12_000, 15_000, "Moved block", "Priya", "S1"),
    )

    private fun manifest() = PackageManifest(
        appVersion = "1.0.0", exportedAt = 42, document = doc,
        recordings = listOf(PackagedRecording(recording, audioIncluded = false, transcript = segments, transcriptSource = "device:sherpa")),
    )

    @Test fun namesAndLabelsSurviveTheManifestRoundTrip() {
        val back = PackageManifest.decode(PackageManifest.encode(manifest()))
        assertEquals(segments, back.recordings.single().transcript)
        assertEquals("Priya", back.recordings.single().transcript[0].speaker)
        assertEquals("S1", back.recordings.single().transcript[0].speakerLabel)
    }

    @Test fun restoredRowsKeepNameAndLabelAndNormaliseBlanks() {
        val rows = PackagedTranscripts.entities("new-rec", segments + TranscriptSegment(15_000, 16_000, "x", "  ", " "), null)
        assertEquals(5, rows.size)
        assertTrue(rows.all { it.recordingId == "new-rec" && it.rowId == 0L })
        assertEquals("Priya", rows[0].speaker)
        assertEquals("S1", rows[0].speakerLabel)
        assertEquals("Speaker 2", rows[1].speaker)
        assertEquals("S2", rows[1].speakerLabel)
        assertNull(rows[2].speaker)
        assertNull(rows[2].speakerLabel)
        // Blank names and labels become null, so "unknown" stays one value everywhere.
        assertNull(rows[4].speaker)
        assertNull(rows[4].speakerLabel)
        assertEquals(PackagedTranscripts.IMPORT_SOURCE, rows[0].source)
        assertEquals("openai", PackagedTranscripts.entities("r", segments, "openai").first().source)
    }

    @Test fun packagesWrittenBeforeSpeakerLabelsRestoreWithNullLabels() {
        val old = """{"format":"inkwell-package","formatVersion":1,"document":${PackageManifest.json.encodeToString(DocumentMeta.serializer(), doc)},
            "recordings":[{"recording":${PackageManifest.json.encodeToString(Recording.serializer(), recording)},"audioIncluded":false,
            "transcript":[{"startMs":0,"endMs":900,"text":"Hello","speaker":"A"},{"startMs":900,"endMs":1800,"text":"Hi","futureField":{"a":1}}]}]}"""
        val m = PackageManifest.decode(old)
        val t = m.recordings.single().transcript
        assertEquals(listOf(TranscriptSegment(0, 900, "Hello", "A", null), TranscriptSegment(900, 1800, "Hi", null, null)), t)
    }

    @Test fun manifestNeverCarriesVoiceData() {
        val tree = PackageManifest.json.parseToJsonElement(PackageManifest.encode(manifest()))
        val keys = HashSet<String>()
        fun walk(e: JsonElement) {
            when (e) {
                is JsonObject -> e.forEach { (k, v) -> keys += k; walk(v) }
                is JsonArray -> e.forEach(::walk)
                else -> Unit
            }
        }
        walk(tree)
        for (word in listOf("embedding", "vector", "centroid", "voiceprint", "clip", "personId", "voiceSamples", "confidence")) {
            assertTrue("manifest key mentions $word: ${keys.filter { it.contains(word, ignoreCase = true) }}", keys.none { it.contains(word, ignoreCase = true) })
        }
        // The amplitude envelope (waveformPath) is a display aid, not audio; no WAV reference clip travels.
        assertTrue(keys.none { it.equals("wav", ignoreCase = true) || it.contains("reference", ignoreCase = true) })
        // Only the display name and the within-recording label travel.
        val segmentKeys = tree.jsonObject["recordings"]!!.let { it as JsonArray }[0].jsonObject["transcript"]!!.let { it as JsonArray }[0].jsonObject.keys
        assertEquals(setOf("startMs", "endMs", "text", "speaker", "speakerLabel"), segmentKeys)
    }

    @Test fun backedUpSettingsKeepSpeakerPreferencesWithoutDeviceState() {
        val s = AppSettings(speakers = SpeakerPrefs(engine = SpeakerEngine.OPENAI, rememberVoices = false, matchThreshold = 0.7f))
        val forBackup = SettingsSanitizer.forBackup(s)
        assertEquals(s.speakers, forBackup.speakers)
        val text = SettingsRepository.json.encodeToString(AppSettings.serializer(), forBackup)
        assertFalse(text.contains("apiKey", ignoreCase = true))
        // Restoring applies them like any other preference; privacy and destinations stay the device's.
        val merged = SettingsSanitizer.merge(AppSettings(), forBackup)
        assertEquals(s.speakers, merged.speakers)
    }
}
