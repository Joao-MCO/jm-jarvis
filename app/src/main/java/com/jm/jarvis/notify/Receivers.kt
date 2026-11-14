package com.jm.jarvis.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.jm.jarvis.AppContainer
import com.jm.jarvis.container
import com.jm.jarvis.data.AlertMode
import com.jm.jarvis.domain.AlertPolicy
import com.jm.jarvis.domain.Progress
import com.jm.jarvis.domain.StopwatchAction
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalTime

/** Roda [block] fora da thread principal, mantendo o processo vivo até terminar. */
private fun BroadcastReceiver.runAsync(context: Context, block: suspend AppContainer.() -> Unit) {
    val pending = goAsync()
    val app = context.container
    app.scope.launch {
        try {
            app.block()
        } finally {
            pending.finish()
        }
    }
}

/** Disparado pelos alarmes de rotina e da agenda. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val habitId = intent.data?.lastPathSegment?.toLongOrNull()
        runAsync(context) {
            when (intent.action) {
                ReminderScheduler.ACTION_HABIT -> if (habitId != null) alertHabit(habitId)
                ReminderScheduler.ACTION_CALENDAR -> calendar.onAlarm()
                ReminderScheduler.ACTION_REMINDER -> if (habitId != null) alertReminder(habitId)
            }
        }
    }

    private suspend fun AppContainer.alertReminder(reminderId: Long) {
        val reminder = reminders.onDue(reminderId) ?: return
        val decision = AlertPolicy.forHabit(AlertMode.SPEAK, settings.current(), LocalTime.now(), speaker.shouldStayQuiet())
        notifications.showReminder(reminder, decision.channel)
        if (decision.speak) {
            delay(800)
            speaker.speak("Lembrete: ${reminder.text}.")
        }
    }

    private suspend fun AppContainer.alertHabit(habitId: Long) {
        val status = habits.onReminderDue(habitId) ?: return
        val decision = AlertPolicy.forHabit(status.habit.alertMode, settings.current(), LocalTime.now(), speaker.shouldStayQuiet())
        notifications.showHabit(status, decision.channel)
        if (decision.speak) {
            delay(800) // deixa o som da notificação tocar antes da fala
            speaker.speak(Progress.spoken(status))
        }
    }
}

/** Botões Feito / Adiar / Pular da notificação. */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val stopwatchAction = when (intent.action) {
            ACTION_STOPWATCH_PAUSE -> StopwatchAction.PAUSE
            ACTION_STOPWATCH_RESUME -> StopwatchAction.RESUME
            ACTION_STOPWATCH_RESET -> StopwatchAction.RESET
            else -> null
        }
        if (stopwatchAction != null) {
            runAsync(context) { stopwatch.apply(stopwatchAction) }
            return
        }
        if (intent.action == ACTION_REMINDER_SNOOZE) {
            val reminderId = intent.data?.lastPathSegment?.toLongOrNull() ?: return
            runAsync(context) { reminders.snooze(reminderId, REMINDER_SNOOZE_MINUTES) }
            return
        }
        val habitId = intent.data?.lastPathSegment?.toLongOrNull() ?: return
        runAsync(context) {
            when (intent.action) {
                ACTION_DONE -> habits.logQuick(habitId)
                ACTION_SNOOZE -> habits.snooze(habitId, SNOOZE_MINUTES)
                ACTION_SKIP -> habits.skip(habitId)
            }
            notifications.cancel(habitId)
        }
    }

    companion object {
        const val ACTION_DONE = "com.jm.jarvis.action.DONE"
        const val ACTION_SNOOZE = "com.jm.jarvis.action.SNOOZE"
        const val ACTION_SKIP = "com.jm.jarvis.action.SKIP"
        const val SNOOZE_MINUTES = 15L
        const val ACTION_STOPWATCH_PAUSE = "com.jm.jarvis.action.STOPWATCH_PAUSE"
        const val ACTION_STOPWATCH_RESUME = "com.jm.jarvis.action.STOPWATCH_RESUME"
        const val ACTION_STOPWATCH_RESET = "com.jm.jarvis.action.STOPWATCH_RESET"
        const val ACTION_REMINDER_SNOOZE = "com.jm.jarvis.action.REMINDER_SNOOZE"
        const val REMINDER_SNOOZE_MINUTES = 10L
    }
}

/** Alarmes não sobrevivem a reinício nem a mudança de hora: tudo é reagendado. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in HANDLED_ACTIONS) return
        runAsync(context) {
            habits.rescheduleAll()
            reminders.rescheduleAll()
            calendar.refresh()
        }
    }

    private companion object {
        val HANDLED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
        )
    }
}
