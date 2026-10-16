package com.inkwell.notes.core.storage

import com.inkwell.notes.core.storage.db.DocumentEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TagNormalizationTest {
    @Test fun tagsAreTrimmedDedupedAndSafeToStore() {
        val tags = LibraryRepositoryImpl.normalizeTags(
            listOf("  Exam ", "exam", "", "Biology", "a${DocumentEntity.TAG_SEP}b", "   ", "Line\nbreak"),
        )
        assertEquals(listOf("Exam", "Biology", "a b", "Line break"), tags)
        assertTrue(tags.none { it.contains(DocumentEntity.TAG_SEP) })
    }

    @Test fun tagCountAndLengthAreBounded() {
        val tags = LibraryRepositoryImpl.normalizeTags((1..100).map { "tag$it" } + "x".repeat(200))
        assertEquals(32, tags.size)
        assertTrue(LibraryRepositoryImpl.normalizeTags(listOf("y".repeat(200))).single().length <= 48)
    }
}
