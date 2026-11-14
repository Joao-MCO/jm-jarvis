package com.jm.jarvis.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime

class SpokenTimeTest {
    /** Sexta-feira, 13/11/2026, 20:00. */
    private val now: LocalDateTime = LocalDate.of(2026, 11, 13).atTime(20, 0).also { check(it.dayOfWeek == DayOfWeek.FRIDAY) }

    private fun at(text: String) = SpokenTime.moment(text, now)
    private fun day(offset: Long, hour: Int, minute: Int = 0) = now.toLocalDate().plusDays(offset).atTime(hour, minute)

    @Test
    fun `relative times`() {
        assertEquals(now.plusMinutes(20), at("daqui a 20 minutos"))
        assertEquals(now.plusMinutes(30), at("daqui a meia hora"))
        assertEquals(now.plusHours(2), at("em 2 horas"))
        assertEquals(now.plusHours(1), at("daqui a uma hora"))
        assertEquals(now.plusSeconds(30), at("daqui a 30 segundos"))
    }

    @Test
    fun `named days`() {
        assertEquals(day(1, 15), at("amanhã às 15h"))
        assertEquals(day(2, 7), at("depois de amanhã às 7"))
        assertEquals(day(0, 21, 30), at("hoje 21h30"))
        assertEquals(day(1, 9), at("amanhã"))
    }

    @Test
    fun `weekdays point to the next one`() {
        assertEquals(day(1, 10), at("sábado às 10h"))
        assertEquals(day(3, 9), at("segunda às 9h"))
        // Hoje é sexta, mas 9h já passou: vale a próxima sexta.
        assertEquals(day(7, 9), at("sexta às 9h"))
    }

    @Test
    fun `time alone is the next time it happens`() {
        assertEquals(day(0, 22), at("às 22h"))
        assertEquals(day(1, 14), at("às 14h"))
        assertEquals(day(1, 15), at("3 da tarde"))
        assertEquals(day(1, 12), at("meio-dia"))
    }

    @Test
    fun `day of the month`() {
        assertEquals(LocalDate.of(2026, 11, 20).atTime(10, 0), at("dia 20 às 10h"))
        assertEquals(LocalDate.of(2026, 12, 5).atTime(9, 0), at("dia 5"))
    }

    @Test
    fun `nonsense is not a time`() {
        assertNull(at("quando der"))
        assertNull(at("ontem às 10h"))
    }
}
