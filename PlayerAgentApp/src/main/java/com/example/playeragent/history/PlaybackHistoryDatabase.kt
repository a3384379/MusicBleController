package com.example.playeragent.history

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        TrackEntity::class,
        PlaySessionEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class PlaybackHistoryDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao
    abstract fun playSessionDao(): PlaySessionDao

    companion object {
        const val DATABASE_NAME = "playeragent_history.db"

        @Volatile
        private var instance: PlaybackHistoryDatabase? = null

        fun getInstance(context: Context): PlaybackHistoryDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    PlaybackHistoryDatabase::class.java,
                    DATABASE_NAME
                ).build().also {
                    instance = it
                }
            }
        }
    }
}
