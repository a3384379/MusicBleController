package com.example.controllerapp.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.controllerapp.model.AppExperienceMode
import com.example.controllerapp.model.ControllerSettings
import com.example.controllerapp.model.LyricDisplayMode
import com.example.controllerapp.model.PlaybackPerformanceMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.controllerPreferencesDataStore by preferencesDataStore(
    name = "controller_preferences"
)

class ControllerPreferences(private val context: Context) {
    val settings: Flow<ControllerSettings> =
        context.controllerPreferencesDataStore.data.map { values ->
            ControllerSettings(
                experienceMode = values[Keys.EXPERIENCE_MODE]
                    ?.let { runCatching { AppExperienceMode.valueOf(it) }.getOrNull() }
                    ?: AppExperienceMode.DAILY,
                performanceMode = values[Keys.PERFORMANCE_MODE]
                    ?.let { runCatching { PlaybackPerformanceMode.valueOf(it) }.getOrNull() }
                    ?: PlaybackPerformanceMode.AUTOMATIC,
                autoReconnect = values[Keys.AUTO_RECONNECT] ?: true,
                lyricOffsetMs = values[Keys.LYRIC_OFFSET_MS] ?: 600L,
                lyricDisplayMode = values[Keys.LYRIC_DISPLAY_MODE]
                    ?.let { runCatching { LyricDisplayMode.valueOf(it) }.getOrNull() }
                    ?: LyricDisplayMode.ORIGINAL_TRANSLATION,
                artworkDisplaySizeDp = values[Keys.ARTWORK_SIZE_DP] ?: 260,
                artworkEnhancementEnabled = values[Keys.ARTWORK_ENHANCEMENT] ?: true
            )
        }

    val savedDeviceAddress: Flow<String> =
        context.controllerPreferencesDataStore.data.map {
            it[Keys.DEVICE_ADDRESS].orEmpty()
        }

    suspend fun setExperienceMode(value: AppExperienceMode) = edit {
        this[Keys.EXPERIENCE_MODE] = value.name
    }

    suspend fun setPerformanceMode(value: PlaybackPerformanceMode) = edit {
        this[Keys.PERFORMANCE_MODE] = value.name
    }

    suspend fun setAutoReconnect(value: Boolean) = edit {
        this[Keys.AUTO_RECONNECT] = value
    }

    suspend fun setLyricOffsetMs(value: Long) = edit {
        this[Keys.LYRIC_OFFSET_MS] = value.coerceIn(-2_000L, 2_000L)
    }

    suspend fun setLyricDisplayMode(value: LyricDisplayMode) = edit {
        this[Keys.LYRIC_DISPLAY_MODE] = value.name
    }

    suspend fun setArtworkDisplaySizeDp(value: Int) = edit {
        this[Keys.ARTWORK_SIZE_DP] = value.coerceIn(200, 260)
    }

    suspend fun setArtworkEnhancementEnabled(value: Boolean) = edit {
        this[Keys.ARTWORK_ENHANCEMENT] = value
    }

    suspend fun setSavedDeviceAddress(value: String) = edit {
        if (value.isBlank()) {
            remove(Keys.DEVICE_ADDRESS)
        } else {
            this[Keys.DEVICE_ADDRESS] = value
        }
    }

    private suspend fun edit(block: MutableMapPreferences.() -> Unit) {
        context.controllerPreferencesDataStore.edit { values ->
            MutableMapPreferences(values).apply(block).commit()
        }
    }

    private class MutableMapPreferences(
        private val target: androidx.datastore.preferences.core.MutablePreferences
    ) {
        operator fun <T> set(
            key: androidx.datastore.preferences.core.Preferences.Key<T>,
            value: T
        ) {
            target[key] = value
        }

        fun <T> remove(key: androidx.datastore.preferences.core.Preferences.Key<T>) {
            target.remove(key)
        }

        fun commit() = Unit
    }

    private object Keys {
        val EXPERIENCE_MODE = stringPreferencesKey("experience_mode")
        val PERFORMANCE_MODE = stringPreferencesKey("performance_mode")
        val AUTO_RECONNECT = booleanPreferencesKey("auto_reconnect")
        val LYRIC_OFFSET_MS = longPreferencesKey("lyric_offset_ms")
        val LYRIC_DISPLAY_MODE = stringPreferencesKey("lyric_display_mode")
        val ARTWORK_SIZE_DP = intPreferencesKey("artwork_size_dp")
        val ARTWORK_ENHANCEMENT = booleanPreferencesKey("artwork_enhancement")
        val DEVICE_ADDRESS = stringPreferencesKey("device_address")
    }
}
