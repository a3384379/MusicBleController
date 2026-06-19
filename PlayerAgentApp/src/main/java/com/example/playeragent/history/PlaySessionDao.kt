package com.example.playeragent.history

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

@Dao
interface PlaySessionDao {
    @Insert
    fun insertSession(session: PlaySessionEntity): Long

    @Update
    fun updateSession(session: PlaySessionEntity)

    @Query("SELECT * FROM play_sessions WHERE sessionId = :sessionId LIMIT 1")
    fun getSession(sessionId: Long): PlaySessionEntity?

    @Query(
        "SELECT " +
            "s.sessionId AS sessionId, " +
            "s.trackKey AS trackKey, " +
            "COALESCE(t.title, '') AS title, " +
            "COALESCE(t.artist, '') AS artist, " +
            "COALESCE(t.album, '') AS album, " +
            "t.artworkId AS artworkId, " +
            "s.startedAt AS startedAt, " +
            "s.endedAt AS endedAt, " +
            "s.listenedMs AS listenedMs, " +
            "s.durationMs AS durationMs, " +
            "s.completed AS completed, " +
            "s.skipped AS skipped, " +
            "s.countedPlay AS countedPlay " +
            "FROM play_sessions s " +
            "LEFT JOIN tracks t ON s.trackKey = t.trackKey " +
            "WHERE (:beforeSessionId IS NULL OR s.sessionId < :beforeSessionId) " +
            "ORDER BY s.sessionId DESC " +
            "LIMIT :limit"
    )
    fun getRecentSessions(beforeSessionId: Long?, limit: Int): List<HistorySessionRow>

    @Query(
        "SELECT " +
            "s.sessionId AS sessionId, " +
            "s.trackKey AS trackKey, " +
            "COALESCE(t.title, '') AS title, " +
            "COALESCE(t.artist, '') AS artist, " +
            "COALESCE(t.album, '') AS album, " +
            "t.artworkId AS artworkId, " +
            "s.startedAt AS startedAt, " +
            "s.endedAt AS endedAt, " +
            "s.listenedMs AS listenedMs, " +
            "s.durationMs AS durationMs, " +
            "s.completed AS completed, " +
            "s.skipped AS skipped, " +
            "s.countedPlay AS countedPlay " +
            "FROM play_sessions s " +
            "LEFT JOIN tracks t ON s.trackKey = t.trackKey " +
            "WHERE s.sessionId > :afterSessionId " +
            "ORDER BY s.sessionId ASC " +
            "LIMIT :limit"
    )
    fun getSessionsAfterId(afterSessionId: Long, limit: Int): List<HistorySessionRow>

    @Query(
        "SELECT * FROM play_sessions " +
            "WHERE startedAt >= :startAt AND startedAt < :endAt " +
            "ORDER BY startedAt ASC"
    )
    fun getSessionsInRange(startAt: Long, endAt: Long): List<PlaySessionEntity>

    @Query("SELECT MAX(sessionId) FROM play_sessions WHERE endedAt IS NOT NULL")
    fun getLastCompletedSessionId(): Long?

    @Query("SELECT COUNT(*) FROM play_sessions")
    fun countSessions(): Int

    @Query("SELECT COALESCE(MAX(sessionId), 0) FROM play_sessions")
    fun getLastSessionId(): Long

    @Query("SELECT COALESCE(SUM(listenedMs), 0) FROM play_sessions WHERE startedAt >= :startAt AND startedAt < :endAt")
    fun sumListenMsInRange(startAt: Long, endAt: Long): Long

    @Query("DELETE FROM play_sessions")
    fun clearSessions()
}
