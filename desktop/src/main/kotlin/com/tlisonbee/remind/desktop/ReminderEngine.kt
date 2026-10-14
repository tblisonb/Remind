package com.tlisonbee.remind.desktop

import com.tlisonbee.remind.shared.Recurrence
import com.tlisonbee.remind.shared.RepeatUnit
import com.tlisonbee.remind.shared.TaskDto
import kotlinx.serialization.Serializable
import java.time.Duration
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/** A synced task plus this machine's own reminder bookkeeping. */
@Serializable
data class LocalTask(
    val task: TaskDto,
    /** When this machine should next notify (ISO local date-time); device-local, never synced. */
    val nextReminderAt: String? = null,
    /** Changed here (e.g. "Done" on a notification) and not yet accepted by the server. */
    val dirty: Boolean = false,
)

@Serializable
data class DesktopState(
    val cursor: Long = 0,
    val tasks: Map<String, LocalTask> = emptyMap(),
)

interface Notifier {
    fun show(task: TaskDto)
    fun close(taskId: String)
}

/**
 * The desktop's copy of the tasks and its reminder schedule. Same rules as the phone: reminders
 * come from the anchor time and repeat rule, the newer edit wins when syncing, and reminders fire
 * from local state, so they work without the server.
 *
 * Every method is synchronized: notification actions arrive on D-Bus threads.
 */
class ReminderEngine(
    initial: DesktopState,
    private val notifier: Notifier,
    private val clock: () -> LocalDateTime = LocalDateTime::now,
    private val millis: () -> Long = System::currentTimeMillis,
    /** Persists the state after every change. */
    private val onChange: (DesktopState) -> Unit = {},
) {
    private var state = initial

    val cursor: Long @Synchronized get() = state.cursor

    @Synchronized
    fun task(id: String): TaskDto? = state.tasks[id]?.task?.takeUnless { it.deleted }

    @Synchronized
    fun activeTasks(): List<LocalTask> = state.tasks.values.filter { !it.task.deleted && !it.task.done }

    @Synchronized
    fun pending(): List<TaskDto> = state.tasks.values.filter { it.dirty }.map { it.task }

    /** Clears the dirty flag on pushed tasks unless they changed again while the sync ran. */
    @Synchronized
    fun markSynced(pushed: List<TaskDto>) = update { tasks ->
        for (dto in pushed) {
            val local = tasks[dto.id] ?: continue
            if (local.task.updatedAt == dto.updatedAt) tasks[dto.id] = local.copy(dirty = false)
        }
    }

    /** Merges server changes (newer edit wins) and moves the cursor forward. */
    @Synchronized
    fun applyRemote(changes: List<TaskDto>, cursor: Long) = update(cursor) { tasks ->
        for (incoming in changes) {
            val local = tasks[incoming.id]
            if (local != null && local.task.updatedAt >= incoming.updatedAt) continue
            val reminderChanged = local == null || local.task.reminderDiffers(incoming)
            val next = when {
                incoming.done || incoming.deleted -> null
                reminderChanged -> firstReminder(incoming)
                else -> local.nextReminderAt
            }
            tasks[incoming.id] = LocalTask(incoming, next, dirty = false)
            if (incoming.done || incoming.deleted) notifier.close(incoming.id)
        }
    }

    /** Shows every reminder that is due and schedules each task's next occurrence. */
    @Synchronized
    fun fireDue() {
        val now = clock()
        val due = state.tasks.values.filter { local ->
            val at = local.nextReminderAt?.let(LocalDateTime::parse)
            at != null && !at.isAfter(now) && !local.task.done && !local.task.deleted
        }
        if (due.isEmpty()) return
        update { tasks ->
            for (local in due) {
                notifier.show(local.task)
                val dueAt = LocalDateTime.parse(local.nextReminderAt)
                val next = if (local.task.isRecurring) {
                    Recurrence.firstAtOrAfter(
                        LocalDateTime.parse(local.task.remindAt), local.task.repeatUnit, local.task.repeatInterval,
                        notBefore = maxOf(now, dueAt).plusSeconds(1),
                    )
                } else {
                    null
                }
                tasks[local.task.id] = local.copy(nextReminderAt = next?.toString())
            }
        }
    }

    /** How long until the next reminder is due, or null if none is scheduled. */
    @Synchronized
    fun timeUntilNextDue(): Duration? = state.tasks.values
        .filter { !it.task.done && !it.task.deleted }
        .mapNotNull { it.nextReminderAt?.let(LocalDateTime::parse) }
        .minOrNull()
        ?.let { Duration.between(clock(), it) }

    /** Local only, like on the phone: the regular next occurrence still wins if it comes first. */
    @Synchronized
    fun snooze(id: String, minutes: Long) = update { tasks ->
        val local = tasks[id] ?: return@update
        val until = clock().plusMinutes(minutes)
        val current = local.nextReminderAt?.let(LocalDateTime::parse)
        val next = if (current != null && current.isBefore(until)) current else until
        tasks[id] = local.copy(nextReminderAt = next.toString())
        notifier.close(id)
    }

    /** Completes a one-time task (synced to other devices); for a recurring one it only dismisses. */
    @Synchronized
    fun done(id: String) = update { tasks ->
        notifier.close(id)
        val local = tasks[id] ?: return@update
        if (local.task.isRecurring || local.task.done) return@update
        val updated = local.task.copy(done = true, updatedAt = maxOf(millis(), local.task.updatedAt + 1))
        tasks[id] = LocalTask(updated, nextReminderAt = null, dirty = true)
    }

    private inline fun update(cursor: Long = state.cursor, block: (MutableMap<String, LocalTask>) -> Unit) {
        val tasks = state.tasks.toMutableMap()
        block(tasks)
        state = DesktopState(cursor, tasks)
        onChange(state)
    }

    private fun firstReminder(task: TaskDto): String? {
        val anchor = task.remindAt?.let(LocalDateTime::parse) ?: return null
        // Minute precision, matching the phone: a reminder for the current minute still fires.
        val notBefore = clock().truncatedTo(ChronoUnit.MINUTES)
        return Recurrence.firstAtOrAfter(anchor, task.repeatUnit, task.repeatInterval, notBefore)?.toString()
    }
}

val TaskDto.isRecurring: Boolean get() = remindAt != null && repeatUnit != RepeatUnit.NONE

private fun TaskDto.reminderDiffers(other: TaskDto) =
    done != other.done ||
        deleted != other.deleted ||
        remindAt != other.remindAt ||
        repeatUnit != other.repeatUnit ||
        repeatInterval != other.repeatInterval
