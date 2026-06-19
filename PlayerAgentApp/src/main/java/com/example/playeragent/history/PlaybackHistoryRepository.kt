package com.example.playeragent.history

import android.content.Context
import java.io.File
import java.security.MessageDigest
import java.util.Locale

class PlaybackHistoryRepository(
    context: Context
) {
    private val appContext = context.applicationContext
    private val db = PlaybackHistoryDatabase.getInstance(appContext)
    private val trackDao = db.trackDao()
    private val sessionDao = db.playSessionDao()

    fun buildTrackKey(snapshot: FastPlaybackSnapshot): String {
        val durationBucket = if (snapshot.durationMs > 0L) {
            (snapshot.durationMs / DURATION_BUCKET_MS) * DURATION_BUCKET_MS
        } else {
            0L
        }
        val source = listOf(
            normalize(snapshot.packageName),
            normalize(snapshot.title),
            normalize(snapshot.artist),
            durationBucket.toString()
        ).joinToString("|")
        return MessageDigest.getInstance("SHA-256")
            .digest(source.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    fun buildIdentityKey(snapshot: FastPlaybackSnapshot): String {
        return listOf(
            normalize(snapshot.packageName),
            normalize(snapshot.title),
            normalize(snapshot.artist)
        ).joinToString("|")
    }

    fun upsertTrackForSnapshot(
        trackKey: String,
        snapshot: FastPlaybackSnapshot,
        nowMs: Long
    ) {
        val existing = trackDao.getTrack(trackKey)
        val title = snapshot.title.trim()
        val artist = snapshot.artist.trim()
        val album = snapshot.album.trim()
        val packageName = snapshot.packageName.trim()
        val durationMs = snapshot.durationMs.coerceAtLeast(0L)
        val track = if (existing == null) {
            TrackEntity(
                trackKey = trackKey,
                title = title,
                artist = artist,
                album = album,
                packageName = packageName,
                durationMs = durationMs,
                artworkId = null,
                firstPlayedAt = nowMs,
                lastPlayedAt = nowMs,
                playCount = 0,
                skipCount = 0,
                completionCount = 0,
                totalListenMs = 0L,
                lastUpdatedAt = nowMs
            )
        } else {
            existing.copy(
                title = title.ifBlank { existing.title },
                artist = artist.ifBlank { existing.artist },
                album = album.ifBlank { existing.album },
                packageName = packageName.ifBlank { existing.packageName },
                durationMs = if (durationMs > 0L) durationMs else existing.durationMs,
                lastPlayedAt = nowMs,
                lastUpdatedAt = nowMs
            )
        }
        trackDao.upsertTrack(track)
    }

    fun insertSession(session: PlaySessionEntity): Long {
        return sessionDao.insertSession(session)
    }

    fun updateSessionWithListenDelta(
        session: PlaySessionEntity,
        listenDeltaMs: Long,
        nowMs: Long
    ) {
        db.runInTransaction {
            sessionDao.updateSession(session)
            if (listenDeltaMs > 0L) {
                trackDao.addListenTime(
                    trackKey = session.trackKey,
                    deltaMs = listenDeltaMs,
                    lastPlayedAt = nowMs,
                    updatedAt = nowMs
                )
            }
        }
    }

    fun markPlayCounted(trackKey: String, nowMs: Long) {
        trackDao.incrementPlayCount(trackKey, lastPlayedAt = nowMs, updatedAt = nowMs)
    }

    fun finishSession(
        session: PlaySessionEntity,
        listenDeltaMs: Long,
        completedIncrement: Boolean,
        skippedIncrement: Boolean,
        nowMs: Long
    ) {
        db.runInTransaction {
            sessionDao.updateSession(session)
            if (listenDeltaMs > 0L) {
                trackDao.addListenTime(
                    trackKey = session.trackKey,
                    deltaMs = listenDeltaMs,
                    lastPlayedAt = nowMs,
                    updatedAt = nowMs
                )
            }
            if (completedIncrement) {
                trackDao.incrementCompletionCount(session.trackKey, updatedAt = nowMs)
            }
            if (skippedIncrement) {
                trackDao.incrementSkipCount(session.trackKey, updatedAt = nowMs)
            }
        }
    }

    fun getRecentSessions(limit: Int): List<HistorySessionRow> {
        return sessionDao.getRecentSessions(beforeSessionId = null, limit = limit.coerceIn(1, 50))
    }

    fun getRecentSessions(beforeSessionId: Long?, limit: Int): List<HistorySessionRow> {
        return sessionDao.getRecentSessions(beforeSessionId, limit.coerceIn(1, 50))
    }

    fun getSessionsAfterId(afterSessionId: Long, limit: Int): List<HistorySessionRow> {
        return sessionDao.getSessionsAfterId(afterSessionId, limit.coerceIn(1, 50))
    }

    fun stats(range: StatsRange): PlaybackStatsSummary {
        return PlaybackStatsCalculator(db).summary(range)
    }

    fun statusSnapshot(): HistoryStatusSnapshot {
        val today = PlaybackStatsCalculator(db).summary(StatsRange.TODAY)
        return HistoryStatusSnapshot(
            monitorStatus = PlaybackHistoryMonitor.latestStatus(),
            totalTracks = trackDao.countTracks(),
            totalSessions = sessionDao.countSessions(),
            lastSessionId = sessionDao.getLastSessionId(),
            todayListenMs = today.totalListenMs,
            databasePath = databasePath().absolutePath
        )
    }

    fun clearAll() {
        db.runInTransaction {
            sessionDao.clearSessions()
            trackDao.clearTracks()
        }
    }

    fun databasePath(): File {
        return appContext.getDatabasePath(PlaybackHistoryDatabase.DATABASE_NAME)
    }

    private fun normalize(value: String): String {
        return value.trim()
            .lowercase(Locale.ROOT)
            .replace(WHITESPACE_REGEX, " ")
    }

    private companion object {
        private const val DURATION_BUCKET_MS = 2_000L
        private val WHITESPACE_REGEX = Regex("\\s+")
    }
}
