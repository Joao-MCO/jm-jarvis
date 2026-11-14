package com.jm.jarvis.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import com.jm.jarvis.domain.Stopwatch
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.LocalTime

data class Settings(
    /** Silencia tudo: os avisos ainda aparecem, mas sem som e sem fala. */
    val muted: Boolean = false,
    val quietEnabled: Boolean = true,
    val quietStart: LocalTime = LocalTime.of(22, 30),
    val quietEnd: LocalTime = LocalTime.of(7, 0),
    /** Falar os eventos da agenda no horário do lembrete de cada um. */
    val calendarSpeech: Boolean = true,
    /** Chave pessoal da Tavily para pesquisar na internet; fica só no armazenamento privado do app. */
    val tavilyKey: String = "",
) {
    /** Se [time] cai no horário de silêncio, que pode atravessar a meia-noite. */
    fun isQuietAt(time: LocalTime): Boolean {
        if (!quietEnabled || quietStart == quietEnd) return false
        return if (quietStart < quietEnd) time >= quietStart && time < quietEnd
        else time >= quietStart || time < quietEnd
    }
}

private val Context.settingsStore by preferencesDataStore(name = "settings")

class SettingsStore(context: Context) {
    private val store = context.settingsStore

    val flow: Flow<Settings> = store.data.map { it.toSettings() }

    suspend fun current(): Settings = flow.first()

    suspend fun update(transform: (Settings) -> Settings) {
        store.edit { prefs ->
            val next = transform(prefs.toSettings())
            prefs[MUTED] = next.muted
            prefs[QUIET_ENABLED] = next.quietEnabled
            prefs[QUIET_START] = next.quietStart.toSecondOfDay() / 60
            prefs[QUIET_END] = next.quietEnd.toSecondOfDay() / 60
            prefs[CALENDAR_SPEECH] = next.calendarSpeech
            prefs[TAVILY_KEY] = next.tavilyKey
        }
    }

    suspend fun stopwatch(): Stopwatch {
        val prefs = store.data.first()
        return Stopwatch(runningSince = prefs[STOPWATCH_SINCE], accumulatedMs = prefs[STOPWATCH_ACCUMULATED] ?: 0)
    }

    suspend fun saveStopwatch(stopwatch: Stopwatch) {
        store.edit { prefs ->
            stopwatch.runningSince?.let { prefs[STOPWATCH_SINCE] = it } ?: prefs.remove(STOPWATCH_SINCE)
            prefs[STOPWATCH_ACCUMULATED] = stopwatch.accumulatedMs
        }
    }

    suspend fun isSeeded(): Boolean = store.data.first()[SEEDED] == true

    suspend fun markSeeded() {
        store.edit { it[SEEDED] = true }
    }

    private fun Preferences.toSettings(): Settings {
        val defaults = Settings()
        return Settings(
            muted = this[MUTED] ?: defaults.muted,
            quietEnabled = this[QUIET_ENABLED] ?: defaults.quietEnabled,
            quietStart = this[QUIET_START]?.let { LocalTime.of(it / 60, it % 60) } ?: defaults.quietStart,
            quietEnd = this[QUIET_END]?.let { LocalTime.of(it / 60, it % 60) } ?: defaults.quietEnd,
            calendarSpeech = this[CALENDAR_SPEECH] ?: defaults.calendarSpeech,
            tavilyKey = this[TAVILY_KEY] ?: defaults.tavilyKey,
        )
    }

    private companion object {
        val MUTED = booleanPreferencesKey("muted")
        val QUIET_ENABLED = booleanPreferencesKey("quiet_enabled")
        val QUIET_START = intPreferencesKey("quiet_start_minutes")
        val QUIET_END = intPreferencesKey("quiet_end_minutes")
        val CALENDAR_SPEECH = booleanPreferencesKey("calendar_speech")
        val SEEDED = booleanPreferencesKey("seeded")
        val TAVILY_KEY = stringPreferencesKey("tavily_key")
        val STOPWATCH_SINCE = longPreferencesKey("stopwatch_since")
        val STOPWATCH_ACCUMULATED = longPreferencesKey("stopwatch_accumulated")
    }
}
