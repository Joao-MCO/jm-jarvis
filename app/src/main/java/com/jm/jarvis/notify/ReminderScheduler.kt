package com.jm.jarvis.notify

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import java.time.Instant

/** Um alarme exato por rotina, mais um para a próxima fala da agenda. */
class ReminderScheduler(private val context: Context) {
    private val alarms = context.getSystemService(AlarmManager::class.java)

    fun scheduleHabit(habitId: Long, at: Instant?) = set(operation(ACTION_HABIT, "jarvis://habit/$habitId"), at)

    fun scheduleCalendar(at: Instant?) = set(operation(ACTION_CALENDAR, "jarvis://calendar"), at)

    fun scheduleBriefing(at: Instant?) = set(operation(ACTION_BRIEFING, "jarvis://briefing"), at)

    fun scheduleReminder(reminderId: Long, at: Instant?) = set(operation(ACTION_REMINDER, "jarvis://reminder/$reminderId"), at)

    private fun set(operation: PendingIntent, at: Instant?) {
        if (at == null) {
            alarms.cancel(operation)
            return
        }
        // USE_EXACT_ALARM é concedida na instalação; o inexato é só uma rede de segurança.
        if (alarms.canScheduleExactAlarms()) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toEpochMilli(), operation)
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toEpochMilli(), operation)
        }
    }

    private fun operation(action: String, data: String): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, ReminderReceiver::class.java).setAction(action).setData(Uri.parse(data)),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val ACTION_HABIT = "com.jm.jarvis.action.HABIT_REMINDER"
        const val ACTION_CALENDAR = "com.jm.jarvis.action.CALENDAR_ANNOUNCEMENT"
        const val ACTION_REMINDER = "com.jm.jarvis.action.REMINDER"
        const val ACTION_BRIEFING = "com.jm.jarvis.action.BRIEFING"
    }
}
