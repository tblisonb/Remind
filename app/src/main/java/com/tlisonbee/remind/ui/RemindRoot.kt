package com.tlisonbee.remind.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun RemindRoot(viewModel: TasksViewModel) {
    val tasks by viewModel.tasks.collectAsStateWithLifecycle()
    val notificationsAllowed = rememberNotificationPermission()
    val context = LocalContext.current

    when (val draft = viewModel.draft) {
        null -> TaskListScreen(
            tasks = tasks,
            notificationsAllowed = notificationsAllowed,
            onOpenNotificationSettings = { context.openNotificationSettings() },
            onAdd = viewModel::newTask,
            onOpen = viewModel::openEditor,
            onSetDone = viewModel::setDone,
        )
        // Keyed so editor-local state (pickers, focus) resets when switching tasks.
        else -> key(draft.id) {
            TaskEditScreen(
                task = draft,
                onChange = viewModel::updateDraft,
                onClose = viewModel::closeEditor,
                onDelete = viewModel::deleteDraft,
            )
        }
    }
}

/** Asks for the notification permission once on launch, and re-checks whenever the app resumes. */
@Composable
private fun rememberNotificationPermission(): Boolean {
    val context = LocalContext.current
    fun check() = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED

    var granted by remember { mutableStateOf(check()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
    }
    LaunchedEffect(Unit) {
        if (!granted) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    LifecycleResumeEffect(Unit) {
        granted = check()
        onPauseOrDispose {}
    }
    return granted
}

private fun Context.openNotificationSettings() {
    startActivity(
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName),
    )
}
