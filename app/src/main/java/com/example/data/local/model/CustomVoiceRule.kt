package com.example.data.local.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "custom_voice_rules")
data class CustomVoiceRule(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val triggerPhrase: String,
    val spokenResponse: String,
    val isEnabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
)
