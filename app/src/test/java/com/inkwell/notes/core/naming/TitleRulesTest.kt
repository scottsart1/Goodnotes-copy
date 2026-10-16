package com.inkwell.notes.core.naming

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/** The pure parts of createDocument / renameDocument / applyAutoName / duplicateDocument naming. */
class TitleRulesTest {
    private val zone = ZoneId.of("Europe/London")
    private val created = LocalDateTime.of(2026, 10, 15, 9, 30).atZone(zone).toInstant().toEpochMilli()

    @Test fun newDocumentsAreNumberedUntitled() {
        assertEquals("20261015_Untitled", TitleRules.untitled(created, emptyList(), zone))
        assertEquals("20261015_Untitled (2)", TitleRules.untitled(created, listOf("20261015_Untitled"), zone))
        assertEquals(
            "20261015_Untitled (3)",
            TitleRules.untitled(created, listOf("20261015_untitled", "20261015_Untitled (2)"), zone),
        )
    }

    @Test fun autoNameKeepsTheCreationDateAndIsUnique() {
        val others = listOf("20261015_Monetary Policy", "20261016_Monetary Policy")
        // Applied days later, the prefix is still the creation date.
        assertEquals("20261015_Monetary Policy (2)", TitleRules.auto(created, "monetary policy", others, zone))
        assertEquals("20261015_Bond Markets", TitleRules.auto(created, "Bond Markets", others, zone))
    }

    @Test fun autoNameIgnoresTheDocumentsOwnOldTitle() {
        // The repository passes the titles of the *other* documents, so renaming "Untitled" never collides with itself.
        assertEquals("20261015_Genetics", TitleRules.auto(created, "Genetics", listOf("20261014_Genetics"), zone))
    }

    @Test fun autoNameKeepsThePrefixTheDocumentAlreadyShows() {
        // Created 23:30 New York time on 15 Oct; the device is now set to London where that instant is 16 Oct.
        val lateEvening = LocalDateTime.of(2026, 10, 15, 23, 30).atZone(ZoneId.of("America/New_York")).toInstant().toEpochMilli()
        assertEquals("20261016_Physics", TitleRules.auto(lateEvening, "Physics", emptyList(), zone))
        assertEquals("20261015_Physics", TitleRules.auto(lateEvening, "Physics", emptyList(), zone, currentTitle = "20261015_Untitled"))
        assertEquals("20261015_Physics", TitleRules.auto(lateEvening, "Physics", emptyList(), zone, currentTitle = "20261015_Untitled (3)"))
        assertEquals("20261015_Physics (2)", TitleRules.auto(lateEvening, "Physics", listOf("20261015_physics"), zone, currentTitle = "20261015_Untitled"))
        // A title without the convention (imported or manual) gets the prefix from the creation time.
        assertEquals("20261016_Physics", TitleRules.auto(lateEvening, "Physics", emptyList(), zone, currentTitle = "Lecture slides"))
        assertEquals("20261015", DateNaming.prefixOf("20261015_Untitled"))
        assertNull(DateNaming.prefixOf("2026101_Untitled"))
        assertNull(DateNaming.prefixOf("Untitled"))
    }

    @Test fun unusableTopicsAreRejected() {
        assertNull(TitleRules.auto(created, "Notes", emptyList(), zone))
        assertNull(TitleRules.auto(created, "  \"\"  ", emptyList(), zone))
    }

    @Test fun manualRenamesAreTrimmedAndUnique() {
        assertEquals("Physics", TitleRules.manual("  Physics  ", emptyList()))
        assertEquals("Physics (2)", TitleRules.manual("Physics", listOf("physics")))
        assertEquals("Line one line two", TitleRules.manual("Line one\nline two", emptyList()))
        assertNull(TitleRules.manual("   \n\t ", emptyList()))
        assertEquals(TitleRules.MAX_TITLE_CHARS, TitleRules.manual("x".repeat(500), emptyList())!!.length)
    }

    @Test fun importedNamesLoseFoldersAndKnownExtensions() {
        assertEquals("Lecture 5 slides", TitleRules.fromImportedName("Lecture 5 slides.pdf"))
        assertEquals("scan", TitleRules.fromImportedName("/storage/emulated/0/Download/scan.JPG"))
        assertEquals("report.v2", TitleRules.fromImportedName("report.v2"))
        assertEquals(".pdf", TitleRules.fromImportedName(".pdf"))
        assertNull(TitleRules.fromImportedName("   "))
    }

    @Test fun copiesAreNamedCopyAndNumbered() {
        assertEquals("20261015_Physics copy", TitleRules.copyOf("20261015_Physics", listOf("20261015_Physics")))
        assertEquals(
            "20261015_Physics copy (2)",
            TitleRules.copyOf("20261015_Physics", listOf("20261015_Physics", "20261015_Physics copy")),
        )
    }
}
