package com.example.data.local.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "voice_command_logs")
data class VoiceCommandLog(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val commandText: String,
    val responseText: String,
    val intentName: String,
    val wasScreenOff: Boolean = false,
    val executionTimeMs: Long = 0L,
    val success: Boolean = true
)
