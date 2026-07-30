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
}

@Database(
    entities = [PlaybackHistoryEntity::class],
    version = 1,
    exportSchema = false
)
abstract class PlaybackHistoryDatabase : RoomDatabase() {
    abstract fun historyDao(): PlaybackHistoryDao

    companion object {
        fun create(context: Context): PlaybackHistoryDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                PlaybackHistoryDatabase::class.java,
                "controller_history.db"
            ).fallbackToDestructiveMigration().build()
    }
}
