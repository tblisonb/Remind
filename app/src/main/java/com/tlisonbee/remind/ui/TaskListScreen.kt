package com.tlisonbee.remind.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tlisonbee.remind.data.Task
import java.time.LocalDateTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskListScreen(
    tasks: List<Task>,
    notificationsAllowed: Boolean,
    onOpenNotificationSettings: () -> Unit,
    onAdd: () -> Unit,
    onOpen: (String) -> Unit,
    onSetDone: (String, Boolean) -> Unit,
) {
    // Upcoming reminders first (soonest at the top), then everything else by last edit.
    val active = tasks.filter { !it.done }.sortedWith(
        compareBy<Task, LocalDateTime?>(nullsLast()) { it.nextReminderAt }.thenByDescending { it.updatedAt },
    )
    val completed = tasks.filter { it.done }.sortedByDescending { it.updatedAt }
    var showCompleted by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Remind") }) },
        floatingActionButton = {
            FloatingActionButton(onClick = onAdd) {
                Icon(Icons.Default.Add, contentDescription = "Add task")
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!notificationsAllowed) {
                item { NotificationsOffBanner(onOpenNotificationSettings) }
            }
            if (tasks.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(top = 96.dp), contentAlignment = Alignment.Center) {
                        Text("No tasks yet. Tap + to add one.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            items(active, key = { it.id }) { task ->
                TaskRow(task, onOpen, onSetDone, Modifier.animateItem())
            }
            if (completed.isNotEmpty()) {
                item(key = "completed-header") {
                    TextButton(onClick = { showCompleted = !showCompleted }, modifier = Modifier.animateItem()) {
                        Icon(
                            if (showCompleted) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = null,
                        )
                        Text("Completed (${completed.size})")
                    }
                }
                if (showCompleted) {
                    items(completed, key = { it.id }) { task ->
                        TaskRow(task, onOpen, onSetDone, Modifier.animateItem())
                    }
                }
            }
        }
    }
}

@Composable
private fun TaskRow(
    task: Task,
    onOpen: (String) -> Unit,
    onSetDone: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val reminder = reminderSummary(context, task)
    // Reminders that already fired (or were never armed) are shown muted.
    val reminderPending = task.nextReminderAt != null && !task.done
    Card(modifier = modifier.fillMaxWidth().clickable { onOpen(task.id) }) {
        ListItem(
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            leadingContent = {
                Checkbox(checked = task.done, onCheckedChange = { onSetDone(task.id, it) })
            },
            headlineContent = {
                Text(
                    task.title.ifBlank { "(untitled)" },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textDecoration = if (task.done) TextDecoration.LineThrough else null,
                )
            },
            supportingContent = if (task.notes.isBlank() && reminder == null) null else {
                {
                    Column {
                        if (task.notes.isNotBlank()) {
                            Text(task.notes, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        if (reminder != null) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                val color = if (reminderPending) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.outline
                                }
                                Icon(
                                    Icons.Default.Notifications,
                                    contentDescription = null,
                                    tint = color,
                                    modifier = Modifier.size(16.dp).padding(end = 4.dp),
                                )
                                Text(reminder, style = MaterialTheme.typography.labelMedium, color = color)
                            }
                        }
                    }
                }
            },
        )
    }
}

@Composable
private fun NotificationsOffBanner(onOpenSettings: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Notifications are off, so reminders won't appear.",
                modifier = Modifier.weight(1f).padding(vertical = 12.dp),
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            TextButton(onClick = onOpenSettings) { Text("Settings") }
        }
    }
}
