package com.tlisonbee.remind.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime

class RecurrenceTest {
    private fun at(s: String) = LocalDateTime.parse(s)

    @Test
    fun futureAnchorIsReturnedAsIs() {
        val anchor = at("2026-10-20T09:00")
        assertEquals(anchor, Recurrence.firstAtOrAfter(anchor, RepeatUnit.DAY, 1, at("2026-10-13T12:00")))
    }

    @Test
    fun pastOneTimeReminderHasNoNextOccurrence() {
        assertNull(Recurrence.firstAtOrAfter(at("2026-10-01T09:00"), RepeatUnit.NONE, 1, at("2026-10-13T12:00")))
    }

    @Test
    fun dailyKeepsTimeOfDay() {
        assertEquals(
            at("2026-10-14T09:00"),
            Recurrence.firstAtOrAfter(at("2026-10-01T09:00"), RepeatUnit.DAY, 1, at("2026-10-13T12:00")),
        )
    }

    @Test
    fun occurrenceExactlyAtNotBeforeCounts() {
        assertEquals(
            at("2026-10-13T09:00"),
            Recurrence.firstAtOrAfter(at("2026-10-01T09:00"), RepeatUnit.DAY, 1, at("2026-10-13T09:00")),
        )
    }

    @Test
    fun everyTwoWeeksStaysOnCadence() {
        // Anchor Thu Oct 1; occurrences Oct 15, Oct 29, ...
        assertEquals(
            at("2026-10-29T08:30"),
            Recurrence.firstAtOrAfter(at("2026-10-01T08:30"), RepeatUnit.WEEK, 2, at("2026-10-16T00:00")),
        )
    }

    @Test
    fun monthlyClampsToEndOfMonthWithoutDrifting() {
        val anchor = at("2026-01-31T10:00")
        assertEquals(at("2026-02-28T10:00"), Recurrence.firstAtOrAfter(anchor, RepeatUnit.MONTH, 1, at("2026-02-01T00:00")))
        assertEquals(at("2026-03-31T10:00"), Recurrence.firstAtOrAfter(anchor, RepeatUnit.MONTH, 1, at("2026-03-01T00:00")))
        assertEquals(at("2028-02-29T10:00"), Recurrence.firstAtOrAfter(anchor, RepeatUnit.MONTH, 1, at("2028-02-01T00:00")))
    }

    @Test
    fun yearlyLeapDay() {
        val anchor = at("2024-02-29T07:00")
        assertEquals(at("2026-02-28T07:00"), Recurrence.firstAtOrAfter(anchor, RepeatUnit.YEAR, 1, at("2026-01-01T00:00")))
        assertEquals(at("2028-02-29T07:00"), Recurrence.firstAtOrAfter(anchor, RepeatUnit.YEAR, 1, at("2027-03-01T00:00")))
    }
}
