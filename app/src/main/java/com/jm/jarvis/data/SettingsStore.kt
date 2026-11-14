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
    /** IA online (API compatível com OpenAI, como a do Gemini): usada quando há internet; sem ela, o Gemma local. */
    val cloudEnabled: Boolean = true,
    val cloudKey: String = "",
    val cloudUrl: String = DEFAULT_CLOUD_URL,
    val cloudModel: String = DEFAULT_CLOUD_MODEL,
    /** Briefing falado: clima, agenda, rotinas e lembretes do dia. */
    val briefingEnabled: Boolean = true,
    val briefingTime: LocalTime = LocalTime.of(7, 30),
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
            prefs[CLOUD_ENABLED] = next.cloudEnabled
            prefs[CLOUD_KEY] = next.cloudKey
            prefs[CLOUD_URL] = next.cloudUrl
            prefs[CLOUD_MODEL] = next.cloudModel
            prefs[BRIEFING_ENABLED] = next.briefingEnabled
            prefs[BRIEFING_TIME] = next.briefingTime.toSecondOfDay() / 60
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
            cloudEnabled = this[CLOUD_ENABLED] ?: defaults.cloudEnabled,
            cloudKey = this[CLOUD_KEY] ?: defaults.cloudKey,
            cloudUrl = this[CLOUD_URL] ?: defaults.cloudUrl,
            cloudModel = this[CLOUD_MODEL] ?: defaults.cloudModel,
            briefingEnabled = this[BRIEFING_ENABLED] ?: defaults.briefingEnabled,
            briefingTime = this[BRIEFING_TIME]?.let { LocalTime.of(it / 60, it % 60) } ?: defaults.briefingTime,
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
        val CLOUD_ENABLED = booleanPreferencesKey("cloud_enabled")
        val CLOUD_KEY = stringPreferencesKey("cloud_key")
        val CLOUD_URL = stringPreferencesKey("cloud_url")
        val CLOUD_MODEL = stringPreferencesKey("cloud_model")
        val BRIEFING_ENABLED = booleanPreferencesKey("briefing_enabled")
        val BRIEFING_TIME = intPreferencesKey("briefing_time_minutes")
        val STOPWATCH_SINCE = longPreferencesKey("stopwatch_since")
        val STOPWATCH_ACCUMULATED = longPreferencesKey("stopwatch_accumulated")
    }
}

const val DEFAULT_CLOUD_URL = "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions"
const val DEFAULT_CLOUD_MODEL = "gemini-3.6-flash"
