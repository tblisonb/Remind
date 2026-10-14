package com.tlisonbee.remind.server

import com.tlisonbee.remind.shared.RepeatUnit
import com.tlisonbee.remind.shared.SyncRequest
import com.tlisonbee.remind.shared.SyncResponse
import com.tlisonbee.remind.shared.TaskDto
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

/**
 * SQLite-backed task storage. Every accepted write gets the next value of a server-wide sequence
 * number (`seq`), which clients use as their sync cursor, so syncing never depends on clocks agreeing.
 *
 * A single connection guarded by `@Synchronized` is plenty for one user.
 */
class TaskStore(path: String) : AutoCloseable {
    private val conn: Connection = DriverManager.getConnection("jdbc:sqlite:$path")

    init {
        conn.createStatement().use { st ->
            st.execute("PRAGMA journal_mode = WAL")
            st.execute(
                """
                CREATE TABLE IF NOT EXISTS tasks (
                    id TEXT PRIMARY KEY,
                    title TEXT NOT NULL,
                    notes TEXT NOT NULL,
                    done INTEGER NOT NULL,
                    remind_at TEXT,
                    repeat_unit TEXT NOT NULL,
                    repeat_interval INTEGER NOT NULL,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL,
                    deleted INTEGER NOT NULL,
                    seq INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            st.execute("CREATE INDEX IF NOT EXISTS tasks_seq ON tasks (seq)")
        }
    }

    @Synchronized
    fun active(): List<TaskDto> = query("SELECT * FROM tasks WHERE deleted = 0")

    @Synchronized
    fun get(id: String): TaskDto? = getLocked(id)

    /** Applies a device's changes (newest edit wins) and returns everything after its cursor. */
    @Synchronized
    fun sync(request: SyncRequest): SyncResponse = transaction {
        request.changes.forEach(::putLocked)
        SyncResponse(
            cursor = maxSeq(),
            changes = query("SELECT * FROM tasks WHERE seq > ? ORDER BY seq", request.since),
        )
    }

    /**
     * Saves an edit made on the server itself (the web UI). It is stamped with a time later than
     * the stored version so it wins even if a device's clock runs ahead of this machine's.
     */
    @Synchronized
    fun save(task: TaskDto): TaskDto = transaction {
        val existing = getLocked(task.id)
        val stamped = task.copy(
            updatedAt = maxOf(System.currentTimeMillis(), (existing?.updatedAt ?: 0) + 1),
            createdAt = existing?.createdAt ?: task.createdAt,
        )
        putLocked(stamped)
        stamped
    }

    override fun close() = conn.close()

    private fun putLocked(task: TaskDto) {
        val existing = getLocked(task.id)
        if (existing != null && existing.updatedAt >= task.updatedAt) return
        conn.prepareStatement(
            """
            INSERT OR REPLACE INTO tasks
                (id, title, notes, done, remind_at, repeat_unit, repeat_interval, created_at, updated_at, deleted, seq)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { st ->
            st.setString(1, task.id)
            st.setString(2, task.title)
            st.setString(3, task.notes)
            st.setBoolean(4, task.done)
            st.setString(5, task.remindAt)
            st.setString(6, task.repeatUnit.name)
            st.setInt(7, task.repeatInterval)
            st.setLong(8, task.createdAt)
            st.setLong(9, task.updatedAt)
            st.setBoolean(10, task.deleted)
            st.setLong(11, maxSeq() + 1)
            st.executeUpdate()
        }
    }

    private fun getLocked(id: String): TaskDto? = query("SELECT * FROM tasks WHERE id = ?", id).firstOrNull()

    private fun maxSeq(): Long = conn.createStatement().use { st ->
        st.executeQuery("SELECT COALESCE(MAX(seq), 0) FROM tasks").use { rs -> rs.next(); rs.getLong(1) }
    }

    private fun query(sql: String, vararg args: Any): List<TaskDto> = conn.prepareStatement(sql).use { st ->
        args.forEachIndexed { i, arg -> st.setObject(i + 1, arg) }
        st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toTask()) } }
    }

    private fun ResultSet.toTask() = TaskDto(
        id = getString("id"),
        title = getString("title"),
        notes = getString("notes"),
        done = getBoolean("done"),
        remindAt = getString("remind_at"),
        repeatUnit = RepeatUnit.valueOf(getString("repeat_unit")),
        repeatInterval = getInt("repeat_interval"),
        createdAt = getLong("created_at"),
        updatedAt = getLong("updated_at"),
        deleted = getBoolean("deleted"),
    )

    private fun <T> transaction(block: () -> T): T {
        conn.autoCommit = false
        try {
            return block().also { conn.commit() }
        } catch (e: Throwable) {
            conn.rollback()
            throw e
        } finally {
            conn.autoCommit = true
        }
    }
}
