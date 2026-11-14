package com.jm.jarvis.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import com.jm.jarvis.MainActivity
import com.jm.jarvis.R
import com.jm.jarvis.domain.AlertChannel
import com.jm.jarvis.domain.HabitStatus
import com.jm.jarvis.domain.Progress
import com.jm.jarvis.domain.Stopwatch
import java.util.Locale

class Notifications(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    fun createChannels() {
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL_SOUND, "Rotinas com som", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Avisos de rotina com som e vibração."
                    enableVibration(true)
                },
                NotificationChannel(CHANNEL_VIBRATE, "Rotinas com vibração", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Avisos de rotina só com vibração."
                    setSound(null, null)
                    enableVibration(true)
                },
                NotificationChannel(CHANNEL_SILENT, "Rotinas silenciosas", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Avisos sem som, usados também no horário de silêncio."
                    setSound(null, null)
                    enableVibration(false)
                },
            ),
        )
    }

    fun enabled(): Boolean = manager.areNotificationsEnabled()

    fun showHabit(status: HabitStatus, channel: AlertChannel) {
        val habit = status.habit
        val openApp = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(context, channel.channelId)
            .setSmallIcon(R.drawable.ic_jarvis)
            .setContentTitle(habit.name)
            .setContentText(Progress.describe(status))
            .setCategory(Notification.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(openApp)
            .addAction(action(habit.id, NotificationActionReceiver.ACTION_DONE, Progress.quickLabel(habit)))
            .addAction(action(habit.id, NotificationActionReceiver.ACTION_SNOOZE, "Adiar ${NotificationActionReceiver.SNOOZE_MINUTES} min"))
            .addAction(action(habit.id, NotificationActionReceiver.ACTION_SKIP, "Pular"))
            .build()
        manager.notify(habit.id.toInt(), notification)
    }

    fun cancel(habitId: Long) = manager.cancel(habitId.toInt())

    /** Notificação fixa do cronômetro: o próprio Android faz o tempo correr, sem serviço rodando. */
    fun showStopwatch(stopwatch: Stopwatch, now: Long) {
        val elapsed = stopwatch.elapsed(now)
        val builder = Notification.Builder(context, CHANNEL_SILENT)
            .setSmallIcon(R.drawable.ic_jarvis)
            .setContentTitle("Cronômetro")
            .setOnlyAlertOnce(true)
            .setOngoing(stopwatch.running)
            .setContentIntent(openApp())
        if (stopwatch.running) {
            builder.setUsesChronometer(true).setShowWhen(true).setWhen(now - elapsed)
                .addAction(stopwatchAction(NotificationActionReceiver.ACTION_STOPWATCH_PAUSE, "Pausar"))
        } else {
            builder.setShowWhen(false).setContentText("Pausado em ${clock(elapsed)}")
                .addAction(stopwatchAction(NotificationActionReceiver.ACTION_STOPWATCH_RESUME, "Continuar"))
        }
        builder.addAction(stopwatchAction(NotificationActionReceiver.ACTION_STOPWATCH_RESET, "Zerar"))
        manager.notify(STOPWATCH_ID, builder.build())
    }

    fun cancelStopwatch() = manager.cancel(STOPWATCH_ID)

    private fun stopwatchAction(action: String, label: String): Notification.Action {
        val intent = Intent(context, NotificationActionReceiver::class.java).setAction(action).setData(Uri.parse("jarvis://stopwatch"))
        val pending = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Action.Builder(Icon.createWithResource(context, R.drawable.ic_jarvis), label, pending).build()
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE,
    )

    private fun clock(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s % 3600 / 60, s % 60)
        else String.format(Locale.ROOT, "%02d:%02d", s / 60, s % 60)
    }

    private fun action(habitId: Long, action: String, label: String): Notification.Action {
        val intent = Intent(context, NotificationActionReceiver::class.java)
            .setAction(action)
            .setData(Uri.parse("jarvis://habit/$habitId"))
        val pending = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Action.Builder(Icon.createWithResource(context, R.drawable.ic_jarvis), label, pending).build()
    }

    private val AlertChannel.channelId: String
        get() = when (this) {
            AlertChannel.SOUND -> CHANNEL_SOUND
            AlertChannel.VIBRATE -> CHANNEL_VIBRATE
            AlertChannel.SILENT -> CHANNEL_SILENT
        }

    private companion object {
        const val CHANNEL_SOUND = "routines_sound"
        const val CHANNEL_VIBRATE = "routines_vibrate"
        const val CHANNEL_SILENT = "routines_silent"

        /** Longe dos ids das rotinas, que começam em 1. */
        const val STOPWATCH_ID = Int.MAX_VALUE - 1
    }
}
