package com.jm.jarvis

import android.app.Application
import android.content.Context
import android.util.Log
import com.jm.jarvis.widget.JarvisWidget
import kotlinx.coroutines.withTimeoutOrNull
import com.jm.jarvis.brain.Brain
import com.jm.jarvis.brain.JarvisTools
import com.jm.jarvis.domain.BriefingService
import com.jm.jarvis.domain.MemoryService
import com.jm.jarvis.domain.ReminderService
import com.jm.jarvis.domain.StopwatchService
import com.jm.jarvis.phone.PhoneActions
import com.jm.jarvis.phone.PhoneControl
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
    private val database = AppDatabase.build(context)
    val habits = HabitService(context, database.habitDao(), scheduler, notifications)
    val reminders = ReminderService(database.reminderDao(), scheduler, notifications)
    val memory = MemoryService(database.memoryDao())
    val calendar = CalendarAnnouncer(context, settings, scheduler, speaker)
    val speechInput = SpeechInput(context)
    val web = WebLookup(context, settings)
    val phone = PhoneActions(context)
    val control = PhoneControl(context, phone)
    val stopwatch = StopwatchService(settings, notifications)
    val briefing = BriefingService(context, settings, scheduler, habits, reminders, calendar, web)
    val brain = Brain(context, habits, memory) {
        JarvisTools(habits, calendar, web, phone, stopwatch, reminders, memory, briefing, control)
    }

    /**
     * Arma de novo todos os alarmes (ao abrir o app, ao reiniciar o celular, ao mudar a hora).
     * Cada passo é independente e tem prazo: um passo travado não pode impedir os outros.
     */
    suspend fun rearmAll() {
        step("rotinas") { habits.rescheduleAll() }
        step("lembretes") { reminders.rescheduleAll() }
        step("briefing") { briefing.schedule() }
        step("agenda") { calendar.refresh() }
        step("widget") { JarvisWidget.refresh(appContext) }
    }

    private val appContext = context.applicationContext

    private suspend fun step(name: String, block: suspend () -> Unit) {
        val started = System.currentTimeMillis()
        val finished = runCatching { withTimeoutOrNull(15_000) { block() } }
            .onFailure { Log.w(TAG, "falha ao rearmar $name", it) }
            .getOrNull()
        Log.i(TAG, "rearmar $name: ${if (finished == null) "não terminou" else "ok"} em ${System.currentTimeMillis() - started} ms")
    }

    private companion object {
        const val TAG = "JarvisAlarmes"
    }
}

val Context.container: AppContainer get() = (applicationContext as JarvisApp).container
