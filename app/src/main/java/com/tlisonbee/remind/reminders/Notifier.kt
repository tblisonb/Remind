package com.tlisonbee.remind.reminders

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.tlisonbee.remind.R
import com.tlisonbee.remind.data.Task
import com.tlisonbee.remind.sync.SyncProblem
import com.tlisonbee.remind.ui.MainActivity
import com.tlisonbee.remind.ui.formatDateTime
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

const val SNOOZE_MINUTES = 60L

class Notifier(private val context: Context) {
    private val manager = NotificationManagerCompat.from(context)

    fun createChannels() {
        val reminders = NotificationChannel(CHANNEL_ID, "Reminders", NotificationManager.IMPORTANCE_HIGH)
            .apply { description = "Task reminders" }
        // Low importance: no sound or pop-up, just an entry in the notification shade.
        val sync = NotificationChannel(SYNC_CHANNEL_ID, "Sync problems", NotificationManager.IMPORTANCE_LOW)
            .apply { description = "Shown when Remind hasn't been able to sync for a long time" }
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannels(listOf(reminders, sync))
    }

    @SuppressLint("MissingPermission") // checked by canNotify()
    fun show(task: Task) {
        if (!canNotify()) return

        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java)
                .setData(taskUri(task.id))
                .putExtra(MainActivity.EXTRA_TASK_ID, task.id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(task.title.ifBlank { "Reminder" })
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open)
            .setAutoCancel(true)
            .addAction(0, "Done", actionIntent(ReminderReceiver.ACTION_DONE, task.id))
            .addAction(0, "Snooze 1h", actionIntent(ReminderReceiver.ACTION_SNOOZE, task.id))
        if (task.notes.isNotBlank()) {
            builder.setContentText(task.notes)
                .setStyle(NotificationCompat.BigTextStyle().bigText(task.notes))
        }
        // Tagging by task id gives every task its own notification without hash collisions.
        manager.notify(task.id, NOTIFICATION_ID, builder.build())
    }

    fun cancel(taskId: String) = manager.cancel(taskId, NOTIFICATION_ID)

    @SuppressLint("MissingPermission") // checked by canNotify()
    fun showSyncProblem(problem: SyncProblem) {
        if (!canNotify()) return
        val lastOk = LocalDateTime.ofInstant(Instant.ofEpochMilli(problem.lastOkAt), ZoneId.systemDefault())
        val text = "Reminders still work on this phone. Is Tailscale connected?" +
            problem.error?.let { "\n\nLast error: $it" }.orEmpty()
        val open = PendingIntent.getActivity(
            context,
            1,
            Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_SYNC_SETTINGS, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, SYNC_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Not synced since ${formatDateTime(context, lastOk)}")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        manager.notify(SYNC_TAG, NOTIFICATION_ID, notification)
    }

    fun cancelSyncProblem() = manager.cancel(SYNC_TAG, NOTIFICATION_ID)

    private fun canNotify() = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED

    private fun actionIntent(action: String, taskId: String) = PendingIntent.getBroadcast(
        context,
        0,
        ReminderReceiver.intent(context, action, taskId),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private companion object {
        const val CHANNEL_ID = "reminders"
        const val SYNC_CHANNEL_ID = "sync"
        const val SYNC_TAG = "sync"
        const val NOTIFICATION_ID = 1
    }
}
