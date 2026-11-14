package com.jm.jarvis.brain

import com.jm.jarvis.brain.Shortcut.Alarm
import com.jm.jarvis.brain.Shortcut.LogHabit
import com.jm.jarvis.brain.Shortcut.OpenApp
import com.jm.jarvis.brain.Shortcut.Stopwatch
import com.jm.jarvis.brain.Shortcut.Timer
import com.jm.jarvis.brain.Shortcut.Torch
import com.jm.jarvis.brain.Shortcut.Volume
import com.jm.jarvis.domain.Seeds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShortcutsTest {
    private val habits = Seeds.routines.mapIndexed { i, habit -> habit.copy(id = i + 1L) }
    private fun match(text: String) = Shortcut.match(text, habits)

    @Test
    fun `phone commands`() {
        assertEquals(Torch(true), match("Liga a lanterna"))
        assertEquals(Torch(false), match("Desliga a lanterna!"))
        assertEquals(Volume("menos"), match("Abaixa o volume"))
        assertEquals(Volume("mais"), match("aumenta o volume"))
        assertEquals(Volume("40"), match("volume em 40%"))
        assertEquals(OpenApp("spotify"), match("Abre o Spotify"))
    }

    @Test
    fun `clock commands`() {
        assertEquals(Timer(10, 0), match("Timer de 10 minutos"))
        assertEquals(Timer(90, 30), match("timer de 1 hora e 30 minutos e 30 segundos"))
        assertEquals(Alarm("06:30"), match("Me acorda amanhã às 6h30"))
        assertEquals(Alarm("07:00"), match("coloca um despertador às 7"))
        assertEquals(Stopwatch("iniciar"), match("Inicia o cronômetro"))
        assertEquals(Stopwatch("pausar"), match("Pausa o cronômetro"))
        assertEquals(Stopwatch("zerar"), match("Zera o cronômetro"))
        assertEquals(Stopwatch("consultar"), match("quanto deu?"))
    }

    @Test
    fun `habit logging`() {
        assertEquals(500.0, (match("Bebi 500 ml de água agora") as LogHabit).amount)
        assertEquals(1000.0, (match("Bebi 1l de água") as LogHabit).amount)
        assertEquals(1000.0, (match("Bebi um litro de água") as LogHabit).amount)
        assertEquals(500.0, (match("Bebi meio litro de água") as LogHabit).amount)
        assertEquals(true, match("Já escovei os dentes") is LogHabit)
        assertEquals(true, match("Tomei os suplementos") is LogHabit)
        assertEquals(true, match("Fui na academia hoje") is LogHabit)
    }

    @Test
    fun `anything uncertain goes to the model`() {
        assertNull(match("Qual é o volume agora?"))
        assertNull(match("Coloca um despertador às 7h de segunda a sexta"))
        assertNull(match("Quero correr uma meia maratona até junho"))
        assertNull(match("Bom dia, Jarvis!"))
        assertNull(match("Fui ao mercado")) // sem rotina correspondente
    }

    private val now = java.time.LocalDateTime.of(2026, 11, 14, 10, 0) // sábado

    private fun shortcut(text: String) = Shortcut.match(text, habits, now)

    @Test
    fun `questions never act`() {
        assertEquals(Shortcut.HabitProgress("agua"), shortcut("Quanto eu bebi de água hoje?"))
        assertEquals(true, shortcut("Já bebi água?") is Shortcut.HabitProgress)
        assertNull(shortcut("Bebi 500 ml de água?"))
        assertNull(shortcut("Liga a lanterna?"))
        assertNull(shortcut("Já escovei os dentes?").takeIf { it !is Shortcut.HabitProgress })
        assertEquals(Shortcut.Stopwatch("consultar"), shortcut("quanto deu?"))
        assertNull(shortcut("Quanto tempo falta para o timer de 10 minutos?"))
    }

    @Test
    fun `read shortcuts`() {
        assertEquals(Shortcut.Weather(2), shortcut("Vai chover amanhã?"))
        assertEquals(Shortcut.Weather(1), shortcut("Como está o clima hoje"))
        assertEquals(Shortcut.Weather(7), shortcut("previsão para a semana"))
        assertEquals(Shortcut.Weather(2), shortcut("previsão para domingo")) // sábado → domingo
        assertEquals(Shortcut.Weather(7), shortcut("vai chover sexta?")) // sábado → sexta é daqui a 6 dias
        assertEquals(Shortcut.Agenda(1), shortcut("O que eu tenho na agenda hoje?"))
        assertEquals(Shortcut.Agenda(2), shortcut("quais são meus compromissos amanhã"))
        assertEquals(Shortcut.DaySummary, shortcut("Como está o meu dia?"))
        assertEquals(Shortcut.PendingHabits, shortcut("Quais rotinas ainda faltam hoje?"))
        assertEquals(Shortcut.PendingReminders, shortcut("Quais lembretes eu tenho?"))
        assertNull(shortcut("Marca uma reunião na agenda amanhã"))
    }

    @Test
    fun `reminders and memory`() {
        assertEquals(
            Shortcut.Remind("ligar para o dentista", "amanhã às 15h"),
            shortcut("Me lembra de ligar para o dentista amanhã às 15h"),
        )
        assertEquals(
            Shortcut.Remind("tirar o bolo do forno", "Daqui a 20 minutos"),
            shortcut("Daqui a 20 minutos me lembra de tirar o bolo do forno"),
        )
        assertEquals(Shortcut.Remember("eu sou alérgico a camarão"), shortcut("Lembra que eu sou alérgico a camarão"))
        assertNull(shortcut("Me lembra de comprar pão")) // sem horário: a IA pergunta quando
        assertNull(shortcut("Lembra que amanhã eu tenho dentista")) // ambíguo: lembrete ou fato
    }
}
