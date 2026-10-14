package com.tlisonbee.remind.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {
    @Query("SELECT * FROM tasks WHERE deleted = 0")
    fun observeAll(): Flow<List<Task>>

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun get(id: String): Task?

    @Query("SELECT * FROM tasks WHERE deleted = 0 AND done = 0 AND nextReminderAt IS NOT NULL")
    suspend fun getScheduled(): List<Task>

    @Upsert
    suspend fun upsert(task: Task)
}
