package com.tlisonbee.remind.desktop

import com.tlisonbee.remind.shared.RepeatUnit
import com.tlisonbee.remind.shared.TaskDto
import java.time.LocalDateTime
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReminderEngineTest {
    private class FakeNotifier : Notifier {
        val shown = mutableListOf<String>()
        val closed = mutableListOf<String>()
        override fun show(task: TaskDto) { shown += task.title }
        override fun close(taskId: String) { closed += taskId }
    }

    private var now = LocalDateTime.parse("2026-10-14T08:00")
    private var millis = 1_000L
    private val notifier = FakeNotifier()
    private var saved: DesktopState? = null
    private val engine = ReminderEngine(DesktopState(), notifier, { now }, { millis }, { saved = it })

    private fun task(
        title: String,
        remindAt: String? = null,
        unit: RepeatUnit = RepeatUnit.NONE,
        updatedAt: Long = 100,
    ) = TaskDto(
        id = UUID.randomUUID().toString(),
        title = title,
        remindAt = remindAt,
        repeatUnit = unit,
        createdAt = 1,
        updatedAt = updatedAt,
    )

    @Test
    fun firesOneTimeReminderOnceWhenDue() {
        engine.applyRemote(listOf(task("Call", "2026-10-14T09:00")), cursor = 1)
        engine.fireDue()
        assertEquals(emptyList(), notifier.shown)

        now = LocalDateTime.parse("2026-10-14T09:00:05")
        engine.fireDue()
        engine.fireDue()
        assertEquals(listOf("Call"), notifier.shown)
        assertNull(engine.timeUntilNextDue())
        assertEquals(1, saved!!.cursor)
    }

    @Test
    fun recurringReminderSchedulesNextOccurrence() {
        engine.applyRemote(listOf(task("Medicine", "2026-10-01T20:00", RepeatUnit.DAY)), cursor = 1)
        now = LocalDateTime.parse("2026-10-14T20:00:01")
        engine.fireDue()
        assertEquals(listOf("Medicine"), notifier.shown)
        assertEquals(LocalDateTime.parse("2026-10-15T20:00"), now.plus(engine.timeUntilNextDue()))
    }

    @Test
    fun pastOneTimeReminderFromServerDoesNotFire() {
        engine.applyRemote(listOf(task("Old", "2026-10-01T09:00")), cursor = 1)
        engine.fireDue()
        assertEquals(emptyList(), notifier.shown)
    }

    @Test
    fun newerLocalEditIsKeptOverOlderServerVersion() {
        val t = task("Call", "2026-10-14T09:00")
        engine.applyRemote(listOf(t), cursor = 1)
        now = LocalDateTime.parse("2026-10-14T09:00")
        millis = 5_000
        engine.done(t.id)
        assertEquals(listOf(true), engine.pending().map { it.done })

        // An older copy arriving from the server doesn't undo the local "done".
        engine.applyRemote(listOf(t), cursor = 2)
        assertEquals(true, engine.pending().single().done)

        engine.markSynced(engine.pending())
        assertEquals(emptyList(), engine.pending())
    }

    @Test
    fun doneOnRecurringTaskOnlyDismisses() {
        val t = task("Trash", "2026-10-14T09:00", RepeatUnit.WEEK)
        engine.applyRemote(listOf(t), cursor = 1)
        engine.done(t.id)
        assertEquals(listOf(t.id), notifier.closed)
        assertEquals(emptyList(), engine.pending())
    }

    @Test
    fun snoozeNeverDelaysPastTheNextRegularOccurrence() {
        val t = task("Stretch", "2026-10-14T08:30", RepeatUnit.DAY)
        engine.applyRemote(listOf(t), cursor = 1)
        engine.snooze(t.id, minutes = 60) // regular 08:30 comes before 09:00
        assertEquals(LocalDateTime.parse("2026-10-14T08:30"), now.plus(engine.timeUntilNextDue()))

        now = LocalDateTime.parse("2026-10-14T08:30:01")
        engine.fireDue()
        engine.snooze(t.id, minutes = 60)
        assertEquals(LocalDateTime.parse("2026-10-14T09:30:01"), now.plus(engine.timeUntilNextDue()))
    }

    @Test
    fun remoteCompletionOrDeletionClosesNotification() {
        val t = task("Call", "2026-10-14T08:00")
        engine.applyRemote(listOf(t), cursor = 1)
        engine.fireDue()
        engine.applyRemote(listOf(t.copy(deleted = true, updatedAt = 200)), cursor = 2)
        assertTrue(t.id in notifier.closed)
        assertNull(engine.task(t.id))
        assertNull(engine.timeUntilNextDue())
    }
}
