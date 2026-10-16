package com.inkwell.notes.export.backup

import com.inkwell.notes.core.model.DocumentMeta
import com.inkwell.notes.core.model.OutlineEntry
import com.inkwell.notes.core.model.PageMeta
import com.inkwell.notes.core.model.Recording
import com.inkwell.notes.core.model.TextSource
import com.inkwell.notes.core.model.TranscriptSegment
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * manifest.json of a native .inkwell package (a zip):
 *
 * manifest.json                 this file (always the first entry)
 * pages/<pageId>.ink            ProtoBuf PageContent of each page
 * assets/<assetId>              images and other embedded files
 * source.pdf                    the imported PDF, when the document has one
 * typed.json                    typed-document blocks (TYPED documents)
 * audio/<recId>/seg_NNN.m4a     recording segments (when audio is included)
 * audio/<recId>/waveform.bin    amplitude envelope
 * thumb.webp                    library thumbnail
 *
 * Paths inside the manifest are relative to the package root; no device paths are stored.
 */
@Serializable
data class PackageManifest(
    val format: String = FORMAT_ID,
    val formatVersion: Int = FORMAT_VERSION,
    val appVersion: String = "",
    val exportedAt: Long = 0,
    val document: DocumentMeta,
    val pages: List<PackagedPage> = emptyList(),
    val outline: List<OutlineEntry> = emptyList(),
    val recordings: List<PackagedRecording> = emptyList(),
    val assets: List<String> = emptyList(),
    val sourcePdf: String? = null,
    val typed: String? = null,
    val thumbnail: String? = null,
    /** Recognised text (handwriting, OCR, PDF text) so a restored library is searchable at once. */
    val text: List<PackagedText> = emptyList(),
) {
    companion object {
        const val FORMAT_ID = "inkwell-package"
        const val FORMAT_VERSION = 1
        const val MANIFEST = "manifest.json"
        const val SOURCE_PDF = "source.pdf"
        const val TYPED = "typed.json"
        const val THUMBNAIL = "thumb.webp"

        fun pagePath(pageId: String) = "pages/${safe(pageId)}.ink"
        fun assetPath(assetId: String) = "assets/${safe(assetId)}"
        fun audioPath(recordingId: String, fileName: String) = "audio/${safe(recordingId)}/${safe(fileName)}"

        private val UNSAFE = Regex("[^A-Za-z0-9._-]")
        fun safe(name: String): String = name.replace(UNSAFE, "_").ifEmpty { "_" }

        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            coerceInputValues = true
            prettyPrint = false
        }

        fun encode(m: PackageManifest): String = json.encodeToString(serializer(), m)

        /** Parses and validates a manifest; throws [PackageException] when it is not ours or too new. */
        fun decode(text: String): PackageManifest {
            val m = try {
                json.decodeFromString(serializer(), text)
            } catch (e: Exception) {
                throw PackageException(PackageException.Reason.CORRUPT, e)
            }
            if (m.format != FORMAT_ID) throw PackageException(PackageException.Reason.NOT_A_PACKAGE)
            if (m.formatVersion > FORMAT_VERSION) throw PackageException(PackageException.Reason.NEWER_VERSION)
            return m
        }
    }
}

@Serializable
data class PackagedPage(
    val meta: PageMeta,
    /** Path of the page's .ink entry. */
    val content: String,
    /** True when the page's recognised text was current at export time. */
    val textCurrent: Boolean = false,
)

@Serializable
data class PackagedRecording(
    /** segmentFiles hold package paths (empty when audio was not included); waveformPath likewise. */
    val recording: Recording,
    val audioIncluded: Boolean,
    val transcript: List<TranscriptSegment> = emptyList(),
    val transcriptSource: String? = null,
)

@Serializable
data class PackagedText(
    val pageId: String? = null,
    val source: TextSource,
    val text: String,
    val confidence: Float? = null,
    val bounds: String? = null,
    val engine: String = "",
    val timestampMs: Long? = null,
)
