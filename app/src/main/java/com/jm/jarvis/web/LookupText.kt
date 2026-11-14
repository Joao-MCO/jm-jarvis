package com.jm.jarvis.web

import com.jm.jarvis.domain.Progress
import java.time.LocalDate
import kotlin.math.roundToInt

/** De onde veio uma informação, para mostrar abaixo da resposta. */
data class Source(val title: String, val url: String)

/** O texto que a IA lê e as fontes que a tela mostra. */
data class Lookup(val text: String, val sources: List<Source> = emptyList())

data class SearchHit(val title: String, val url: String, val content: String)

data class WeatherNow(val temperature: Double, val feelsLike: Double, val code: Int)

data class DayForecast(val date: LocalDate, val code: Int, val min: Double, val max: Double, val rainChance: Int?)

/**
 * Textos curtos com o que veio da internet: a IA roda na CPU do celular e lê cerca de 100
 * palavras por segundo, então cada trecho a mais atrasa a resposta.
 */
object LookupText {
    private const val SNIPPET = 280

    fun search(answer: String?, hits: List<SearchHit>): String {
        if (answer.isNullOrBlank() && hits.isEmpty()) return "A pesquisa não trouxe resultados."
        val parts = buildList {
            answer?.takeIf { it.isNotBlank() }?.let { add("Resumo: ${it.trim().take(SNIPPET * 2)}") }
            hits.take(3).forEachIndexed { i, hit -> add("Fonte ${i + 1} (${hit.title.trim()}): ${hit.content.oneLine().take(SNIPPET)}") }
        }
        return parts.joinToString("\n")
    }

    fun wikipedia(title: String, extract: String): String = "$title: ${extract.oneLine().take(SNIPPET * 2)}"

    fun weather(now: WeatherNow, days: List<DayForecast>, city: String?, today: LocalDate): String = buildString {
        append(if (city.isNullOrBlank()) "Agora: " else "Agora em $city: ")
        append("${now.temperature.roundToInt()}°, ${label(now.code)}, sensação de ${now.feelsLike.roundToInt()}°.")
        days.take(2).forEach { day ->
            val name = if (day.date == today) "Hoje" else if (day.date == today.plusDays(1)) "Amanhã" else Progress.DAY_LABELS.getValue(day.date.dayOfWeek)
            append(" $name: ${label(day.code)}, mínima de ${day.min.roundToInt()}° e máxima de ${day.max.roundToInt()}°")
            day.rainChance?.let { append(", $it% de chance de chuva") }
            append('.')
        }
    }

    /** Códigos de tempo da OMM, usados pelo Open-Meteo. */
    fun label(code: Int): String = when (code) {
        0 -> "céu limpo"
        1 -> "céu quase limpo"
        2 -> "parcialmente nublado"
        3 -> "nublado"
        45, 48 -> "neblina"
        51, 53, 55 -> "garoa"
        56, 57 -> "garoa congelante"
        61 -> "chuva fraca"
        63 -> "chuva moderada"
        65 -> "chuva forte"
        66, 67 -> "chuva congelante"
        71, 73, 75, 77 -> "neve"
        80 -> "pancadas de chuva fracas"
        81 -> "pancadas de chuva"
        82 -> "pancadas de chuva fortes"
        85, 86 -> "pancadas de neve"
        95 -> "trovoadas"
        96, 99 -> "trovoadas com granizo"
        else -> "tempo indefinido"
    }

    private fun String.oneLine(): String = replace(Regex("\\s+"), " ").trim()
}
