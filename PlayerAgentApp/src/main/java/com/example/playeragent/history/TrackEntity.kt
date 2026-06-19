package com.example.playeragent.history

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "tracks",
    indices = [
        Index(value = ["title"]),
        Index(value = ["artist"]),
        Index(value = ["lastPlayedAt"])
    ]
)
data class TrackEntity(
    @PrimaryKey
    val trackKey: String,
    val title: String,
    val artist: String,
    val album: String,
    val packageName: String,
    val durationMs: Long,
    val artworkId: String?,
    val firstPlayedAt: Long,
    val lastPlayedAt: Long,
    val playCount: Int,
    val skipCount: Int,
    val completionCount: Int,
    val totalListenMs: Long,
    val lastUpdatedAt: Long
)
