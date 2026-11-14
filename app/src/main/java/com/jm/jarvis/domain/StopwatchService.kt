package com.jm.jarvis.domain

import com.jm.jarvis.data.SettingsStore
import com.jm.jarvis.notify.Notifications

/** O que dá para pedir ao cronômetro, por voz ou pelos botões da notificação. */
enum class StopwatchAction { START, PAUSE, RESUME, RESET, STATUS }

/** Dono do cronômetro: guarda o estado e mantém a notificação com o tempo correndo. */
class StopwatchService(private val settings: SettingsStore, private val notifications: Notifications) {

    suspend fun apply(action: StopwatchAction): String {
        val now = System.currentTimeMillis()
        val before = settings.stopwatch()
        val after = when (action) {
            StopwatchAction.START, StopwatchAction.RESUME -> before.start(now)
            StopwatchAction.PAUSE -> before.pause(now)
            StopwatchAction.RESET -> before.reset()
            StopwatchAction.STATUS -> before
        }
        if (after != before) settings.saveStopwatch(after)
        if (after.running || after.elapsed(now) > 0) notifications.showStopwatch(after, now) else notifications.cancelStopwatch()

        val time = Stopwatch.spoken(after.elapsed(now))
        return when (action) {
            StopwatchAction.START -> if (before.running) "O cronômetro já está correndo: $time." else "Cronômetro iniciado."
            StopwatchAction.RESUME -> "Cronômetro retomado em $time."
            StopwatchAction.PAUSE -> "Cronômetro pausado em $time."
            StopwatchAction.RESET -> "Cronômetro zerado."
            StopwatchAction.STATUS -> when {
                after.running -> "Cronômetro em $time, correndo."
                after.elapsed(now) > 0 -> "Cronômetro pausado em $time."
                else -> "O cronômetro está zerado."
            }
        }
    }
}
