package com.example.playeragent.history

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Update
import androidx.sqlite.db.SupportSQLiteQuery

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

    @Query(
        "SELECT COALESCE(SUM(listenedMs), 0) AS totalListenMs, " +
            "COALESCE(SUM(CASE WHEN countedPlay THEN 1 ELSE 0 END), 0) AS playCount, " +
            "COUNT(DISTINCT trackKey) AS uniqueTrackCount, " +
            "COUNT(endedAt) AS endedSessionCount, " +
            "COALESCE(SUM(CASE WHEN endedAt IS NOT NULL AND completed THEN 1 ELSE 0 END), 0) AS completedCount, " +
            "COALESCE(SUM(CASE WHEN endedAt IS NOT NULL AND skipped THEN 1 ELSE 0 END), 0) AS skippedCount " +
            "FROM play_sessions WHERE startedAt >= :startAt AND startedAt < :endAt"
    )
    fun statsTotals(startAt: Long, endAt: Long): PlaybackStatsTotals

    @Query(
        "SELECT s.trackKey AS trackKey, COALESCE(t.title, '') AS title, " +
            "COALESCE(t.artist, '') AS artist, COALESCE(t.album, '') AS album, t.artworkId AS artworkId, " +
            "SUM(s.listenedMs) AS listenedMs, " +
            "SUM(CASE WHEN s.countedPlay THEN 1 ELSE 0 END) AS playCount, " +
            "SUM(CASE WHEN s.endedAt IS NOT NULL AND s.completed THEN 1 ELSE 0 END) AS completedCount, " +
            "SUM(CASE WHEN s.endedAt IS NOT NULL AND s.skipped THEN 1 ELSE 0 END) AS skippedCount " +
            "FROM play_sessions s LEFT JOIN tracks t ON t.trackKey = s.trackKey " +
            "WHERE s.startedAt >= :startAt AND s.startedAt < :endAt " +
            "GROUP BY s.trackKey ORDER BY listenedMs DESC, playCount DESC, s.trackKey COLLATE BINARY ASC LIMIT :limit"
    )
    fun statsTopTracks(startAt: Long, endAt: Long, limit: Int): List<TopTrack>

    @Query(
        "SELECT CASE WHEN TRIM(COALESCE(t.artist, ''), :blankCharacters) = '' " +
            "THEN '未知歌手' ELSE t.artist END AS artist, SUM(s.listenedMs) AS listenedMs, " +
            "SUM(CASE WHEN s.countedPlay THEN 1 ELSE 0 END) AS playCount, " +
            "COUNT(DISTINCT s.trackKey) AS trackCount " +
            "FROM play_sessions s LEFT JOIN tracks t ON t.trackKey = s.trackKey " +
            "WHERE s.startedAt >= :startAt AND s.startedAt < :endAt " +
            "GROUP BY CASE WHEN TRIM(COALESCE(t.artist, ''), :blankCharacters) = '' " +
            "THEN '未知歌手' ELSE t.artist END " +
            "ORDER BY listenedMs DESC, playCount DESC, artist COLLATE BINARY ASC LIMIT :limit"
    )
    fun statsTopArtists(startAt: Long, endAt: Long, limit: Int, blankCharacters: String): List<TopArtist>

    @Query(
        "SELECT MIN(startedAt) AS firstStartedAt, MAX(startedAt) AS lastStartedAt " +
            "FROM play_sessions WHERE startedAt >= :startAt AND startedAt < :endAt " +
            "GROUP BY CASE WHEN startedAt >= 0 THEN startedAt / 86400000 " +
            "ELSE (startedAt + 1) / 86400000 - 1 END ORDER BY firstStartedAt ASC"
    )
    fun statsOccupiedUtcDays(startAt: Long, endAt: Long): List<PlaybackStatsTimeBucket>

    @RawQuery
    fun statsDailyTrend(query: SupportSQLiteQuery): List<DailyListenStat>

    @Query("DELETE FROM play_sessions")
    fun clearSessions()
}
