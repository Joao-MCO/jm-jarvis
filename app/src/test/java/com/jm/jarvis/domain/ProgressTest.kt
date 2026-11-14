package com.jm.jarvis.domain

import com.jm.jarvis.data.Habit
import com.jm.jarvis.data.HabitLog
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

class ProgressTest {
    private val zone = ZoneOffset.UTC
    private val mon = LocalDate.of(2026, 11, 16)
    private val seeds = Seeds.routines.mapIndexed { i, habit -> habit.copy(id = i + 1L) }.associateBy { it.name }
    private val water = seeds.getValue("Beber água")
    private val brush = seeds.getValue("Escovar os dentes")
    private val gym = seeds.getValue("Academia")
    private val study = seeds.getValue("Estudar")

    private fun log(habit: Habit, at: LocalDateTime, amount: Double? = null) =
        HabitLog(habitId = habit.id, at = at.toInstant(zone).toEpochMilli(), amount = amount)

    private fun status(habit: Habit, vararg logs: HabitLog) = Progress.status(habit, logs.toList(), mon, zone)

    @Test
    fun `water shows liters and the amount left`() {
        val drinks = (0..5).map { log(water, mon.atTime(9 + it, 0), 250.0) }.toTypedArray()
        val status = status(water, *drinks)
        assertEquals("1,5 de 2,5 L", Progress.describe(status))
        assertEquals("Beber água. Falta 1 litro para a meta de hoje.", Progress.spoken(status))
        assertEquals("Beber água. Meta de hoje: 2,5 litros.", Progress.spoken(status(water)))
    }

    @Test
    fun `counted routine shows how many of the day's times`() {
        val status = status(brush, log(brush, mon.atTime(9, 0)))
        assertEquals("1 de 3 hoje", Progress.describe(status))
        assertEquals("Escovar os dentes. 1 de 3 hoje.", Progress.spoken(status))
    }

    @Test
    fun `weekly routine counts days this week`() {
        val sunday = log(gym, mon.minusDays(1).atTime(10, 0)) // semana passada
        val status = status(gym, sunday, log(gym, mon.atTime(10, 0)))
        assertEquals("1 de 5 nesta semana · feito hoje", Progress.describe(status))
    }

    @Test
    fun `minutes goal and quick buttons`() {
        assertEquals("10 de 30 min", Progress.describe(status(study, log(study, mon.atTime(19, 0), 10.0))))
        assertEquals("Estudar. Faltam 20 minutos para a meta de hoje.", Progress.spoken(status(study, log(study, mon.atTime(19, 0), 10.0))))
        assertEquals("+30 min", Progress.quickLabel(study))
        assertEquals("+250 ml", Progress.quickLabel(water))
        assertEquals("Feito", Progress.quickLabel(brush))
    }

    @Test
    fun `schedule summaries`() {
        assertEquals("A cada 60 min, 08:00–22:00 · todo dia · meta 2,5 L", Progress.schedule(water))
        assertEquals("19:00 · seg a sex · meta 30 min", Progress.schedule(study))
        assertEquals("11:00 · todo dia · 5x por semana", Progress.schedule(gym))
    }

    @Test
    fun `upcoming reminder wording`() {
        assertEquals("hoje às 20:00", Progress.upcoming(mon.atTime(20, 0), mon))
        assertEquals("amanhã às 08:00", Progress.upcoming(mon.plusDays(1).atTime(8, 0), mon))
        assertEquals("qua às 11:00", Progress.upcoming(mon.plusDays(2).atTime(11, 0), mon))
    }
}
