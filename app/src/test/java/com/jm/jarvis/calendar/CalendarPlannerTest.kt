package com.jm.jarvis.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

class CalendarPlannerTest {
    private val zone = ZoneOffset.UTC
    private val now = Instant.parse("2026-11-16T10:00:00Z")

    private fun event(id: Long, title: String, begin: String, vararg minutes: Int, allDay: Boolean = false, declined: Boolean = false) =
        CalendarEvent(id, title, Instant.parse(begin), allDay, declined, minutes.toList())

    private val meeting = event(1, "Reunião", "2026-11-16T10:30:00Z", 10, 60)
    private val dentist = event(2, "Dentista", "2026-11-16T12:00:00Z", 15)

    @Test
    fun `next is the earliest reminder still ahead`() {
        val next = CalendarPlanner.next(listOf(meeting, dentist), now)
        assertEquals(1L, next?.event?.eventId)
        assertEquals(10, next?.minutesBefore)
    }

    @Test
    fun `all-day and declined events are not spoken`() {
        val events = listOf(
            event(3, "Feriado", "2026-11-16T11:00:00Z", 10, allDay = true),
            event(4, "Recusado", "2026-11-16T11:00:00Z", 10, declined = true),
        )
        assertTrue(CalendarPlanner.announcements(events).isEmpty())
    }

    @Test
    fun `a reminder that just fired is due and is not scheduled again`() {
        val firedAt = Instant.parse("2026-11-16T10:20:05Z")
        assertEquals(listOf(1L), CalendarPlanner.due(listOf(meeting, dentist), firedAt).map { it.event.eventId })
        assertEquals(2L, CalendarPlanner.next(listOf(meeting, dentist), firedAt)?.event?.eventId)
    }

    @Test
    fun `phrases say how long until the event`() {
        fun phrase(minutes: Int, begin: String = "2026-11-16T12:00:00Z") =
            CalendarPlanner.phrase(Announcement(event(9, "Reunião", begin, minutes), minutes, now), now, zone)

        assertEquals("Começando agora: Reunião.", phrase(0))
        assertEquals("Daqui a 1 minuto: Reunião.", phrase(1))
        assertEquals("Daqui a 15 minutos: Reunião.", phrase(15))
        assertEquals("Daqui a 1 hora: Reunião.", phrase(60))
        assertEquals("Daqui a 1 hora e 30 minutos: Reunião.", phrase(90))
        assertEquals("Amanhã às 10:00: Reunião.", phrase(1440, "2026-11-17T10:00:00Z"))
    }
}
