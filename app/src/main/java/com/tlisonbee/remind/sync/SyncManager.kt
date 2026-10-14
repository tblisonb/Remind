package com.tlisonbee.remind.sync

import com.tlisonbee.remind.data.TaskRepository
import com.tlisonbee.remind.data.toDto
import com.tlisonbee.remind.shared.Limits
import com.tlisonbee.remind.shared.SyncRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import java.io.IOException

enum class SyncResult { SUCCESS, NOT_CONFIGURED, NETWORK_ERROR, FAILED }

data class SyncProblem(val lastOkAt: Long, val error: String?)

/**
 * One sync round: push dirty tasks, merge what the server sends back, advance the cursor.
 * Safe to retry at any point: the server ignores versions it already has.
 */
class SyncManager(
    private val repository: TaskRepository,
    private val settings: SyncSettings,
    /** Starts/stops background syncing (kept out of here so this class has no Android dependencies). */
    private val onConnectionChanged: (connected: Boolean) -> Unit = {},
    /** Shows (with the last good sync time and error) or clears the "hasn't synced" notification. */
    private val onSyncProblem: (problem: SyncProblem?) -> Unit = {},
    private val client: SyncClient = SyncClient(),
) {
    private val mutex = Mutex()

    val status get() = settings.status

    val serverUrl: String? get() = settings.config?.serverUrl

    suspend fun configure(config: SyncConfig) {
        if (settings.save(config)) repository.markAllDirty()
        onConnectionChanged(true)
    }

    fun disconnect() {
        settings.clear()
        onConnectionChanged(false)
        onSyncProblem(null)
    }

    suspend fun sync(): SyncResult = mutex.withLock {
        val config = settings.config ?: return SyncResult.NOT_CONFIGURED
        val result = syncLocked(config)
        val action = SyncHealth.afterSync(
            success = result == SyncResult.SUCCESS,
            lastOkAt = settings.lastOkAt,
            now = System.currentTimeMillis(),
            alreadyShown = settings.staleNotified,
        )
        when (action) {
            SyncHealth.Action.SHOW -> {
                onSyncProblem(SyncProblem(settings.lastOkAt!!, settings.status.value.lastError))
                settings.staleNotified = true
            }
            SyncHealth.Action.CANCEL -> {
                onSyncProblem(null)
                settings.staleNotified = false
            }
            SyncHealth.Action.NONE -> {}
        }
        result
    }

    private suspend fun syncLocked(config: SyncConfig): SyncResult {
        settings.setInProgress(true)
        try {
            val pending = repository.pendingChanges()
            // Large backlogs (e.g. the first sync) go up in batches.
            for (batch in pending.chunked(Limits.MAX_CHANGES_PER_SYNC).ifEmpty { listOf(emptyList()) }) {
                val request = SyncRequest(since = settings.cursor, changes = batch.map { it.toDto() })
                val response = withContext(Dispatchers.IO) { client.sync(config, request) }
                repository.applyRemote(response.changes)
                repository.markSynced(batch)
                settings.cursor = response.cursor
            }
            settings.recordSuccess(System.currentTimeMillis())
            return SyncResult.SUCCESS
        } catch (e: SyncException) {
            settings.recordError(e.message.orEmpty())
            return SyncResult.FAILED
        } catch (e: IOException) {
            settings.recordError("Couldn't reach the server (${e.javaClass.simpleName}: ${e.message})")
            return SyncResult.NETWORK_ERROR
        } catch (e: SerializationException) {
            settings.recordError("Unexpected response from the server.")
            return SyncResult.FAILED
        } finally {
            settings.setInProgress(false)
        }
    }
}
