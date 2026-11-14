package com.jm.jarvis.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class LookupTextTest {
    private val today = LocalDate.of(2026, 11, 13)

    @Test
    fun `weather reads like speech`() {
        val text = LookupText.weather(
            WeatherNow(temperature = 24.4, feelsLike = 25.6, code = 3),
            listOf(
                DayForecast(today, code = 61, min = 18.2, max = 27.5, rainChance = 60),
                DayForecast(today.plusDays(1), code = 0, min = 17.0, max = 29.0, rainChance = null),
            ),
            city = "São Paulo",
            today = today,
        )
        assertEquals(
            "Agora em São Paulo: 24°, nublado, sensação de 26°. " +
                "Hoje: chuva fraca, mínima de 18° e máxima de 28°, 60% de chance de chuva. " +
                "Amanhã: céu limpo, mínima de 17° e máxima de 29°.",
            text,
        )
    }

    @Test
    fun `unknown weather codes do not break`() = assertEquals("tempo indefinido", LookupText.label(42))

    @Test
    fun `search keeps the answer and short snippets`() {
        val long = "palavra ".repeat(200)
        val text = LookupText.search("O jogo terminou 2 a 1.", listOf(SearchHit("g1", "https://g1.globo.com", long)))
        assertTrue(text.startsWith("Resumo: O jogo terminou 2 a 1.\nFonte 1 (g1): palavra"))
        assertTrue(text.length < 400)
    }

    @Test
    fun `empty search says so`() = assertEquals("A pesquisa não trouxe resultados.", LookupText.search(null, emptyList()))
}
