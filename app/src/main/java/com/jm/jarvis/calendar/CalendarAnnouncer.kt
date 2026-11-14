package com.jm.jarvis.calendar

import android.Manifest
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import com.jm.jarvis.container
import com.jm.jarvis.data.SettingsStore
import com.jm.jarvis.domain.AlertPolicy
import com.jm.jarvis.domain.Progress
import com.jm.jarvis.notify.ReminderScheduler
import com.jm.jarvis.voice.Speaker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/** Fala os eventos da agenda do celular no horário dos lembretes deles. A notificação continua sendo a do Google Agenda. */
class CalendarAnnouncer(
    private val context: Context,
    private val settings: SettingsStore,
    private val scheduler: ReminderScheduler,
    private val speaker: Speaker,
) {
    private val zone: ZoneId get() = ZoneId.systemDefault()

    fun hasPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    /** Agenda o alarme da próxima fala e o vigia de mudanças na agenda, ou desliga os dois. */
    suspend fun refresh(armChangeJob: Boolean = true) {
        if (!settings.current().calendarSpeech || !hasPermission()) {
            scheduler.scheduleCalendar(null)
            CalendarChangeJob.cancel(context)
            return
        }
        scheduler.scheduleCalendar(CalendarPlanner.next(load(), Instant.now())?.at)
        if (armChangeJob) CalendarChangeJob.schedule(context)
    }

    /** Alarme da agenda: relê os eventos (podem ter mudado), fala os que estão na hora e agenda o próximo. */
    suspend fun onAlarm() {
        val now = Instant.now()
        val due = if (hasPermission()) CalendarPlanner.due(load(), now) else emptyList()
        refresh()
        if (due.isNotEmpty() && AlertPolicy.speakCalendar(settings.current(), LocalTime.now(), speaker.shouldStayQuiet())) {
            speaker.speak(due.joinToString(" ") { CalendarPlanner.phrase(it, now, zone) }, timeoutMs = 9_000)
        }
    }

    /** Texto para os Ajustes, ex.: "Próxima fala: hoje às 14:45 · Reunião". */
    suspend fun describeNext(): String? {
        if (!hasPermission()) return null
        val next = CalendarPlanner.next(load(), Instant.now()) ?: return null
        val at = LocalDateTime.ofInstant(next.at, zone)
        return "Próxima fala: ${Progress.upcoming(at, LocalDate.now(zone))} · ${next.event.title}"
    }

    /** Eventos que começam de agora até o fim do dia daqui a [days] - 1 dias, em ordem. */
    suspend fun upcomingEvents(days: Int): List<CalendarEvent> {
        if (!hasPermission()) return emptyList()
        val end = LocalDate.now(zone).plusDays(days.toLong()).atStartOfDay(zone).toInstant()
        return withContext(Dispatchers.IO) {
            runCatching { query(Instant.now(), end) }.getOrDefault(emptyList()).filter { !it.declined }.sortedBy { it.begin }
        }
    }

    private suspend fun load(): List<CalendarEvent> = withContext(Dispatchers.IO) {
        val now = Instant.now()
        runCatching { query(now, now.plus(LOOK_AHEAD)) }.getOrDefault(emptyList())
    }

    private fun query(from: Instant, to: Instant): List<CalendarEvent> {
        val resolver = context.contentResolver
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, from.toEpochMilli())
            ContentUris.appendId(it, to.toEpochMilli())
        }.build()
        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.SELF_ATTENDEE_STATUS,
        )
        data class Row(val eventId: Long, val title: String, val begin: Long, val allDay: Boolean, val status: Int)

        val rows = resolver.query(uri, projection, "${CalendarContract.Instances.VISIBLE} = 1", null, null)?.use { c ->
            buildList {
                while (c.moveToNext()) add(Row(c.getLong(0), c.getString(1).orEmpty(), c.getLong(2), c.getInt(3) == 1, c.getInt(4)))
            }
        }.orEmpty()
        if (rows.isEmpty()) return emptyList()

        // Ids vêm do próprio provedor (números), então montar o IN é seguro.
        val ids = rows.map { it.eventId }.distinct().joinToString(",")
        val reminders = mutableMapOf<Long, MutableList<Int>>()
        resolver.query(
            CalendarContract.Reminders.CONTENT_URI,
            arrayOf(CalendarContract.Reminders.EVENT_ID, CalendarContract.Reminders.MINUTES),
            "${CalendarContract.Reminders.EVENT_ID} IN ($ids) AND ${CalendarContract.Reminders.METHOD} IN " +
                "(${CalendarContract.Reminders.METHOD_ALERT}, ${CalendarContract.Reminders.METHOD_DEFAULT})",
            null,
            null,
        )?.use { c ->
            while (c.moveToNext()) {
                val minutes = c.getInt(1).let { if (it < 0) DEFAULT_REMINDER_MINUTES else it }
                reminders.getOrPut(c.getLong(0)) { mutableListOf() } += minutes
            }
        }

        return rows.map {
            CalendarEvent(
                eventId = it.eventId,
                title = it.title,
                begin = Instant.ofEpochMilli(it.begin),
                allDay = it.allDay,
                declined = it.status == CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED,
                reminderMinutes = reminders[it.eventId].orEmpty(),
            )
        }
    }

    private companion object {
        /** Eventos dos próximos 8 dias cobrem lembretes de até uma semana antes. */
        val LOOK_AHEAD: Duration = Duration.ofDays(8)
        const val DEFAULT_REMINDER_MINUTES = 10
    }
}

/** Acorda quando algo muda na agenda (evento novo, movido ou apagado) e reagenda a próxima fala. */
class CalendarChangeJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        val app = applicationContext.container
        app.scope.launch {
            try {
                app.calendar.refresh(armChangeJob = false)
            } finally {
                jobFinished(params, false)
                // Gatilhos de conteúdo valem uma vez: arma de novo para a próxima mudança.
                schedule(applicationContext)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true

    companion object {
        private const val JOB_ID = 7301

        fun schedule(context: Context) {
            val job = JobInfo.Builder(JOB_ID, ComponentName(context, CalendarChangeJob::class.java))
                .addTriggerContentUri(
                    JobInfo.TriggerContentUri(CalendarContract.CONTENT_URI, JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS),
                )
                .setTriggerContentUpdateDelay(5_000)
                .setTriggerContentMaxDelay(60_000)
                .build()
            context.getSystemService(JobScheduler::class.java).schedule(job)
        }

        fun cancel(context: Context) = context.getSystemService(JobScheduler::class.java).cancel(JOB_ID)
    }
}
