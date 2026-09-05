package com.example.controllerapp

import android.app.Application
import android.content.ComponentCallbacks2
import com.example.controllerapp.data.ControllerLogStore
import com.example.controllerapp.data.ControllerPreferences
import com.example.controllerapp.data.PlaybackHistoryDatabase

class ControllerApplication : Application() {
    lateinit var preferences: ControllerPreferences
        private set
    lateinit var database: PlaybackHistoryDatabase
        private set
    lateinit var logStore: ControllerLogStore
        private set
    lateinit var repository: ControllerRepository
        private set
    lateinit var commandGateway: ControllerCommandGateway
        private set

    override fun onCreate() {
        super.onCreate()
        preferences = ControllerPreferences(this)
        database = PlaybackHistoryDatabase.create(this)
        logStore = ControllerLogStore(this)
        repository = ControllerRepository(
            context = this,
            preferences = preferences,
            historyDao = database.historyDao(),
            logStore = logStore
        )
        commandGateway = ControllerCommandGateway(repository)
    }

    override fun onTrimMemory(level: Int) {
        repository.trimMemory(level)
        super.onTrimMemory(level)
    }

    override fun onLowMemory() {
        repository.trimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
        super.onLowMemory()
    }
}
