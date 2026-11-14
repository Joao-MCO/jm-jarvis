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
    }
}
