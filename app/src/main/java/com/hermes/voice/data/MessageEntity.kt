package com.hermes.voice.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val role: String, // "user" | "assistant" | "system" | "error"
    val content: String,
    val timestamp: Long = System.currentTimeMillis()
)
