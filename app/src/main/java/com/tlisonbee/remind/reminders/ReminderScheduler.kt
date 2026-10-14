package com.tlisonbee.remind.reminders

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import com.tlisonbee.remind.data.Task
import java.time.ZoneId

class ReminderScheduler(private val context: Context) {
    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    // Lint only knows about SCHEDULE_EXACT_ALARM; USE_EXACT_ALARM also allows this, and we check anyway.
    @SuppressLint("MissingPermission")
    fun schedule(task: Task) {
        val at = task.nextReminderAt
        if (at == null || task.done || task.deleted) {
            cancel(task.id)
            return
        }
        val triggerAtMillis = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val intent = alarmIntent(task.id)
        // USE_EXACT_ALARM is granted at install, but fall back to an inexact alarm rather than crash.
        if (alarmManager.canScheduleExactAlarms()) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, intent)
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, intent)
        }
    }

    fun cancel(taskId: String) = alarmManager.cancel(alarmIntent(taskId))

    private fun alarmIntent(taskId: String): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        ReminderReceiver.intent(context, ReminderReceiver.ACTION_FIRE, taskId),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

/** Unique per task, so each task gets its own PendingIntent / alarm. */
fun taskUri(taskId: String) = "remind://task/$taskId".toUri()

fun Intent.taskId(): String? = data?.lastPathSegment
