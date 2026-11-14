package com.jm.jarvis.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalTime

class HabitMatcherTest {
    private val habits = Seeds.routines.mapIndexed { i, habit -> habit.copy(id = i + 1L) }

    private fun find(query: String) = HabitMatcher.find(query, habits)?.name

    @Test
    fun `spoken phrases find the routine`() {
        assertEquals("Beber água", find("água"))
        assertEquals("Beber água", find("agua"))
        assertEquals("Escovar os dentes", find("escovei os dentes"))
        assertEquals("Tomar suplementos", find("suplemento"))
        assertEquals("Academia", find("academia"))
        assertEquals("Rezar", find("rezar o terço"))
        assertEquals("Ler um livro", find("ler"))
        assertEquals("Estudar", find("estudar inglês"))
    }

    @Test
    fun `unknown phrases find nothing`() {
        assertNull(find("correr"))
        assertNull(find("de"))
    }

    @Test
    fun `clock times as people say them`() {
        assertEquals(LocalTime.of(8, 0), parseClock("08:00"))
        assertEquals(LocalTime.of(8, 30), parseClock("8:30"))
        assertEquals(LocalTime.of(8, 0), parseClock("8h"))
        assertEquals(LocalTime.of(20, 30), parseClock("20h30"))
        assertEquals(LocalTime.of(7, 0), parseClock("7"))
        assertEquals(LocalTime.of(21, 0), parseClock("21 horas"))
        assertNull(parseClock("25:00"))
        assertNull(parseClock("de manhã"))
    }
}
