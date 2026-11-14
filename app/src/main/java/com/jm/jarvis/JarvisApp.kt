package com.jm.jarvis

import android.app.Application
import android.content.Context
import com.jm.jarvis.brain.Brain
import com.jm.jarvis.brain.JarvisTools
import com.jm.jarvis.domain.StopwatchService
import com.jm.jarvis.phone.PhoneActions
import com.jm.jarvis.calendar.CalendarAnnouncer
import com.jm.jarvis.data.AppDatabase
import com.jm.jarvis.data.SettingsStore
import com.jm.jarvis.domain.HabitService
import com.jm.jarvis.notify.Notifications
import com.jm.jarvis.notify.ReminderScheduler
import com.jm.jarvis.voice.SpeechInput
import com.jm.jarvis.voice.Speaker
import com.jm.jarvis.web.WebLookup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class JarvisApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.notifications.createChannels()
    }
}

/** Liga as peças do app; uma instância por processo. */
class AppContainer(context: Context) {
    /** Trabalho que precisa terminar mesmo se a tela fechar (alarmes, botões da notificação). */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings = SettingsStore(context)
    val notifications = Notifications(context)
    val speaker = Speaker(context)
    private val scheduler = ReminderScheduler(context)
    val habits = HabitService(context, AppDatabase.build(context).habitDao(), scheduler, notifications)
    val calendar = CalendarAnnouncer(context, settings, scheduler, speaker)
    val speechInput = SpeechInput(context)
    val web = WebLookup(context, settings)
    val phone = PhoneActions(context)
    val stopwatch = StopwatchService(settings, notifications)
    val brain = Brain(context, habits) { JarvisTools(habits, calendar, web, phone, stopwatch) }
}

val Context.container: AppContainer get() = (applicationContext as JarvisApp).container
