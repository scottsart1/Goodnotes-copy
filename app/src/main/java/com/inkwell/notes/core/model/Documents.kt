package com.inkwell.notes.core.model

import kotlinx.serialization.Serializable

/** What a library entry is. Each kind opens in its own screen. */
@Serializable
enum class DocKind { NOTEBOOK, PDF, WHITEBOARD, TYPED }

/**
 * Lifecycle of the "YYYYMMDD_Topic" automatic naming for one document.
 *
 * PENDING: still "YYYYMMDD_Untitled", waiting for the 24h window to pass.
 * QUEUED: window passed, waiting for content analysis (offline / AI unavailable).
 * DONE: a topic was inferred and applied; never renamed again unless the user asks.
 * INSUFFICIENT: analysed but not enough content; stays Untitled (re-checked later, bounded).
 * MANUAL: the user named it; automatic naming never touches it.
 * IMPORTED: kept its original imported file name; only renamed on explicit request.
 * DISABLED: automatic naming turned off when the document was created.
 */
@Serializable
enum class AutoNameStatus { PENDING, QUEUED, DONE, INSUFFICIENT, MANUAL, IMPORTED, DISABLED }

@Serializable
data class DocumentMeta(
    val id: String,
    val title: String,
    val kind: DocKind,
    val folderId: String? = null,
    /** Original creation time (epoch ms). The title's date prefix always derives from this. */
    val createdAt: Long,
    val modifiedAt: Long,
    val openedAt: Long = 0,
    val autoNameStatus: AutoNameStatus = AutoNameStatus.PENDING,
    val autoNameAttempts: Int = 0,
    val topic: String? = null,
    val originalName: String? = null,
    val favorite: Boolean = false,
    val pinned: Boolean = false,
    val archived: Boolean = false,
    /** Non-null when in the trash (epoch ms of deletion). */
    val trashedAt: Long? = null,
    val pageCount: Int = 0,
    val lastPageIndex: Int = 0,
    val lastZoom: Float = 1f,
    val lastScrollX: Float = 0f,
    val lastScrollY: Float = 0f,
    val thumbnailPath: String? = null,
    val coverColor: Int = 0,
    val tags: List<String> = emptyList(),
    val sizeBytes: Long = 0,
    val lastSyncedAt: Long = 0,
) {
    val isManuallyNamed: Boolean get() = autoNameStatus == AutoNameStatus.MANUAL
}

@Serializable
data class Folder(
    val id: String,
    val name: String,
    val parentId: String? = null,
    /** ARGB; 0 = default. */
    val color: Int = 0,
    /** Name of a Material icon (see ui/components/FolderIcons.kt), "folder" by default. */
    val icon: String = "folder",
    val createdAt: Long,
    val modifiedAt: Long,
    val sortOrder: Int = 0,
    val trashedAt: Long? = null,
)

@Serializable
enum class PaperStyle { BLANK, RULED, NARROW_RULED, WIDE_RULED, GRID, DOT_GRID, GRAPH }

@Serializable
data class PaperSpec(
    val style: PaperStyle = PaperStyle.RULED,
    /** Paper ARGB. */
    val color: Int = 0xFFFFFFFF.toInt(),
    /** Line/grid colour ARGB. */
    val lineColor: Int = 0xFFD6E0F0.toInt(),
    /** Spacing in points; 0 = style default. */
    val spacingPt: Float = 0f,
) {
    fun effectiveSpacing(): Float = if (spacingPt > 0f) spacingPt else when (style) {
        PaperStyle.BLANK -> 0f
        PaperStyle.RULED -> 24f
        PaperStyle.NARROW_RULED -> 18f
        PaperStyle.WIDE_RULED -> 32f
        PaperStyle.GRID -> 20f
        PaperStyle.DOT_GRID -> 20f
        PaperStyle.GRAPH -> 10f
    }
}

/** Page sizes in PostScript points (1/72 inch). */
@Serializable
enum class PageSizePreset(val widthPt: Float, val heightPt: Float, val label: String) {
    A3(842f, 1191f, "A3"),
    A4(595f, 842f, "A4"),
    A5(420f, 595f, "A5"),
    LETTER(612f, 792f, "US Letter"),
    LEGAL(612f, 1008f, "US Legal"),
    CUSTOM(595f, 842f, "Custom"),
}

@Serializable
enum class Orientation { PORTRAIT, LANDSCAPE }

/** Template used when creating notebooks and adding pages. */
@Serializable
data class PageTemplate(
    val size: PageSizePreset = PageSizePreset.A4,
    val orientation: Orientation = Orientation.PORTRAIT,
    val customWidthPt: Float = 0f,
    val customHeightPt: Float = 0f,
    val paper: PaperSpec = PaperSpec(),
) {
    fun widthPt(): Float {
        val (w, h) = baseSize()
        return if (orientation == Orientation.PORTRAIT) minOf(w, h) else maxOf(w, h)
    }

    fun heightPt(): Float {
        val (w, h) = baseSize()
        return if (orientation == Orientation.PORTRAIT) maxOf(w, h) else minOf(w, h)
    }

    private fun baseSize(): Pair<Float, Float> =
        if (size == PageSizePreset.CUSTOM && customWidthPt > 0f && customHeightPt > 0f) customWidthPt to customHeightPt
        else size.widthPt to size.heightPt
}

/**
 * One page of a document. widthPt/heightPt of 0 mean an unbounded page (whiteboard).
 * pdfPageIndex is set when the page shows a page of the document's source PDF.
 */
@Serializable
data class PageMeta(
    val id: String,
    val documentId: String,
    val index: Int,
    val widthPt: Float,
    val heightPt: Float,
    val paper: PaperSpec = PaperSpec(),
    /** 0, 90, 180, 270 clockwise. */
    val rotation: Int = 0,
    val bookmarked: Boolean = false,
    val bookmarkTitle: String? = null,
    val pdfPageIndex: Int? = null,
    val modifiedAt: Long = 0,
    val thumbnailPath: String? = null,
) {
    val isInfinite: Boolean get() = widthPt <= 0f || heightPt <= 0f
}

@Serializable
data class OutlineEntry(
    val id: String,
    val documentId: String,
    val title: String,
    val pageId: String,
    val parentId: String? = null,
    val sortOrder: Int = 0,
)

@Serializable
enum class LibrarySort { NAME_ASC, NAME_DESC, CREATED_DESC, CREATED_ASC, MODIFIED_DESC, MODIFIED_ASC }

/** Which slice of the library to show. */
@Serializable
data class LibraryFilter(
    val folderId: String? = null,
    /** true = only items directly in [folderId] (null = root); false = everything. */
    val inFolderOnly: Boolean = true,
    val favoritesOnly: Boolean = false,
    val recentOnly: Boolean = false,
    val kinds: Set<DocKind> = emptySet(),
    val archived: Boolean = false,
    val trashed: Boolean = false,
    val query: String = "",
    val sort: LibrarySort = LibrarySort.MODIFIED_DESC,
)

data class StorageInfo(
    val documentCount: Int,
    val totalBytes: Long,
    val audioBytes: Long,
    val pdfBytes: Long,
    val inkBytes: Long,
    val freeBytes: Long,
)
