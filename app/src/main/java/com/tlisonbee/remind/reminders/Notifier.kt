package com.tlisonbee.remind.reminders

import android.Manifest
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
import com.tlisonbee.remind.ui.MainActivity

const val SNOOZE_MINUTES = 60L

class Notifier(private val context: Context) {
    private val manager = NotificationManagerCompat.from(context)

    fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Reminders", NotificationManager.IMPORTANCE_HIGH)
            .apply { description = "Task reminders" }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun show(task: Task) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return

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

    private fun actionIntent(action: String, taskId: String) = PendingIntent.getBroadcast(
        context,
        0,
        ReminderReceiver.intent(context, action, taskId),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private companion object {
        const val CHANNEL_ID = "reminders"
        const val NOTIFICATION_ID = 1
    }
}
