package com.jm.jarvis.brain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuestionTest {
    @Test
    fun `questions`() {
        listOf(
            "Quanto eu bebi de água hoje?", "Qual é o volume agora", "Que horas são", "Como está meu dia",
            "Quando é a minha academia", "Vai chover amanhã", "Tem alguma coisa na agenda hoje", "Jarvis, quem foi Santos Dumont",
            "Já bebi água?", "Bebi água?", "Onde fica o aeroporto", "O que eu tenho hoje", "E vai chover amanhã",
        ).forEach { assertTrue(it, Question.asked(it)) }
    }

    @Test
    fun `statements and requests`() {
        listOf(
            "Bebi 500 ml de água", "Já escovei os dentes", "Tomei os suplementos", "Fui na academia hoje",
            "Liga a lanterna", "Me lembra de ligar para o dentista amanhã às 15h", "Pode ligar a lanterna", "Abre o Spotify",
            "Quero criar o hábito de meditar", "Lembra que eu sou alérgico a camarão", "Bom dia, Jarvis",
        ).forEach { assertFalse(it, Question.asked(it)) }
    }
}
