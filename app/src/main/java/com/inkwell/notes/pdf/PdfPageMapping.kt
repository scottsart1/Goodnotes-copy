package com.inkwell.notes.pdf

/**
 * Pure bookkeeping between document pages and source-PDF pages. A document page shows a PDF
 * page through its `pdfPageIndex`; these helpers decide which indices new pages get when PDFs
 * are inserted, extracted, split and merged, so existing pages never point at the wrong page.
 */
internal object PdfPageMapping {

    /** A page of some source PDF: [source] identifies the file (a document id), [pdfIndex] the page in it. */
    data class Ref<K>(val source: K, val pdfIndex: Int)

    /**
     * Result of [compact]: [keep] lists the source pages to copy into the new PDF, in order
     * (new PDF page i = keep[i]); [remapped] holds the new pdfPageIndex of every input page
     * (null for pages without a PDF background).
     */
    data class Compaction<K>(val keep: List<Ref<K>>, val remapped: List<Int?>)

    /** Where the inserted pages of an [insertPlan] go. */
    data class InsertPlan(
        /** pdfPageIndex of the first inserted page; the others follow consecutively. */
        val firstPdfIndex: Int,
        /** Position of the first inserted page in the document's page order. */
        val atPageIndex: Int,
        val count: Int,
    ) {
        val pdfIndices: List<Int> get() = List(count) { firstPdfIndex + it }
    }

    /** One stretch of the new document's pages, created with a single library call. */
    sealed interface Segment {
        /** Position of the first page of the segment in the page list. */
        val start: Int
        val count: Int

        /** [count] consecutive pages showing PDF pages firstPdfIndex, firstPdfIndex + 1, ... */
        data class PdfRun(override val start: Int, override val count: Int, val firstPdfIndex: Int) : Segment

        /** A single paper page (no PDF background). */
        data class Paper(override val start: Int) : Segment {
            override val count: Int get() = 1
        }
    }

    /** Clamps a requested insertion position; null or out of range means "at the end". */
    fun insertionIndex(atIndex: Int?, pageCount: Int): Int {
        val count = pageCount.coerceAtLeast(0)
        return when {
            atIndex == null -> count
            atIndex < 0 -> 0
            atIndex > count -> count
            else -> atIndex
        }
    }

    /**
     * Inserting a PDF with [insertedCount] pages appends them to the source PDF (which had
     * [existingPdfCount] pages), so the existing pdfPageIndex values stay valid and the new
     * pages get existingPdfCount + i, placed at [atIndex] in the page order.
     */
    fun insertPlan(existingPdfCount: Int, insertedCount: Int, atIndex: Int?, pageCount: Int): InsertPlan =
        InsertPlan(
            firstPdfIndex = existingPdfCount.coerceAtLeast(0),
            atPageIndex = insertionIndex(atIndex, pageCount),
            count = insertedCount.coerceAtLeast(0),
        )

    /** The pdfPageIndex column of a document after applying [plan] to [existing] (for checks). */
    fun applyInsert(existing: List<Int?>, plan: InsertPlan): List<Int?> {
        val at = insertionIndex(plan.atPageIndex, existing.size)
        return existing.subList(0, at) + plan.pdfIndices + existing.subList(at, existing.size)
    }

    /**
     * Builds the page list of a new PDF holding only the referenced pages. Each distinct source
     * page is copied once, in order of first appearance; pages that show the same source page
     * (duplicates) share it.
     */
    fun <K> compact(refs: List<Ref<K>?>): Compaction<K> {
        val keep = ArrayList<Ref<K>>()
        val position = HashMap<Ref<K>, Int>()
        val remapped = refs.map { ref ->
            if (ref == null || ref.pdfIndex < 0) null
            else position.getOrPut(ref) { keep.add(ref); keep.size - 1 }
        }
        return Compaction(keep, remapped)
    }

    /** Groups [remapped] into runs of consecutive PDF pages and single paper pages. */
    fun segments(remapped: List<Int?>): List<Segment> {
        val out = ArrayList<Segment>()
        var runStart = -1
        var runFirst = 0
        var runCount = 0
        fun flush() {
            if (runCount > 0) out += Segment.PdfRun(runStart, runCount, runFirst)
            runCount = 0
        }
        remapped.forEachIndexed { i, idx ->
            when {
                idx == null -> {
                    flush()
                    out += Segment.Paper(i)
                }
                runCount > 0 && idx == runFirst + runCount -> runCount++
                else -> {
                    flush()
                    runStart = i
                    runFirst = idx
                    runCount = 1
                }
            }
        }
        flush()
        return out
    }

    /**
     * Page positions to move into a new document when splitting before [atPageIndex].
     * Throws when the split would leave either part empty.
     */
    fun splitRange(pageCount: Int, atPageIndex: Int): IntRange {
        require(pageCount >= 2 && atPageIndex in 1 until pageCount) { "Cannot split at $atPageIndex of $pageCount" }
        return atPageIndex until pageCount
    }

    /** Valid, distinct page positions from [requested] in the given order. */
    fun selection(requested: List<Int>, pageCount: Int): List<Int> =
        requested.filter { it in 0 until pageCount }.distinct()
}
