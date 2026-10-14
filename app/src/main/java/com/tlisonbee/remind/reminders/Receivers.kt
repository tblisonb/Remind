package com.tlisonbee.remind.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.tlisonbee.remind.app
import kotlinx.coroutines.launch

/** Handles alarms firing and the actions on a reminder notification. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.taskId() ?: return
        val repository = context.app.repository
        launchAsync(context) {
            when (intent.action) {
                ACTION_FIRE -> repository.onReminderDue(taskId)
                ACTION_DONE -> repository.doneFromNotification(taskId)
                ACTION_SNOOZE -> repository.snooze(taskId, SNOOZE_MINUTES)
            }
        }
    }

    companion object {
        const val ACTION_FIRE = "com.tlisonbee.remind.action.FIRE"
        const val ACTION_DONE = "com.tlisonbee.remind.action.DONE"
        const val ACTION_SNOOZE = "com.tlisonbee.remind.action.SNOOZE"

        fun intent(context: Context, action: String, taskId: String): Intent =
            Intent(context, ReminderReceiver::class.java).setAction(action).setData(taskUri(taskId))
    }
}

/** Alarms don't survive reboots and are tied to absolute times, so re-arm them on these events. */
class SystemEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_TIME_CHANGED,
            -> launchAsync(context) { context.app.repository.rescheduleAll() }
        }
    }
}

private fun BroadcastReceiver.launchAsync(context: Context, block: suspend () -> Unit) {
    val pending = goAsync()
    context.app.appScope.launch {
        try {
            block()
        } finally {
            pending.finish()
        }
    }
}
