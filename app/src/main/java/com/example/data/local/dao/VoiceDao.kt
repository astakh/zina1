package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.local.model.CustomVoiceRule
import com.example.data.local.model.VoiceCommandLog
import kotlinx.coroutines.flow.Flow

@Dao
interface VoiceDao {
    @Query("SELECT * FROM voice_command_logs ORDER BY timestamp DESC")
    fun getAllLogs(): Flow<List<VoiceCommandLog>>

    @Query("SELECT * FROM voice_command_logs ORDER BY timestamp DESC LIMIT 50")
    fun getRecentLogs(): Flow<List<VoiceCommandLog>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(log: VoiceCommandLog): Long

    @Query("DELETE FROM voice_command_logs")
    suspend fun clearLogs()

    @Query("SELECT * FROM custom_voice_rules ORDER BY createdAt DESC")
    fun getAllRules(): Flow<List<CustomVoiceRule>>

    @Query("SELECT * FROM custom_voice_rules WHERE isEnabled = 1")
    suspend fun getEnabledRules(): List<CustomVoiceRule>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRule(rule: CustomVoiceRule): Long

    @Update
    suspend fun updateRule(rule: CustomVoiceRule)

    @Delete
    suspend fun deleteRule(rule: CustomVoiceRule)
}
