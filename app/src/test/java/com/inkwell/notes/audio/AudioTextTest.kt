package com.inkwell.notes.audio

import com.inkwell.notes.audio.logic.AudioText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class AudioTextTest {
    @Test
    fun clockFormat() {
        assertEquals("0:00", AudioText.clock(0))
        assertEquals("0:00", AudioText.clock(-5))
        assertEquals("0:07", AudioText.clock(7_999))
        assertEquals("18:40", AudioText.clock((18 * 60 + 40) * 1000L))
        assertEquals("59:59", AudioText.clock(3_599_000))
        assertEquals("1:00:00", AudioText.clock(3_600_000))
        assertEquals("1:02:03", AudioText.clock((3600 + 120 + 3) * 1000L))
    }

    @Test
    fun defaultTitleUsesCountAndTime() {
        val zone = ZoneId.of("Europe/Berlin")
        val at = LocalDateTime.of(2026, 10, 16, 14, 5).atZone(zone).toInstant().toEpochMilli()
        assertEquals("Recording 3 · 14:05", AudioText.defaultTitle(3, at, zone))
        assertEquals("Recording 1 · 14:05", AudioText.defaultTitle(0, at, zone))
    }

    @Test
    fun recoveredSuffixIsIdempotent() {
        val once = AudioText.recoveredTitle("Recording 2 · 09:12")
        assertEquals("Recording 2 · 09:12 (recovered)", once)
        assertEquals(once, AudioText.recoveredTitle(once))
        assertTrue(AudioText.isRecoveredTitle(once))
        assertFalse(AudioText.isRecoveredTitle("Lecture"))
    }

    @Test
    fun minutesRoundUp() {
        assertEquals(1, AudioText.minutesCeil(0))
        assertEquals(1, AudioText.minutesCeil(59_000))
        assertEquals(2, AudioText.minutesCeil(60_001))
        assertEquals(19, AudioText.minutesCeil((18 * 60 + 40) * 1000L))
    }

    @Test
    fun usdAndTokens() {
        assertNull(AudioText.usd(null))
        assertEquals("$0.00", AudioText.usd(0.0))
        assertEquals("< $0.01", AudioText.usd(0.004))
        assertEquals("$0.11", AudioText.usd(0.112))
        assertEquals("$12.50", AudioText.usd(12.5))
        assertEquals("about 350 tokens", AudioText.tokens(350))
        assertEquals("about 1.1k tokens", AudioText.tokens(1_100))
        assertEquals("about 2.5M tokens", AudioText.tokens(2_500_000))
    }
}
