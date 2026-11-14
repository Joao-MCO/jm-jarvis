package com.jm.jarvis.calendar

import com.jm.jarvis.domain.Progress
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/** Uma ocorrência de evento da agenda e os lembretes configurados nela. */
data class CalendarEvent(
    val eventId: Long,
    val title: String,
    val begin: Instant,
    val allDay: Boolean,
    val declined: Boolean,
    val reminderMinutes: List<Int>,
)

/** Uma fala agendada: [minutesBefore] antes do início do evento. */
data class Announcement(val event: CalendarEvent, val minutesBefore: Int, val at: Instant)

/** Decide quando falar cada evento: no horário de cada lembrete dele, como o Google Agenda avisaria. */
object CalendarPlanner {
    /** Uma fala cujo horário caiu até 30 s depois do alarme é falada nele, e não reagendada. */
    private val LOOKAHEAD: Duration = Duration.ofSeconds(30)
    private val LATE_TOLERANCE: Duration = Duration.ofMinutes(2)

    fun announcements(events: List<CalendarEvent>): List<Announcement> =
        events.filter { !it.allDay && !it.declined && it.title.isNotBlank() }
            .flatMap { event ->
                event.reminderMinutes.distinct().map { minutes ->
                    Announcement(event, minutes, event.begin.minus(Duration.ofMinutes(minutes.toLong())))
                }
            }

    fun next(events: List<CalendarEvent>, now: Instant): Announcement? =
        announcements(events).filter { it.at > now.plus(LOOKAHEAD) }.minByOrNull { it.at }

    fun due(events: List<CalendarEvent>, now: Instant): List<Announcement> =
        announcements(events).filter { it.at >= now.minus(LATE_TOLERANCE) && it.at <= now.plus(LOOKAHEAD) }
            .sortedBy { it.event.begin }

    /** "Daqui a 15 minutos: Reunião com o Pedro." */
    fun phrase(announcement: Announcement, now: Instant, zone: ZoneId): String {
        val minutes = announcement.minutesBefore
        val start = announcement.event.begin.atZone(zone)
        val today = now.atZone(zone).toLocalDate()
        val whenText = when {
            minutes == 0 -> "Começando agora"
            minutes == 1 -> "Daqui a 1 minuto"
            minutes < 60 -> "Daqui a $minutes minutos"
            minutes < 120 -> "Daqui a 1 hora" + if (minutes % 60 > 0) " e ${minutes % 60} minutos" else ""
            else -> {
                val day = when (start.toLocalDate()) {
                    today -> "Hoje"
                    today.plusDays(1) -> "Amanhã"
                    else -> Progress.DAY_LABELS.getValue(start.dayOfWeek).replaceFirstChar(Char::uppercase)
                }
                "$day às ${Progress.hhmm(start.toLocalTime())}"
            }
        }
        return "$whenText: ${announcement.event.title.trim()}."
    }
}
