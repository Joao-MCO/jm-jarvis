package com.jm.jarvis.domain

import com.jm.jarvis.data.ScheduleType
import org.junit.Assert.assertEquals
import org.junit.Test

class RoutineDraftTest {

    private fun error(draft: RoutineDraft) = (draft.validate() as DraftResult.Invalid).message

    @Test
    fun `every seed routine survives a round trip through the editor`() {
        Seeds.routines.forEach { habit ->
            assertEquals(habit.name, DraftResult.Valid(habit), RoutineDraft.from(habit).validate())
        }
    }

    @Test
    fun `missing fields are explained`() {
        assertEquals("Dê um nome à rotina.", error(RoutineDraft(name = "  ")))
        assertEquals("Escolha pelo menos um dia.", error(RoutineDraft(name = "Ler", days = emptySet())))
        assertEquals("Adicione pelo menos um horário.", error(RoutineDraft(name = "Ler", times = emptyList())))
        assertEquals(
            "O intervalo deve ser de 5 a 720 minutos.",
            error(RoutineDraft(name = "Água", type = ScheduleType.INTERVAL, interval = "2")),
        )
        assertEquals(
            "Informe a unidade da meta (ex.: ml, min).",
            error(RoutineDraft(name = "Água", goal = GoalKind.DAILY, dailyTarget = "2500")),
        )
        assertEquals("A meta semanal deve ser de 1 a 7 vezes.", error(RoutineDraft(name = "Academia", goal = GoalKind.WEEKLY, weeklyTarget = "9")))
    }

    @Test
    fun `decimal comma is accepted`() {
        val habit = (RoutineDraft(name = "Água", goal = GoalKind.DAILY, dailyTarget = "2,5", unit = "L", quickAmount = "0,25").validate() as DraftResult.Valid).habit
        assertEquals(2.5, habit.dailyTarget!!, 0.0)
        assertEquals(0.25, habit.quickAmount!!, 0.0)
    }
}
