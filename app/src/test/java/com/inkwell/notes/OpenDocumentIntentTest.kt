package com.inkwell.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * MainActivity is exported, so the OPEN_DOCUMENT action (sent by the app's own "voices to
 * name" notification) can also arrive from any other app. Only ids shaped like the app's own
 * are accepted; everything else is dropped before it reaches navigation or the database.
 */
class OpenDocumentIntentTest {
    @Test fun idsTheAppCreatesAreAccepted() {
        repeat(20) {
            val id = UUID.randomUUID().toString()
            assertEquals(id, OpenDocumentIntent.docId(id))
        }
        assertEquals("doc_1-A", OpenDocumentIntent.docId("doc_1-A"))
        assertEquals("a".repeat(OpenDocumentIntent.MAX_ID_LENGTH), OpenDocumentIntent.docId("a".repeat(OpenDocumentIntent.MAX_ID_LENGTH)))
    }

    @Test fun missingEmptyAndOversizedIdsAreDropped() {
        assertNull(OpenDocumentIntent.docId(null))
        assertNull(OpenDocumentIntent.docId(""))
        assertNull(OpenDocumentIntent.docId("a".repeat(OpenDocumentIntent.MAX_ID_LENGTH + 1)))
    }

    @Test fun pathQueryAndControlCharactersAreDropped() {
        for (bad in listOf("../etc", "doc/..", "doc id", "doc\n", "doc\u0000", "doc;drop", "doc?x=1", "doc%2F", "doc.ink", "‮doc", "ドキュメント", " doc", "doc ")) {
            assertNull("'$bad' must be rejected", OpenDocumentIntent.docId(bad))
            assertFalse(OpenDocumentIntent.isValidDocId(bad))
        }
    }

    @Test fun actionAndExtraNamesAreStable() {
        // The notification PendingIntent and the activity must agree; a rename here breaks taps on old notifications.
        assertEquals("com.inkwell.notes.OPEN_DOCUMENT", MainActivity.ACTION_OPEN_DOCUMENT)
        assertEquals("docId", MainActivity.EXTRA_DOC_ID)
        assertTrue(OpenDocumentIntent.MAX_ID_LENGTH >= 36)
    }
}
