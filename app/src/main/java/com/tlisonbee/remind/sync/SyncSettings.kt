package com.tlisonbee.remind.sync

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class SyncConfig(val serverUrl: String, val token: String)

data class SyncStatus(
    val configured: Boolean = false,
    val inProgress: Boolean = false,
    val lastSuccessAt: Long? = null,
    val lastError: String? = null,
)

/**
 * Server address, token and sync cursor. The prefs file is excluded from backups
 * (see res/xml/data_extraction_rules.xml) so the token never leaves the phone.
 */
class SyncSettings(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _status = MutableStateFlow(
        SyncStatus(
            configured = config != null,
            lastSuccessAt = prefs.getLong(KEY_LAST_SUCCESS, 0).takeIf { it > 0 },
            lastError = prefs.getString(KEY_LAST_ERROR, null),
        ),
    )
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    val config: SyncConfig?
        get() {
            val url = prefs.getString(KEY_URL, null) ?: return null
            val token = prefs.getString(KEY_TOKEN, null) ?: return null
            return SyncConfig(url, token)
        }

    /** Cursor from the last sync; 0 means "send me everything". */
    var cursor: Long
        get() = prefs.getLong(KEY_CURSOR, 0)
        set(value) = prefs.edit { putLong(KEY_CURSOR, value) }

    /** Returns true if this is a different server than before (so the cursor was reset). */
    fun save(config: SyncConfig): Boolean {
        val serverChanged = this.config?.serverUrl != config.serverUrl
        prefs.edit {
            putString(KEY_URL, config.serverUrl)
            putString(KEY_TOKEN, config.token)
            if (serverChanged) {
                putLong(KEY_CURSOR, 0)
                remove(KEY_LAST_SUCCESS)
            }
            remove(KEY_LAST_ERROR)
        }
        _status.update { SyncStatus(configured = true, lastSuccessAt = if (serverChanged) null else it.lastSuccessAt) }
        return serverChanged
    }

    fun clear() {
        prefs.edit { clear() }
        _status.value = SyncStatus()
    }

    fun setInProgress(inProgress: Boolean) = _status.update { it.copy(inProgress = inProgress) }

    fun recordSuccess(at: Long) {
        prefs.edit {
            putLong(KEY_LAST_SUCCESS, at)
            remove(KEY_LAST_ERROR)
        }
        _status.update { it.copy(lastSuccessAt = at, lastError = null) }
    }

    fun recordError(message: String) {
        prefs.edit { putString(KEY_LAST_ERROR, message) }
        _status.update { it.copy(lastError = message) }
    }

    companion object {
        const val PREFS_NAME = "sync"
        private const val KEY_URL = "serverUrl"
        private const val KEY_TOKEN = "token"
        private const val KEY_CURSOR = "cursor"
        private const val KEY_LAST_SUCCESS = "lastSuccessAt"
        private const val KEY_LAST_ERROR = "lastError"
    }
}
