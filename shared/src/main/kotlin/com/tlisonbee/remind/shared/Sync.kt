package com.tlisonbee.remind.shared

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A task as it travels between devices and the server. Device-local scheduling state (when the
 * next alarm fires) is deliberately absent; each device derives it from [remindAt] and the repeat rule.
 */
@Serializable
data class TaskDto(
    val id: String,
    val title: String,
    val notes: String = "",
    val done: Boolean = false,
    /** First occurrence as local wall-clock time (ISO-8601, e.g. "2026-10-14T09:00"), or null. */
    val remindAt: String? = null,
    val repeatUnit: RepeatUnit = RepeatUnit.NONE,
    val repeatInterval: Int = 1,
    val createdAt: Long,
    /** Epoch millis of the last user edit; the newer edit wins when two devices change a task. */
    val updatedAt: Long,
    val deleted: Boolean = false,
)

/**
 * One round trip: the client pushes its local changes and pulls everything the server has
 * accepted after [since], a cursor from a previous [SyncResponse] (0 on first sync).
 */
@Serializable
data class SyncRequest(
    val since: Long,
    val changes: List<TaskDto>,
)

@Serializable
data class SyncResponse(
    /** Pass as [SyncRequest.since] next time. */
    val cursor: Long,
    val changes: List<TaskDto>,
)

/** JSON settings for the sync API, used by both ends. Unknown fields are ignored so either side can add some. */
val SyncJson = Json { ignoreUnknownKeys = true }

object Limits {
    const val MAX_TITLE = 1_000
    const val MAX_NOTES = 100_000
    const val MAX_INTERVAL = 999
    const val MAX_CHANGES_PER_SYNC = 5_000
}
