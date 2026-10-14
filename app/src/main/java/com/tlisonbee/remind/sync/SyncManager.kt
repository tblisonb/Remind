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

/**
 * One sync round: push dirty tasks, merge what the server sends back, advance the cursor.
 * Safe to retry at any point: the server ignores versions it already has.
 */
class SyncManager(
    private val repository: TaskRepository,
    private val settings: SyncSettings,
    /** Starts/stops background syncing (kept out of here so this class has no Android dependencies). */
    private val onConnectionChanged: (connected: Boolean) -> Unit = {},
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
    }

    suspend fun sync(): SyncResult = mutex.withLock {
        val config = settings.config ?: return SyncResult.NOT_CONFIGURED
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
            SyncResult.SUCCESS
        } catch (e: SyncException) {
            settings.recordError(e.message.orEmpty())
            SyncResult.FAILED
        } catch (e: IOException) {
            settings.recordError("Couldn't reach the server (${e.javaClass.simpleName}: ${e.message})")
            SyncResult.NETWORK_ERROR
        } catch (e: SerializationException) {
            settings.recordError("Unexpected response from the server.")
            SyncResult.FAILED
        } finally {
            settings.setInProgress(false)
        }
    }
}
