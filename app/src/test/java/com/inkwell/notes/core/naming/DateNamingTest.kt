package com.inkwell.notes.core.naming

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class DateNamingTest {
    private val zone = ZoneId.of("Europe/London")
    private val created = LocalDateTime.of(2026, 10, 15, 9, 30).atZone(zone).toInstant().toEpochMilli()

    @Test fun initialTitleUsesCreationDate() {
        assertEquals("20261015_Untitled", DateNaming.initialTitle(created, zone))
    }

    @Test fun topicTitleKeepsCreationDate() {
        assertEquals("20261015_Monetary Policy", DateNaming.titleFor(created, "monetary policy", zone))
    }

    @Test fun untitledDetection() {
        assertTrue(DateNaming.isUntitled("20261015_Untitled"))
        assertTrue(DateNaming.isUntitled("20261015_Untitled (3)"))
        assertFalse(DateNaming.isUntitled("20261015_Meeting Notes"))
        assertFalse(DateNaming.isUntitled("Untitled"))
    }

    @Test fun cleansAndLimitsTopic() {
        assertEquals("Financial Markets", DateNaming.cleanTopic("  \"financial markets.\"  "))
        assertEquals("One Two Three Four Five", DateNaming.cleanTopic("one two three four five six seven"))
        assertEquals("GDP Outlook", DateNaming.cleanTopic("Topic: GDP outlook"))
        assertEquals("Q3 Report", DateNaming.cleanTopic("Q3/Report"))
        assertNull(DateNaming.cleanTopic("Notes"))
        assertNull(DateNaming.cleanTopic("   "))
    }

    @Test fun uniqueAddsNumericSuffix() {
        val existing = listOf("20261015_Mathematics", "20261015_Mathematics (2)")
        assertEquals("20261015_Mathematics (3)", DateNaming.unique("20261015_Mathematics", existing))
        assertEquals("20261015_Physics", DateNaming.unique("20261015_Physics", existing))
    }
}
