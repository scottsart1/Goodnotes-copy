package com.inkwell.notes.library

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

class RelativeTimeTest {
    private val zone = ZoneId.of("Europe/Berlin")
    private val locale = Locale.US

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0): Long =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    private val now = at(2026, 10, 16, 15, 0)

    private fun phrase(then: Long, n: Long = now) = RelativeTime.phrase(then, n, zone, locale)

    @Test fun underAMinuteIsJustNow() {
        assertEquals("just now", phrase(now))
        assertEquals("just now", phrase(now - 59_000))
    }

    @Test fun smallClockSkewIntoTheFutureIsJustNow() {
        assertEquals("just now", phrase(now + 30_000))
        assertEquals("just now", phrase(now + 20 * 60_000))
    }

    @Test fun farFutureShowsTheDate() {
        assertEquals("Dec 1", phrase(at(2026, 12, 1, 9)))
    }

    @Test fun minutes() {
        assertEquals("1 min ago", phrase(now - 60_000))
        assertEquals("59 min ago", phrase(now - 59 * 60_000 - 59_000))
    }

    @Test fun hoursOnTheSameDay() {
        assertEquals("1h ago", phrase(now - 60 * 60_000))
        assertEquals("2h ago", phrase(at(2026, 10, 16, 12, 30)))
        assertEquals("14h ago", phrase(at(2026, 10, 16, 0, 30)))
    }

    @Test fun shortlyBeforeMidnightStillReadsInHours() {
        val oneAm = at(2026, 10, 16, 1, 0)
        assertEquals("3h ago", phrase(at(2026, 10, 15, 22, 0), oneAm))
    }

    @Test fun previousCalendarDayIsYesterday() {
        assertEquals("yesterday", phrase(at(2026, 10, 15, 23, 0)))
        assertEquals("yesterday", phrase(at(2026, 10, 15, 8, 0)))
    }

    @Test fun daysWithinAWeek() {
        assertEquals("2 days ago", phrase(at(2026, 10, 14, 18, 0)))
        assertEquals("6 days ago", phrase(at(2026, 10, 10, 9, 0)))
    }

    @Test fun olderThisYearShowsMonthAndDay() {
        assertEquals("Oct 9", phrase(at(2026, 10, 9, 9, 0)))
        assertEquals("Jan 3", phrase(at(2026, 1, 3, 9, 0)))
    }

    @Test fun otherYearsIncludeTheYear() {
        assertEquals("Dec 31, 2025", phrase(at(2025, 12, 31, 9, 0)))
    }

    @Test fun capitalizedForStandaloneUse() {
        assertEquals("Yesterday", RelativeTime.capitalized("yesterday"))
        assertEquals("2h ago", RelativeTime.capitalized("2h ago"))
    }
}
