package com.tlisonbee.remind.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.tlisonbee.remind.shared.RepeatUnit
import java.time.LocalDateTime
import java.util.UUID

/**
 * Reminder times are stored as [LocalDateTime] ("floating" wall-clock time) and interpreted in
 * the device's current time zone, so "9:00 every day" stays at 9:00 across DST changes and travel.
 *
 * [id], [updatedAt] and [deleted] (a tombstone instead of a hard delete) exist so the data can be
 * synced with a server later without a schema rewrite.
 */
@Entity(tableName = "tasks")
data class Task(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val title: String,
    val notes: String = "",
    val done: Boolean = false,
    /** First occurrence of the reminder; recurrences are computed from this anchor to avoid drift. */
    val remindAt: LocalDateTime? = null,
    val repeatUnit: RepeatUnit = RepeatUnit.NONE,
    val repeatInterval: Int = 1,
    /** When the alarm should next fire; null once a one-time reminder has fired. */
    val nextReminderAt: LocalDateTime? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val deleted: Boolean = false,
    /** Edited locally since the last successful sync. New in schema v2; existing rows start dirty. */
    @ColumnInfo(defaultValue = "1") val dirty: Boolean = true,
) {
    val isRecurring: Boolean get() = remindAt != null && repeatUnit != RepeatUnit.NONE
}
