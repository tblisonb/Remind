package com.tlisonbee.remind.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.tlisonbee.remind.sync.SyncConfig
import com.tlisonbee.remind.shared.normalizeServerUrl
import com.tlisonbee.remind.sync.SyncStatus
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncSettingsScreen(
    status: SyncStatus,
    savedServerUrl: String?,
    onConnect: (SyncConfig) -> Unit,
    onSyncNow: () -> Unit,
    onDisconnect: () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    var url by rememberSaveable { mutableStateOf(savedServerUrl.orEmpty()) }
    var token by rememberSaveable { mutableStateOf("") }
    var showToken by rememberSaveable { mutableStateOf(false) }
    val normalizedUrl = normalizeServerUrl(url)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sync") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "Sync tasks with your Remind server so they're also available on the web. " +
                    "Reminders always fire from this phone, even when the server can't be reached.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SyncStatusLine(status)

            if (status.configured) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onSyncNow, enabled = !status.inProgress) { Text("Sync now") }
                    OutlinedButton(onClick = onDisconnect) { Text("Disconnect") }
                }
            }

            Text(
                if (status.configured) "Change server" else "Connect to a server",
                style = MaterialTheme.typography.titleMedium,
            )
            OutlinedTextField(
                value = url,
                onValueChange = { url = it.trim() },
                label = { Text("Server URL") },
                placeholder = { Text("https://my-pc.example.ts.net") },
                singleLine = true,
                isError = url.isNotEmpty() && normalizedUrl == null,
                supportingText = if (url.isNotEmpty() && normalizedUrl == null) {
                    { Text("Must start with https://") }
                } else {
                    null
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = token,
                onValueChange = { token = it.trim() },
                label = { Text("Access token") },
                singleLine = true,
                visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    TextButton(onClick = { showToken = !showToken }) { Text(if (showToken) "Hide" else "Show") }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { onConnect(SyncConfig(normalizedUrl!!, token)) },
                enabled = normalizedUrl != null && token.isNotEmpty() && !status.inProgress,
            ) {
                Text(if (status.configured) "Save and sync" else "Connect")
            }
        }
    }
}

@Composable
private fun SyncStatusLine(status: SyncStatus) {
    val context = LocalContext.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (status.inProgress) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        Column {
            Text(
                when {
                    !status.configured -> "Not connected"
                    status.inProgress -> "Syncing…"
                    status.lastSuccessAt != null -> {
                        val at = LocalDateTime.ofInstant(Instant.ofEpochMilli(status.lastSuccessAt), ZoneId.systemDefault())
                        "Last synced ${formatDateTime(context, at)}"
                    }
                    else -> "Not synced yet"
                },
                style = MaterialTheme.typography.bodyLarge,
            )
            if (status.configured && status.lastError != null && !status.inProgress) {
                Text(status.lastError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

