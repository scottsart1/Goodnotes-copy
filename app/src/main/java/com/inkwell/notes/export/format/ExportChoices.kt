package com.inkwell.notes.export.format

import com.inkwell.notes.export.ExportFormat
import com.inkwell.notes.export.ExportOptions
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

enum class RangeMode { ALL, SELECTED, CUSTOM }

/** PDF image quality presets: resolution of embedded/rasterised images and JPEG quality. */
enum class PdfQuality(val dpi: Int, val jpegQuality: Int) {
    HIGH(300, 92),
    BALANCED(200, 85),
    SMALL(150, 70),
}

/**
 * What the user picked in the export dialog, independent of Compose so it can be validated,
 * remembered between exports and turned into [ExportOptions].
 */
@Serializable
data class ExportChoices(
    val format: ExportFormat = ExportFormat.PDF,
    val range: RangeMode = RangeMode.ALL,
    val custom: String = "",
    val flatten: Boolean = true,
    val background: Boolean = true,
    val searchable: Boolean = true,
    val outline: Boolean = true,
    val pdfQuality: PdfQuality = PdfQuality.BALANCED,
    val imageDpi: Int = 200,
    val jpegQuality: Int = 90,
    val includeAudio: Boolean = true,
    val includeTranscripts: Boolean = true,
) {
    /**
     * The export options, or the page-range error to show. [selected] are the pages selected
     * in the editor (null when none); [pageCount] the document's pages.
     */
    fun toOptions(pageCount: Int, selected: List<Int>?, paged: Boolean): Result {
        // Packages always hold the whole document; typed documents and boards have no page choice.
        val pages: List<Int>? = if (!paged || format == ExportFormat.INKWELL) null else when (range) {
            RangeMode.ALL -> null
            RangeMode.SELECTED -> selected?.filter { it in 0 until pageCount }?.distinct()?.sorted()?.takeIf { it.isNotEmpty() }
            RangeMode.CUSTOM -> when (val r = PageRange.parse(custom, pageCount)) {
                is PageRange.Result.Ok -> r.indices
                is PageRange.Result.Error -> return Result.Invalid(r)
            }
        }
        val dpi = if (format == ExportFormat.PDF) pdfQuality.dpi else imageDpi.coerceIn(72, 600)
        val quality = if (format == ExportFormat.PDF) pdfQuality.jpegQuality else jpegQuality.coerceIn(30, 100)
        return Result.Valid(
            ExportOptions(
                format = format,
                pageIndices = pages,
                flattenAnnotations = flatten,
                includeBackground = background,
                searchableText = searchable,
                includeOutline = outline,
                dpi = dpi,
                jpegQuality = quality,
                includeAudio = includeAudio,
                includeTranscripts = includeTranscripts,
            ),
        )
    }

    /** Number of pages the export will contain (for the summary line). */
    fun pageCountFor(pageCount: Int, selected: List<Int>?): Int? = when (range) {
        RangeMode.ALL -> pageCount
        RangeMode.SELECTED -> selected?.count { it in 0 until pageCount }
        RangeMode.CUSTOM -> (PageRange.parse(custom, pageCount) as? PageRange.Result.Ok)?.indices?.size
    }

    sealed interface Result {
        data class Valid(val options: ExportOptions) : Result
        data class Invalid(val error: PageRange.Result.Error) : Result
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

        fun encode(c: ExportChoices): String = json.encodeToString(serializer(), c.copy(range = if (c.range == RangeMode.SELECTED) RangeMode.ALL else c.range, custom = ""))

        fun decode(s: String?): ExportChoices = s?.let { runCatching { json.decodeFromString(serializer(), it) }.getOrNull() } ?: ExportChoices()

        /** First choice for a dialog opened with a page selection. */
        fun initial(saved: ExportChoices, selected: List<Int>?): ExportChoices =
            saved.copy(range = if (!selected.isNullOrEmpty()) RangeMode.SELECTED else RangeMode.ALL, custom = "")
    }
}
