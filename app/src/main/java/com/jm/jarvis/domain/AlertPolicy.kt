package com.jm.jarvis.domain

import com.jm.jarvis.data.AlertMode
import com.jm.jarvis.data.Settings
import java.time.LocalTime

/** Canal de notificação: o som e a vibração de cada canal são fixados pelo Android. */
enum class AlertChannel { SOUND, VIBRATE, SILENT }

data class AlertDecision(val channel: AlertChannel, val speak: Boolean)

/** Quem decide se o JARVIS pode tocar som e falar agora. */
object AlertPolicy {

    /**
     * @param deviceQuiet celular no silencioso, no vibrar, no "Não perturbe" ou em ligação.
     */
    fun forHabit(mode: AlertMode, settings: Settings, time: LocalTime, deviceQuiet: Boolean): AlertDecision {
        if (settings.muted || settings.isQuietAt(time)) return AlertDecision(AlertChannel.SILENT, speak = false)
        return when (mode) {
            AlertMode.SOUND -> AlertDecision(AlertChannel.SOUND, speak = false)
            AlertMode.SPEAK -> AlertDecision(AlertChannel.SOUND, speak = !deviceQuiet)
            AlertMode.VIBRATE -> AlertDecision(AlertChannel.VIBRATE, speak = false)
            AlertMode.SILENT -> AlertDecision(AlertChannel.SILENT, speak = false)
        }
    }

    fun speakCalendar(settings: Settings, time: LocalTime, deviceQuiet: Boolean): Boolean =
        settings.calendarSpeech && !settings.muted && !settings.isQuietAt(time) && !deviceQuiet
}
