package com.example.playeragent.history

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface TrackDao {
    @Query("SELECT * FROM tracks WHERE trackKey = :trackKey LIMIT 1")
    fun getTrack(trackKey: String): TrackEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertTrack(track: TrackEntity)

    @Query("SELECT * FROM tracks ORDER BY totalListenMs DESC LIMIT :limit")
    fun getTopTracksByListenTime(limit: Int): List<TrackEntity>

    @Query("SELECT * FROM tracks ORDER BY playCount DESC, totalListenMs DESC LIMIT :limit")
    fun getTopTracksByPlayCount(limit: Int): List<TrackEntity>

    @Query("SELECT COUNT(*) FROM tracks")
    fun countTracks(): Int

    @Query(
        "UPDATE tracks SET " +
            "totalListenMs = totalListenMs + :deltaMs, " +
            "lastPlayedAt = :lastPlayedAt, " +
            "lastUpdatedAt = :updatedAt " +
            "WHERE trackKey = :trackKey"
    )
    fun addListenTime(trackKey: String, deltaMs: Long, lastPlayedAt: Long, updatedAt: Long)

    @Query(
        "UPDATE tracks SET " +
            "playCount = playCount + 1, " +
            "lastPlayedAt = :lastPlayedAt, " +
            "lastUpdatedAt = :updatedAt " +
            "WHERE trackKey = :trackKey"
    )
    fun incrementPlayCount(trackKey: String, lastPlayedAt: Long, updatedAt: Long)

    @Query(
        "UPDATE tracks SET " +
            "skipCount = skipCount + 1, " +
            "lastUpdatedAt = :updatedAt " +
            "WHERE trackKey = :trackKey"
    )
    fun incrementSkipCount(trackKey: String, updatedAt: Long)

    @Query(
        "UPDATE tracks SET " +
            "completionCount = completionCount + 1, " +
            "lastUpdatedAt = :updatedAt " +
            "WHERE trackKey = :trackKey"
    )
    fun incrementCompletionCount(trackKey: String, updatedAt: Long)

    @Query("DELETE FROM tracks")
    fun clearTracks()
}
