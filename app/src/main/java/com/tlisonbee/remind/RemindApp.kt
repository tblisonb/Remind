package com.tlisonbee.remind

import android.app.Application
import android.content.Context
import com.tlisonbee.remind.data.AppDatabase
import com.tlisonbee.remind.data.TaskRepository
import com.tlisonbee.remind.reminders.Notifier
import com.tlisonbee.remind.reminders.ReminderScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class RemindApp : Application() {
    /** For work that must outlive a screen, e.g. saving a task while navigating away. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val notifier by lazy { Notifier(this) }

    val repository by lazy {
        TaskRepository(AppDatabase.create(this).taskDao(), ReminderScheduler(this), notifier)
    }

    override fun onCreate() {
        super.onCreate()
        notifier.createChannel()
    }
}

val Context.app: RemindApp get() = applicationContext as RemindApp
