package com.jm.jarvis.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalTime

class BriefingTextTest {
    @Test
    fun `full morning briefing`() = assertEquals(
        "Bom dia! Agora: 20°, nublado. Na agenda hoje: 09:00, Reunião, Feriado, o dia todo. " +
            "Rotinas de hoje: Beber água, Rezar e Academia. Lembretes: ligar para o dentista às 15:00.",
        BriefingText.compose(
            now = LocalTime.of(7, 30),
            weather = "Agora: 20°, nublado.",
            events = listOf(LocalTime.of(9, 0) to "Reunião", null to "Feriado"),
            pendingRoutines = listOf("Beber água", "Rezar", "Academia"),
            reminders = listOf(LocalTime.of(15, 0) to "ligar para o dentista"),
        ),
    )

    @Test
    fun `empty day without calendar access`() = assertEquals(
        "Boa tarde!",
        BriefingText.compose(LocalTime.of(14, 0), weather = null, events = null, pendingRoutines = emptyList(), reminders = emptyList()),
    )

    @Test
    fun `free agenda is said`() = assertEquals(
        "Boa noite! Agenda livre hoje. Rotinas de hoje: Rezar.",
        BriefingText.compose(LocalTime.of(20, 0), weather = null, events = emptyList(), pendingRoutines = listOf("Rezar"), reminders = emptyList()),
    )
}
