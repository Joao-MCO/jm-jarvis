package com.jm.jarvis.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.time.DayOfWeek

class StopwatchTest {
    @Test
    fun `pausing keeps the time and resuming adds to it`() {
        val running = Stopwatch().start(now = 1_000)
        val paused = running.pause(now = 61_000)
        assertFalse(paused.running)
        assertEquals(60_000, paused.elapsed(now = 999_999))
        assertEquals(90_000, paused.start(now = 100_000).elapsed(now = 130_000))
        assertEquals(0, paused.reset().elapsed(now = 130_000))
    }

    @Test
    fun `starting twice does not restart the count`() =
        assertEquals(Stopwatch().start(1_000), Stopwatch().start(1_000).start(5_000))

    @Test
    fun `elapsed time as spoken`() {
        assertEquals("0 segundos", Stopwatch.spoken(0))
        assertEquals("1 minuto e 5 segundos", Stopwatch.spoken(65_000))
        assertEquals("1 hora, 2 minutos e 5 segundos", Stopwatch.spoken(3_725_000))
        assertEquals("2 horas", Stopwatch.spoken(7_200_000))
    }

    @Test
    fun `spoken days`() {
        assertEquals(Progress.WEEKDAYS, SpokenTime.days(listOf("dias úteis")))
        assertEquals(setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY), SpokenTime.days(listOf("segunda-feira", "qua")))
        assertEquals(setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY), SpokenTime.days(listOf("fim de semana")))
        assertEquals(7, SpokenTime.days(listOf("todos os dias")).size)
        assertEquals(emptySet<DayOfWeek>(), SpokenTime.days(listOf("amanhã")))
    }
}
