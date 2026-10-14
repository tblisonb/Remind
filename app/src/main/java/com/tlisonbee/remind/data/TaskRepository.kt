package com.tlisonbee.remind.data

import com.tlisonbee.remind.reminders.Notifier
import com.tlisonbee.remind.reminders.ReminderScheduler
import com.tlisonbee.remind.shared.Recurrence
import com.tlisonbee.remind.shared.TaskDto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/**
 * Owns all task mutations so the database, scheduled alarms and visible notifications stay in sync.
 * Mutations are serialized because alarms, notification actions, sync and the UI can race each other.
 */
class TaskRepository(
    private val dao: TaskDao,
    private val scheduler: ReminderScheduler,
    private val notifier: Notifier,
    /** Called after every user edit, so it can be pushed to the server. */
    private val onLocalChange: () -> Unit = {},
) {
    private val mutex = Mutex()

    fun observeTasks(): Flow<List<Task>> = dao.observeAll()

    suspend fun get(id: String): Task? = dao.get(id)

    suspend fun save(task: Task) {
        mutex.withLock {
            val existing = dao.get(task.id)
            val reminderChanged = existing == null || existing.reminderDiffers(task)
            if (existing != null && !reminderChanged && existing.sameContentAs(task)) return

            val updated = task.copy(
                nextReminderAt = if (existing == null || reminderChanged) firstReminder(task) else existing.nextReminderAt,
                updatedAt = nextUpdatedAt(existing),
                dirty = true,
            )
            dao.upsert(updated)
            scheduler.schedule(updated)
            if (updated.done) notifier.cancel(updated.id)
        }
        onLocalChange()
    }

    suspend fun setDone(id: String, done: Boolean) {
        val task = dao.get(id) ?: return
        save(task.copy(done = done))
    }

    suspend fun delete(id: String) {
        mutex.withLock {
            val task = dao.get(id) ?: return
            dao.upsert(task.copy(deleted = true, nextReminderAt = null, updatedAt = nextUpdatedAt(task), dirty = true))
            scheduler.cancel(id)
            notifier.cancel(id)
        }
        onLocalChange()
    }

    /** Called by the alarm: show the notification and schedule the next occurrence, if any. */
    suspend fun onReminderDue(id: String) = mutex.withLock {
        val task = dao.get(id) ?: return@withLock
        val due = task.nextReminderAt
        if (task.done || task.deleted || due == null) return@withLock
        val now = LocalDateTime.now()
        if (due.isAfter(now.plusMinutes(1))) {
            // Stale alarm (e.g. the task was edited or the clock changed); just re-arm it.
            scheduler.schedule(task)
            return@withLock
        }

        notifier.show(task)
        val next = if (task.isRecurring) {
            Recurrence.firstAtOrAfter(
                task.remindAt!!, task.repeatUnit, task.repeatInterval,
                notBefore = maxOf(now, due).plusSeconds(1),
            )
        } else {
            null
        }
        // nextReminderAt is device-local scheduling state, so updatedAt (used for sync) is left alone.
        val updated = task.copy(nextReminderAt = next)
        dao.upsert(updated)
        scheduler.schedule(updated)
    }

    suspend fun snooze(id: String, minutes: Long) = mutex.withLock {
        val task = dao.get(id) ?: return@withLock
        val snoozeUntil = LocalDateTime.now().plusMinutes(minutes)
        // A recurring task's next regular occurrence wins if it comes before the snooze ends.
        val next = task.nextReminderAt?.takeIf { it.isBefore(snoozeUntil) } ?: snoozeUntil
        val updated = task.copy(nextReminderAt = next)
        dao.upsert(updated)
        scheduler.schedule(updated)
        notifier.cancel(id)
    }

    /** "Done" on a notification completes a one-time task; for a recurring one it only dismisses. */
    suspend fun doneFromNotification(id: String) {
        val task = dao.get(id) ?: return
        if (task.isRecurring) notifier.cancel(id) else setDone(id, true)
    }

    /** Re-arms every alarm; needed after reboot, app update, time zone change or force-stop. */
    suspend fun rescheduleAll() = mutex.withLock {
        dao.getScheduled().forEach(scheduler::schedule)
    }

    // ---- Sync ----

    /** Local edits not yet accepted by the server (including deletions). */
    suspend fun pendingChanges(): List<Task> = dao.getDirty()

    /** Clears the dirty flag on pushed tasks, unless they were edited again while the sync ran. */
    suspend fun markSynced(pushed: List<Task>) {
        pushed.forEach { dao.markClean(it.id, it.updatedAt) }
    }

    /** After pointing at a different server, everything local needs pushing to it. */
    suspend fun markAllDirty() = dao.markAllDirty()

    /** Merges tasks from the server; the newer edit wins, and alarms follow the result. */
    suspend fun applyRemote(changes: List<TaskDto>) = mutex.withLock {
        for (dto in changes) {
            val local = dao.get(dto.id)
            if (local != null && local.updatedAt >= dto.updatedAt) continue
            val incoming = dto.toTask()
            val reminderChanged = local == null || local.reminderDiffers(incoming) || local.deleted != incoming.deleted
            val updated = incoming.copy(
                nextReminderAt = when {
                    incoming.deleted -> null
                    local == null || reminderChanged -> firstReminder(incoming)
                    else -> local.nextReminderAt
                },
                dirty = false,
            )
            dao.upsert(updated)
            scheduler.schedule(updated)
            if (updated.done || updated.deleted) notifier.cancel(updated.id)
        }
    }

    private fun Task.reminderDiffers(other: Task) =
        done != other.done ||
            remindAt != other.remindAt ||
            repeatUnit != other.repeatUnit ||
            repeatInterval != other.repeatInterval

    /**
     * Ignores bookkeeping fields: an alarm may have advanced nextReminderAt, or an earlier save
     * bumped updatedAt, while the task was open in the editor.
     */
    private fun Task.sameContentAs(other: Task) =
        copy(nextReminderAt = null, updatedAt = 0, dirty = false) ==
            other.copy(nextReminderAt = null, updatedAt = 0, dirty = false)

    /**
     * Always later than the version being replaced, so an edit wins over the version it was based
     * on even if this phone's clock is behind the device that wrote that version.
     */
    private fun nextUpdatedAt(existing: Task?) =
        maxOf(System.currentTimeMillis(), (existing?.updatedAt ?: 0) + 1)

    private fun firstReminder(task: Task): LocalDateTime? {
        val anchor = task.remindAt ?: return null
        if (task.done || task.deleted) return null
        // Reminders have minute precision, so one set for the current minute still fires.
        val notBefore = LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES)
        return Recurrence.firstAtOrAfter(anchor, task.repeatUnit, task.repeatInterval, notBefore)
    }
}
