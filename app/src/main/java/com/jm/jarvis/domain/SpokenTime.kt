package com.jm.jarvis.domain

import java.text.Normalizer
import java.time.DayOfWeek

/** Interpreta dias falados: "seg", "segunda-feira", "dias úteis", "fim de semana", "todos os dias". */
object SpokenTime {
    private val DAYS = mapOf(
        "seg" to DayOfWeek.MONDAY, "ter" to DayOfWeek.TUESDAY, "qua" to DayOfWeek.WEDNESDAY,
        "qui" to DayOfWeek.THURSDAY, "sex" to DayOfWeek.FRIDAY, "sab" to DayOfWeek.SATURDAY, "dom" to DayOfWeek.SUNDAY,
    )

    /** Dias da semana citados; vazio quando nada é reconhecido. */
    fun days(words: List<String>): Set<DayOfWeek> = words.flatMap { day(it) }.toSet()

    private fun day(text: String): Set<DayOfWeek> {
        val t = normalize(text)
        return when {
            "todo" in t || "diari" in t -> DayOfWeek.entries.toSet()
            "uteis" in t || "util" in t -> Progress.WEEKDAYS
            "fim de semana" in t || "fds" in t -> setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
            else -> DAYS.filterKeys { t.startsWith(it) }.values.toSet()
        }
    }

    internal fun normalize(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase().trim()
}
