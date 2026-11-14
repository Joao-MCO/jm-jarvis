package com.jm.jarvis.domain

import java.text.Normalizer
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

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

    private val NUMBER_WORDS = mapOf(
        "um" to 1, "uma" to 1, "dois" to 2, "duas" to 2, "tres" to 3, "quatro" to 4, "cinco" to 5,
        "dez" to 10, "quinze" to 15, "vinte" to 20, "trinta" to 30, "quarenta" to 40,
    )
    /** Dias por palavra inteira: "quando" não pode virar quarta-feira. */
    private val WEEKDAY_WORDS = mapOf(
        "segunda" to DayOfWeek.MONDAY, "seg" to DayOfWeek.MONDAY,
        "terca" to DayOfWeek.TUESDAY, "ter" to DayOfWeek.TUESDAY,
        "quarta" to DayOfWeek.WEDNESDAY, "qua" to DayOfWeek.WEDNESDAY,
        "quinta" to DayOfWeek.THURSDAY, "qui" to DayOfWeek.THURSDAY,
        "sexta" to DayOfWeek.FRIDAY, "sex" to DayOfWeek.FRIDAY,
        "sabado" to DayOfWeek.SATURDAY, "sab" to DayOfWeek.SATURDAY,
        "domingo" to DayOfWeek.SUNDAY, "dom" to DayOfWeek.SUNDAY,
    )
    private val PERIOD_CLOCK = Regex("""\b(\d{1,2})(?:\s*[:h]\s*(\d{2}))?\s+da\s+(manha|tarde|noite)""")
    private val RELATIVE = Regex("""(?:daqui a|daqui|em|dentro de)\s+(\d+|[a-z]+)\s*(segundos?|minutos?|min|horas?|h)\b""")
    private val DAY_OF_MONTH = Regex("""\bdia\s+(\d{1,2})\b""")
    private val CLOCK = Regex("""(?:\bas\s+(\d{1,2})(?:\s*[:h]\s*(\d{2}))?)|(?:\b(\d{1,2})\s*(?:[:h]\s*(\d{2})?|horas?\b))""")

    /**
     * Quando algo deve acontecer, do jeito que se fala: "daqui a 20 minutos", "amanhã às 15h",
     * "sexta às 9h", "às 14h". Só devolve momentos depois de [now]; `null` se não entender.
     */
    fun moment(text: String, now: LocalDateTime): LocalDateTime? {
        val t = normalize(text)
        if ("ontem" in t) return null
        if ("meia hora" in t) return now.plusMinutes(30)
        RELATIVE.find(t)?.let { match ->
            val amount = match.groupValues[1].toIntOrNull() ?: NUMBER_WORDS[match.groupValues[1]] ?: return@let
            val unit = match.groupValues[2]
            return when {
                unit.startsWith("seg") -> now.plusSeconds(amount.toLong())
                unit.startsWith("h") -> now.plusHours(amount.toLong())
                else -> now.plusMinutes(amount.toLong())
            }
        }

        val today = now.toLocalDate()
        var weekday = false
        val date: LocalDate? = when {
            "depois de amanha" in t -> today.plusDays(2)
            "amanha" in t -> today.plusDays(1)
            "hoje" in t -> today
            else -> DAY_OF_MONTH.find(t)?.groupValues?.get(1)?.toInt()?.let { day ->
                runCatching { today.withDayOfMonth(day) }.getOrNull()
                    ?.let { if (it < today) runCatching { today.plusMonths(1).withDayOfMonth(day) }.getOrNull() else it }
            } ?: t.split(Regex("[^a-z]+")).firstNotNullOfOrNull { WEEKDAY_WORDS[it] }?.let { day ->
                weekday = true
                today.plusDays(((day.value - today.dayOfWeek.value + 7) % 7).toLong())
            }
        }
        val time = timeOf(t.replace(DAY_OF_MONTH, " "))

        var result = when {
            date != null && time != null -> date.atTime(time)
            date != null -> date.atTime(DEFAULT_TIME)
            time != null -> today.atTime(time).let { if (it <= now) it.plusDays(1) else it }
            else -> return null
        }
        if (result <= now && weekday) result = result.plusWeeks(1)
        return result.takeIf { it > now }
    }

    private fun timeOf(t: String): LocalTime? {
        if ("meio dia" in t || "meio-dia" in t) return LocalTime.NOON
        if ("meia noite" in t || "meia-noite" in t) return LocalTime.MIDNIGHT
        PERIOD_CLOCK.find(t)?.let { m ->
            val hour = m.groupValues[1].toInt()
            val minute = m.groupValues[2].ifEmpty { "0" }.toInt()
            val h = if (m.groupValues[3] != "manha" && hour in 1..11) hour + 12 else hour
            return if (h in 0..23 && minute in 0..59) LocalTime.of(h, minute) else null
        }
        val match = CLOCK.find(t) ?: return null
        val hour = (match.groupValues[1].ifEmpty { match.groupValues[3] }).toIntOrNull() ?: return null
        val minute = (match.groupValues[2].ifEmpty { match.groupValues[4] }).ifEmpty { "0" }.toInt()
        val afternoon = ("da tarde" in t || "da noite" in t) && hour in 1..11
        val h = if (afternoon) hour + 12 else hour
        return if (h in 0..23 && minute in 0..59) LocalTime.of(h, minute) else null
    }

    private val DEFAULT_TIME: LocalTime = LocalTime.of(9, 0)

    internal fun normalize(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase().trim()
}
