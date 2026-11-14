package com.jm.jarvis.phone

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import java.time.DayOfWeek
import java.time.LocalTime

/**
 * Pede coisas a outros apps do celular pelos pedidos padrão do Android. Só funciona com o JARVIS
 * em primeiro plano, que é o caso durante a conversa.
 */
class PhoneActions(private val context: Context) {

    /** Despertador no app Relógio, sem abrir a tela dele. */
    fun setAlarm(time: LocalTime, days: Set<DayOfWeek>, label: String?): Boolean = launch(
        Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, time.hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, time.minute)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .apply {
                label?.takeIf { it.isNotBlank() }?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) }
                if (days.isNotEmpty()) putExtra(AlarmClock.EXTRA_DAYS, ArrayList(days.sorted().map(::calendarDay)))
            },
    )

    fun setTimer(seconds: Int, label: String?): Boolean = launch(
        Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .apply { label?.takeIf { it.isNotBlank() }?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) } },
    )

    fun showAlarms(): Boolean = launch(Intent(AlarmClock.ACTION_SHOW_ALARMS))

    /** Abre uma tela de outro app; `false` se nenhum app do celular atende o pedido. */
    fun launch(intent: Intent): Boolean {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent.resolveActivity(context.packageManager) == null) return false
        Handler(Looper.getMainLooper()).post { runCatching { context.startActivity(intent) } }
        return true
    }

    /** O Relógio usa os dias do java.util.Calendar: domingo = 1 ... sábado = 7. */
    private fun calendarDay(day: DayOfWeek): Int = day.value % 7 + 1
}
