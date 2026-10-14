package com.tlisonbee.remind.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.tlisonbee.remind.app
import com.tlisonbee.remind.sync.SyncScheduler
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val viewModel: TasksViewModel by viewModels { TasksViewModel.Factory }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleIntent(intent)
        // Safety net: a force-stop clears all of an app's alarms until it is opened again.
        app.appScope.launch { app.repository.rescheduleAll() }
        setContent {
            RemindTheme {
                RemindRoot(viewModel)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Pick up edits made elsewhere (e.g. the web UI) whenever the app comes to the foreground.
        if (app.syncManager.serverUrl != null) SyncScheduler.requestSoon(this)
    }

    override fun onStop() {
        super.onStop()
        viewModel.saveDraft()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        intent.getStringExtra(EXTRA_TASK_ID)?.let(viewModel::openEditor)
    }

    companion object {
        const val EXTRA_TASK_ID = "taskId"
    }
}
