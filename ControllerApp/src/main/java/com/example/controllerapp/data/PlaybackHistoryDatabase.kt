package com.example.controllerapp.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.controllerapp.model.PlaybackHistorySession
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "playback_history")
data class PlaybackHistoryEntity(
    @PrimaryKey val sessionId: Long,
    val trackKey: String,
    val title: String,
    val artist: String,
    val album: String,
    val artworkId: String?,
    val startedAt: Long,
    val endedAt: Long?,
    val listenedMs: Long,
    val durationMs: Long,
    val completed: Boolean,
    val skipped: Boolean,
    val countedPlay: Boolean
) {
    fun toModel() = PlaybackHistorySession(
        sessionId = sessionId,
        trackKey = trackKey,
        title = title,
        artist = artist,
        album = album,
        artworkId = artworkId,
        startedAt = startedAt,
        endedAt = endedAt,
        listenedMs = listenedMs,
        durationMs = durationMs,
        completed = completed,
        skipped = skipped,
        countedPlay = countedPlay
    )

    companion object {
        fun from(value: PlaybackHistorySession) = PlaybackHistoryEntity(
            sessionId = value.sessionId,
            trackKey = value.trackKey,
            title = value.title,
            artist = value.artist,
            album = value.album,
            artworkId = value.artworkId,
            startedAt = value.startedAt,
            endedAt = value.endedAt,
            listenedMs = value.listenedMs,
            durationMs = value.durationMs,
            completed = value.completed,
            skipped = value.skipped,
            countedPlay = value.countedPlay
        )
    }
}

/** Cursors advance in the same transaction as the page they describe. */
@Entity(tableName = "history_cursor")
data class HistoryCursorEntity(
    @PrimaryKey val id: Int = 1,
    val sinceSessionId: Long = 0L,
    val olderBeforeSessionId: Long = Long.MAX_VALUE,
    val olderHasMore: Boolean = true
)

@Dao
interface PlaybackHistoryDao {
    @Query("SELECT * FROM playback_history ORDER BY sessionId DESC LIMIT :limit")
    fun observeRecent(limit: Int = 500): Flow<List<PlaybackHistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(values: List<PlaybackHistoryEntity>)

    @Query("DELETE FROM playback_history")
    suspend fun clear()

    @Query("SELECT MAX(sessionId) FROM playback_history")
    suspend fun latestSessionId(): Long?

    @Query("SELECT MIN(sessionId) FROM playback_history")
    suspend fun oldestSessionId(): Long?

    @Query("SELECT * FROM playback_history WHERE sessionId < :beforeExclusive ORDER BY sessionId DESC LIMIT :limit")
    suspend fun readBefore(beforeExclusive: Long, limit: Int): List<PlaybackHistoryEntity>

    @Query("SELECT * FROM history_cursor WHERE id = 1")
    suspend fun cursor(): HistoryCursorEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCursor(cursor: HistoryCursorEntity)

    @Query("DELETE FROM history_cursor")
    suspend fun clearCursor()

    @Transaction
    suspend fun commitPage(values: List<PlaybackHistoryEntity>, cursor: HistoryCursorEntity) {
        upsert(values)
        upsertCursor(cursor)
    }

    @Transaction
    suspend fun clearHistory() {
        clear()
        clearCursor()
    }
}

@Database(
    entities = [PlaybackHistoryEntity::class, HistoryCursorEntity::class],
    version = 2,
    exportSchema = false
)
abstract class PlaybackHistoryDatabase : RoomDatabase() {
    abstract fun historyDao(): PlaybackHistoryDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS history_cursor (" +
                        "id INTEGER NOT NULL PRIMARY KEY, sinceSessionId INTEGER NOT NULL, " +
                        "olderBeforeSessionId INTEGER NOT NULL, olderHasMore INTEGER NOT NULL)"
                )
                // Legacy rows remain intact. Their maximum ID does not prove that earlier
                // pages were ever synced, so the incremental watermark starts at zero.
            }
        }

        fun create(context: Context): PlaybackHistoryDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                PlaybackHistoryDatabase::class.java,
                "controller_history.db"
            ).addMigrations(MIGRATION_1_2).build()
    }
}
