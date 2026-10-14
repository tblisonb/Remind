package com.tlisonbee.remind

import android.app.Application
import android.content.Context
import com.tlisonbee.remind.data.AppDatabase
import com.tlisonbee.remind.data.TaskRepository
import com.tlisonbee.remind.reminders.Notifier
import com.tlisonbee.remind.reminders.ReminderScheduler
import com.tlisonbee.remind.sync.SyncManager
import com.tlisonbee.remind.sync.SyncScheduler
import com.tlisonbee.remind.sync.SyncSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class RemindApp : Application() {
    /** For work that must outlive a screen, e.g. saving a task while navigating away. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val notifier by lazy { Notifier(this) }

    private val syncSettings by lazy { SyncSettings(this) }

    val repository by lazy {
        TaskRepository(AppDatabase.create(this).taskDao(), ReminderScheduler(this), notifier) {
            if (syncSettings.config != null) SyncScheduler.requestSoon(this)
        }
    }

    val syncManager by lazy {
        SyncManager(
            repository,
            syncSettings,
            onConnectionChanged = { connected ->
                if (connected) SyncScheduler.schedulePeriodic(this) else SyncScheduler.cancelAll(this)
            },
            onSyncProblem = { problem ->
                if (problem != null) notifier.showSyncProblem(problem) else notifier.cancelSyncProblem()
            },
        )
    }

    override fun onCreate() {
        super.onCreate()
        notifier.createChannels()
        if (syncSettings.config != null) SyncScheduler.schedulePeriodic(this)
    }
}

val Context.app: RemindApp get() = applicationContext as RemindApp
