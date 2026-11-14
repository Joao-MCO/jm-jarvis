package com.jm.jarvis.domain

import android.content.Context
import com.jm.jarvis.calendar.CalendarAnnouncer
import com.jm.jarvis.data.SettingsStore
import com.jm.jarvis.notify.ReminderScheduler
import com.jm.jarvis.web.WebLookup
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/** Texto do briefing, montado sem a IA: rápido, previsível e testável. */
object BriefingText {
    fun compose(
        now: LocalTime,
        weather: String?,
        events: List<Pair<LocalTime?, String>>?,
        pendingRoutines: List<String>,
        reminders: List<Pair<LocalTime, String>>,
    ): String = buildList {
        add(
            when {
                now.hour < 12 -> "Bom dia!"
                now.hour < 18 -> "Boa tarde!"
                else -> "Boa noite!"
            },
        )
        weather?.let { add(it) }
        when {
            events == null -> Unit
            events.isEmpty() -> add("Agenda livre hoje.")
            else -> add("Na agenda hoje: " + events.joinToString(", ") { (time, title) -> time?.let { "${Progress.hhmm(it)}, $title" } ?: "$title, o dia todo" } + ".")
        }
        if (pendingRoutines.isNotEmpty()) add("Rotinas de hoje: ${list(pendingRoutines)}.")
        if (reminders.isNotEmpty()) add("Lembretes: " + reminders.joinToString(", ") { (time, text) -> "$text às ${Progress.hhmm(time)}" } + ".")
    }.joinToString(" ")

    /** "a, b e c". */
    private fun list(items: List<String>): String =
        if (items.size == 1) items[0] else items.dropLast(1).joinToString(", ") + " e " + items.last()
}

/** Reúne os dados do dia e agenda o briefing no horário escolhido em Ajustes. */
class BriefingService(
    private val context: Context,
    private val settings: SettingsStore,
    private val scheduler: ReminderScheduler,
    private val habits: HabitService,
    private val reminders: ReminderService,
    private val calendar: CalendarAnnouncer,
    private val web: WebLookup,
) {
    private val zone: ZoneId get() = ZoneId.systemDefault()

    suspend fun text(): String {
        val today = LocalDate.now(zone)
        // Clima com a última localização conhecida: o briefing precisa caber no tempo de um alarme.
        val weather = web.weather(days = 1, quick = true).takeIf { it.sources.isNotEmpty() }?.text
        val events = if (calendar.hasPermission()) {
            calendar.upcomingEvents(1).map { event ->
                val start = event.begin.atZone(zone)
                (if (event.allDay) null else start.toLocalTime()) to event.title
            }
        } else {
            null
        }
        val pending = habits.todayState().statuses.filter { !it.completeToday }.map { it.habit.name }
        val todayReminders = reminders.pending()
            .map { LocalDateTime.ofInstant(Instant.ofEpochMilli(it.at), zone) to it.text }
            .filter { it.first.toLocalDate() == today }
            .map { it.first.toLocalTime() to it.second }
        return BriefingText.compose(LocalTime.now(zone), weather, events, pending, todayReminders)
    }

    /** Agenda o próximo briefing, ou cancela se estiver desligado. */
    suspend fun schedule() {
        val current = settings.current()
        if (!current.briefingEnabled) {
            scheduler.scheduleBriefing(null)
            return
        }
        val now = LocalDateTime.now(zone)
        val todayAt = now.toLocalDate().atTime(current.briefingTime)
        val next = if (todayAt > now) todayAt else todayAt.plusDays(1)
        scheduler.scheduleBriefing(next.atZone(zone).toInstant())
    }
}
