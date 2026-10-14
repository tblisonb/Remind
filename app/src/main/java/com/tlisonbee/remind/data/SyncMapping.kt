package com.tlisonbee.remind.data

import com.tlisonbee.remind.shared.TaskDto
import java.time.LocalDateTime

fun Task.toDto() = TaskDto(
    id = id,
    title = title,
    notes = notes,
    done = done,
    remindAt = remindAt?.toString(),
    repeatUnit = repeatUnit,
    repeatInterval = repeatInterval,
    createdAt = createdAt,
    updatedAt = updatedAt,
    deleted = deleted,
)

/** nextReminderAt is left for the repository to derive; dirty is false since it came from the server. */
fun TaskDto.toTask() = Task(
    id = id,
    title = title,
    notes = notes,
    done = done,
    remindAt = remindAt?.let(LocalDateTime::parse),
    repeatUnit = repeatUnit,
    repeatInterval = repeatInterval,
    createdAt = createdAt,
    updatedAt = updatedAt,
    deleted = deleted,
    dirty = false,
)
