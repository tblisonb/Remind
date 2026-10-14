package com.tlisonbee.remind.ui

import android.content.Context
import android.text.format.DateFormat
import com.tlisonbee.remind.data.Task
import com.tlisonbee.remind.shared.repeatLabel
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

fun formatDate(date: LocalDate): String {
    val today = LocalDate.now()
    return when (date) {
        today -> "Today"
        today.plusDays(1) -> "Tomorrow"
        today.minusDays(1) -> "Yesterday"
        else -> {
            val pattern = if (date.year == today.year) "EEE, MMM d" else "EEE, MMM d, yyyy"
            date.format(DateTimeFormatter.ofPattern(pattern))
        }
    }
}

/** Follows the system's 12/24-hour setting. */
fun formatTime(context: Context, time: LocalTime): String {
    val pattern = if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a"
    return time.format(DateTimeFormatter.ofPattern(pattern))
}

fun formatDateTime(context: Context, dateTime: LocalDateTime): String =
    "${formatDate(dateTime.toLocalDate())}, ${formatTime(context, dateTime.toLocalTime())}"

/** Summary for the task list, e.g. "Tomorrow, 9:00 AM · Every week". */
fun reminderSummary(context: Context, task: Task): String? {
    val time = task.nextReminderAt ?: task.remindAt ?: return null
    val repeat = if (task.isRecurring) " · ${repeatLabel(task.repeatUnit, task.repeatInterval)}" else ""
    return formatDateTime(context, time) + repeat
}
