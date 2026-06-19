package com.example.playeragent.history

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "play_sessions",
    indices = [
        Index(value = ["trackKey"]),
        Index(value = ["startedAt"]),
        Index(value = ["endedAt"]),
        Index(value = ["updatedAt"])
    ]
)
data class PlaySessionEntity(
    @PrimaryKey(autoGenerate = true)
    val sessionId: Long = 0L,
    val trackKey: String,
    val startedAt: Long,
    val endedAt: Long?,
    val listenedMs: Long,
    val startPositionMs: Long,
    val endPositionMs: Long,
    val durationMs: Long,
    val completed: Boolean,
    val skipped: Boolean,
    val countedPlay: Boolean,
    val packageName: String,
    val updatedAt: Long
)
