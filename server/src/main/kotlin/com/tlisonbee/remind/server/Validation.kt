package com.tlisonbee.remind.server

import com.tlisonbee.remind.shared.Limits
import com.tlisonbee.remind.shared.SyncRequest
import com.tlisonbee.remind.shared.TaskDto
import io.ktor.server.plugins.BadRequestException
import java.time.LocalDateTime
import java.time.format.DateTimeParseException
import java.util.UUID

fun SyncRequest.validate() {
    if (since < 0) throw BadRequestException("since must be >= 0")
    if (changes.size > Limits.MAX_CHANGES_PER_SYNC) throw BadRequestException("Too many changes in one sync")
    changes.forEach { it.validate() }
}

fun TaskDto.validate() {
    fun fail(message: String): Nothing = throw BadRequestException("Task $id: $message")
    try {
        UUID.fromString(id)
    } catch (_: IllegalArgumentException) {
        fail("id must be a UUID")
    }
    if (title.length > Limits.MAX_TITLE) fail("title too long")
    if (notes.length > Limits.MAX_NOTES) fail("notes too long")
    if (repeatInterval !in 1..Limits.MAX_INTERVAL) fail("repeatInterval out of range")
    if (remindAt != null) {
        try {
            LocalDateTime.parse(remindAt)
        } catch (_: DateTimeParseException) {
            fail("remindAt must be an ISO local date-time")
        }
    }
}
