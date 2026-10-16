package com.inkwell.notes.export

import com.inkwell.notes.core.model.AudioAnchor
import com.inkwell.notes.core.model.AutoNameStatus
import com.inkwell.notes.core.model.DocKind
import com.inkwell.notes.core.model.DocumentMeta
import com.inkwell.notes.core.model.ImageItem
import com.inkwell.notes.core.model.OutlineEntry
import com.inkwell.notes.core.model.PageContent
import com.inkwell.notes.core.model.PageMeta
import com.inkwell.notes.core.model.PaperSpec
import com.inkwell.notes.core.model.PaperStyle
import com.inkwell.notes.core.model.PenType
import com.inkwell.notes.core.model.Recording
import com.inkwell.notes.core.model.RecordingStatus
import com.inkwell.notes.core.model.Stroke
import com.inkwell.notes.core.model.TextItem
import com.inkwell.notes.core.model.TextSource
import com.inkwell.notes.core.model.TranscriptSegment
import com.inkwell.notes.core.model.TranscriptStatus
import com.inkwell.notes.export.backup.IdRemap
import com.inkwell.notes.export.backup.PackageException
import com.inkwell.notes.export.backup.PackageManifest
import com.inkwell.notes.export.backup.PackagedPage
import com.inkwell.notes.export.backup.PackagedRecording
import com.inkwell.notes.export.backup.PackagedText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PackageManifestTest {
    private val doc = DocumentMeta(
        id = "doc-1", title = "20261015_Financial Markets", kind = DocKind.PDF, folderId = "f1",
        createdAt = 1_760_000_000_000, modifiedAt = 1_760_100_000_000, autoNameStatus = AutoNameStatus.DONE,
        topic = "Financial Markets", originalName = "markets.pdf", favorite = true, tags = listOf("econ", "term 1"), pageCount = 2,
    )
    private val pages = listOf(
        PageMeta("p1", "doc-1", 0, 595f, 842f, PaperSpec(PaperStyle.GRID, spacingPt = 12f), rotation = 90, bookmarked = true, bookmarkTitle = "Intro", pdfPageIndex = 0, modifiedAt = 5),
        PageMeta("p2", "doc-1", 1, 595f, 842f, modifiedAt = 6),
    )
    private val recording = Recording(
        id = "rec-1", documentId = "doc-1", title = "Lecture", segmentFiles = listOf("audio/rec-1/seg_000.m4a", "audio/rec-1/seg_001.m4a"),
        segmentDurationsMs = listOf(120_000, 30_000), durationMs = 150_000, startedAt = 1, endedAt = 2,
        status = RecordingStatus.COMPLETE, transcriptStatus = TranscriptStatus.DONE, waveformPath = "audio/rec-1/waveform.bin",
    )

    private fun manifest() = PackageManifest(
        appVersion = "1.0.0",
        exportedAt = 42,
        document = doc,
        pages = pages.map { PackagedPage(it, PackageManifest.pagePath(it.id), textCurrent = it.id == "p1") },
        outline = listOf(OutlineEntry("o1", "doc-1", "Chapter", "p2"), OutlineEntry("o2", "doc-1", "Section", "p2", parentId = "o1", sortOrder = 1)),
        recordings = listOf(PackagedRecording(recording, true, listOf(TranscriptSegment(0, 900, "Hello", "A")), "openai:whisper-1")),
        assets = listOf("img_1.png"),
        sourcePdf = PackageManifest.SOURCE_PDF,
        thumbnail = PackageManifest.THUMBNAIL,
        text = listOf(PackagedText("p1", TextSource.HANDWRITING, "supply", 0.9f, "1,2,3,4", "mlkit-ink")),
    )

    @Test fun manifestRoundTripsThroughJson() {
        val m = manifest()
        val json = PackageManifest.encode(m)
        assertTrue(json.contains("\"format\":\"inkwell-package\""))
        assertEquals(m, PackageManifest.decode(json))
    }

    @Test fun unknownFieldsAreIgnoredForForwardCompatibility() {
        val json = PackageManifest.encode(manifest()).replaceFirst("{", "{\"futureField\":{\"x\":1},")
        assertEquals(manifest(), PackageManifest.decode(json))
    }

    @Test fun foreignOrNewerPackagesAreRejected() {
        fun reason(text: String): PackageException.Reason? = try {
            PackageManifest.decode(text)
            null
        } catch (e: PackageException) {
            e.reason
        }
        val json = PackageManifest.encode(manifest())
        assertEquals(PackageException.Reason.NOT_A_PACKAGE, reason(json.replace("inkwell-package", "something-else")))
        assertEquals(PackageException.Reason.NEWER_VERSION, reason(json.replace("\"formatVersion\":1", "\"formatVersion\":99")))
        assertEquals(PackageException.Reason.CORRUPT, reason("{not json"))
        assertEquals(PackageException.Reason.CORRUPT, reason("{\"format\":\"inkwell-package\"}")) // no document
    }

    @Test fun entryPathsNeverEscapeThePackage() {
        assertEquals("pages/.._.._etc.ink", PackageManifest.pagePath("../../etc"))
        assertEquals("assets/a_b.png", PackageManifest.assetPath("a/b.png"))
        assertEquals("audio/r1/seg_000.m4a", PackageManifest.audioPath("r1", "seg_000.m4a"))
    }

    @Test fun importGetsFreshIdsAndRemappedReferences() {
        var n = 0
        val remap = IdRemap.create(listOf("p1", "p2", "p1"), listOf("rec-1"), listOf("o1", "o2")) { "new-${n++}" }
        assertEquals(2, remap.pages.size)
        assertNotEquals("p1", remap.page("p1"))

        val meta = remap.pageMeta(pages[0], index = 3)!!
        assertEquals(remap.page("p1"), meta.id)
        assertEquals(remap.documentId, meta.documentId)
        assertEquals(3, meta.index)
        assertEquals(90, meta.rotation)
        assertEquals(0, meta.pdfPageIndex)
        assertNull(remap.pageMeta(pages[0].copy(id = "unknown"), 0))

        val outline = remap.outlineEntries(manifest().outline + OutlineEntry("o3", "doc-1", "Gone", "p-missing"))
        assertEquals(2, outline.size)
        assertEquals(remap.page("p2"), outline[0].pageId)
        assertEquals(outline[0].id, outline[1].parentId)
        assertEquals(remap.documentId, outline[1].documentId)

        val content = PageContent(
            strokes = listOf(
                Stroke("s1", PenType.FOUNTAIN, 0, 1f, floatArrayOf(0f, 0f, 1f, 0f, 0f), 0, audio = AudioAnchor("rec-1", 1234)),
                Stroke("s2", PenType.FOUNTAIN, 0, 1f, floatArrayOf(0f, 0f, 1f, 0f, 0f), 0, audio = AudioAnchor("rec-dropped", 5)),
                Stroke("s3", PenType.FOUNTAIN, 0, 1f, floatArrayOf(0f, 0f, 1f, 0f, 0f), 0),
            ),
            texts = listOf(TextItem("t", 0f, 0f, 10f, 10f, "x", audio = AudioAnchor("rec-1", 7))),
            images = listOf(ImageItem("i", "img_1.png", 0f, 0f, 10f, 10f, audio = AudioAnchor("rec-1", 9))),
        )
        val out = remap.content(content)
        assertEquals(AudioAnchor(remap.recording("rec-1")!!, 1234), out.strokes[0].audio)
        assertNull(out.strokes[1].audio)
        assertNull(out.strokes[2].audio)
        assertEquals(remap.recording("rec-1"), out.texts[0].audio?.recordingId)
        assertEquals(9L, out.images[0].audio?.offsetMs)
        assertEquals("img_1.png", out.images[0].assetId)
        // Content without anchors is returned unchanged.
        val plain = PageContent(texts = listOf(TextItem("t", 0f, 0f, 1f, 1f, "y")))
        assertTrue(plain === remap.content(plain))
    }

    @Test fun createdIdsAreUnique() {
        val remap = IdRemap.create(listOf("a", "b", "c"), listOf("r"), emptyList())
        val all = remap.pages.values + remap.recordings.values + remap.documentId
        assertEquals(all.size, all.toSet().size)
        if (all.any { it.isBlank() }) fail("blank id")
    }
}
