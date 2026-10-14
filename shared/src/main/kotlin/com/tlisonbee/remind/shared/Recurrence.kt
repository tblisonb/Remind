package com.tlisonbee.remind.shared

import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

object Recurrence {
    /**
     * The [n]th occurrence after [anchor]. Months and years are always added to the anchor (never
     * to the previous occurrence), so a reminder on Jan 31 lands on Feb 28/29 and then Mar 31.
     */
    fun occurrence(anchor: LocalDateTime, unit: RepeatUnit, interval: Int, n: Long): LocalDateTime {
        val steps = n * interval
        return when (unit) {
            RepeatUnit.NONE -> anchor
            RepeatUnit.DAY -> anchor.plusDays(steps)
            RepeatUnit.WEEK -> anchor.plusWeeks(steps)
            RepeatUnit.MONTH -> anchor.plusMonths(steps)
            RepeatUnit.YEAR -> anchor.plusYears(steps)
        }
    }

    /** First occurrence at or after [notBefore], or null if there is none (non-repeating and past). */
    fun firstAtOrAfter(
        anchor: LocalDateTime,
        unit: RepeatUnit,
        interval: Int,
        notBefore: LocalDateTime,
    ): LocalDateTime? {
        if (!anchor.isBefore(notBefore)) return anchor
        if (unit == RepeatUnit.NONE || interval < 1) return null
        val chronoUnit = when (unit) {
            RepeatUnit.DAY -> ChronoUnit.DAYS
            RepeatUnit.WEEK -> ChronoUnit.WEEKS
            RepeatUnit.MONTH -> ChronoUnit.MONTHS
            else -> ChronoUnit.YEARS
        }
        // Jump close to the target, then step forward to cover month-length clamping.
        var n = maxOf(0L, chronoUnit.between(anchor, notBefore) / interval)
        var next = occurrence(anchor, unit, interval, n)
        while (next.isBefore(notBefore)) next = occurrence(anchor, unit, interval, ++n)
        return next
    }
}
