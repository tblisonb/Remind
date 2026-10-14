package com.tlisonbee.remind.ui

import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.tlisonbee.remind.shared.RepeatUnit
import com.tlisonbee.remind.shared.repeatMenuLabel
import com.tlisonbee.remind.shared.unitName
import com.tlisonbee.remind.data.Task
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskEditScreen(
    task: Task,
    onChange: (Task) -> Unit,
    onClose: () -> Unit,
    onDelete: () -> Unit,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    BackHandler(onBack = onClose)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Save and go back")
                    }
                },
                actions = {
                    IconButton(onClick = { confirmDelete = true }) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete task")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState()),
        ) {
            val titleFocus = remember { FocusRequester() }
            TextField(
                value = task.title,
                onValueChange = { onChange(task.copy(title = it)) },
                placeholder = { Text("Title") },
                textStyle = MaterialTheme.typography.titleLarge,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Next,
                ),
                colors = transparentTextFieldColors(),
                modifier = Modifier.fillMaxWidth().focusRequester(titleFocus),
            )
            TextField(
                value = task.notes,
                onValueChange = { onChange(task.copy(notes = it)) },
                placeholder = { Text("Notes") },
                minLines = 3,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                colors = transparentTextFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            ReminderSection(task, onChange, Modifier.padding(horizontal = 16.dp))

            // Jump straight into typing for a brand new task.
            LaunchedEffect(Unit) {
                if (task.title.isEmpty() && task.notes.isEmpty()) titleFocus.requestFocus()
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this task?") },
            confirmButton = { TextButton(onClick = onDelete) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun transparentTextFieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = Color.Transparent,
    unfocusedContainerColor = Color.Transparent,
    focusedIndicatorColor = Color.Transparent,
    unfocusedIndicatorColor = Color.Transparent,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReminderSection(task: Task, onChange: (Task) -> Unit, modifier: Modifier = Modifier) {
    val remindAt = task.remindAt
    if (remindAt == null) {
        TextButton(
            onClick = {
                // Default to the next full hour.
                val nextHour = LocalDateTime.now().plusHours(1).truncatedTo(ChronoUnit.HOURS)
                onChange(task.copy(remindAt = nextHour))
            },
            modifier = modifier,
        ) {
            Icon(Icons.Default.Notifications, contentDescription = null, Modifier.padding(end = 8.dp))
            Text("Add reminder")
        }
        return
    }

    val context = LocalContext.current
    var pickDate by rememberSaveable { mutableStateOf(false) }
    var pickTime by rememberSaveable { mutableStateOf(false) }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Notifications, contentDescription = null, Modifier.padding(end = 8.dp))
            Text("Reminder", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = {
                onChange(task.copy(remindAt = null, repeatUnit = RepeatUnit.NONE, repeatInterval = 1))
            }) {
                Icon(Icons.Default.Close, contentDescription = "Remove reminder")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { pickDate = true }) { Text(formatDate(remindAt.toLocalDate())) }
            OutlinedButton(onClick = { pickTime = true }) { Text(formatTime(context, remindAt.toLocalTime())) }
        }
        RepeatPicker(task, onChange)
        if (!task.isRecurring && remindAt.isBefore(LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES))) {
            Text(
                "This time has already passed.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    if (pickDate) {
        // The date picker works in UTC milliseconds at the start of the selected day.
        val state = rememberDatePickerState(
            initialSelectedDateMillis = remindAt.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { millis ->
                        val date = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                        onChange(task.copy(remindAt = date.atTime(remindAt.toLocalTime())))
                    }
                    pickDate = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickDate = false }) { Text("Cancel") } },
        ) {
            DatePicker(state)
        }
    }

    if (pickTime) {
        val state = rememberTimePickerState(
            initialHour = remindAt.hour,
            initialMinute = remindAt.minute,
            is24Hour = DateFormat.is24HourFormat(context),
        )
        AlertDialog(
            onDismissRequest = { pickTime = false },
            confirmButton = {
                TextButton(onClick = {
                    onChange(task.copy(remindAt = remindAt.toLocalDate().atTime(LocalTime.of(state.hour, state.minute))))
                    pickTime = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickTime = false }) { Text("Cancel") } },
            text = { TimePicker(state) },
        )
    }
}

@Composable
private fun RepeatPicker(task: Task, onChange: (Task) -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { menuOpen = true }) {
            Text(repeatMenuLabel(task.repeatUnit))
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            RepeatUnit.entries.forEach { unit ->
                DropdownMenuItem(
                    text = { Text(repeatMenuLabel(unit)) },
                    onClick = {
                        onChange(task.copy(repeatUnit = unit))
                        menuOpen = false
                    },
                )
            }
        }
    }

    if (task.repeatUnit != RepeatUnit.NONE) {
        // Kept as text so the field can be briefly empty while typing.
        var intervalText by remember { mutableStateOf(task.repeatInterval.toString()) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Every")
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(
                value = intervalText,
                onValueChange = { text ->
                    intervalText = text.filter(Char::isDigit).take(3)
                    intervalText.toIntOrNull()?.takeIf { it >= 1 }?.let {
                        onChange(task.copy(repeatInterval = it))
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.width(72.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(unitName(task.repeatUnit, task.repeatInterval))
        }
    }
}

