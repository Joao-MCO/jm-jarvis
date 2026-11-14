package com.jm.jarvis.domain

import com.jm.jarvis.data.AlertMode
import com.jm.jarvis.data.Habit
import com.jm.jarvis.data.HabitLog
import com.jm.jarvis.data.ScheduleType
import java.text.NumberFormat
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale

internal val PT_BR: Locale = Locale.forLanguageTag("pt-BR")

/** Como uma rotina está hoje e nesta semana. */
data class HabitStatus(
    val habit: Habit,
    /** Soma das quantidades registradas hoje, ou o número de registros se não há meta em quantidade. */
    val doneToday: Double,
    val targetToday: Double?,
    /** Dias desta semana (segunda a domingo) com a rotina feita. */
    val weekDays: Int,
) {
    val completeToday: Boolean
        get() = when {
            habit.weeklyTarget != null -> doneToday > 0
            targetToday != null -> doneToday >= targetToday
            else -> false
        }

    val fraction: Float
        get() {
            val value = when {
                habit.weeklyTarget != null -> weekDays.toDouble() / habit.weeklyTarget
                targetToday != null && targetToday > 0 -> doneToday / targetToday
                else -> 0.0
            }
            return value.toFloat().coerceIn(0f, 1f)
        }
}

/** Cálculo e textos do progresso, usados pelo app, pelo widget, pelas notificações e pela voz. */
object Progress {

    fun status(habit: Habit, logs: List<HabitLog>, today: LocalDate, zone: ZoneId): HabitStatus {
        val done = logs.filter { it.habitId == habit.id && !it.skipped }
            .map { it to Instant.ofEpochMilli(it.at).atZone(zone).toLocalDate() }
        val todays = done.filter { it.second == today }.map { it.first }
        val doneToday = if (habit.dailyTarget != null) todays.sumOf { it.amount ?: 0.0 } else todays.size.toDouble()
        val target = habit.dailyTarget
            ?: if (habit.weeklyTarget == null && habit.scheduleType == ScheduleType.TIMES) {
                habit.times.size.coerceAtLeast(1).toDouble()
            } else {
                null
            }
        val monday = today.with(DayOfWeek.MONDAY)
        val weekDays = done.map { it.second }.filter { it in monday..today }.distinct().size
        return HabitStatus(habit, doneToday, target, weekDays)
    }

    /** Linha curta para telas e notificações, ex.: "1,5 de 2,5 L". */
    fun describe(status: HabitStatus): String {
        val habit = status.habit
        val target = status.targetToday
        return when {
            habit.weeklyTarget != null ->
                "${status.weekDays} de ${habit.weeklyTarget} nesta semana" + if (status.doneToday > 0) " · feito hoje" else ""
            target != null && habit.dailyTarget != null -> {
                val unit = habit.unit.orEmpty()
                if (usesLiters(unit, target)) "${number(status.doneToday / 1000)} de ${number(target / 1000)} L"
                else "${number(status.doneToday)} de ${number(target)} $unit".trim()
            }
            target != null -> "${status.doneToday.toInt()} de ${target.toInt()} hoje"
            else -> "${status.doneToday.toInt()} hoje"
        }
    }

    /** O que o JARVIS fala na hora do aviso. */
    fun spoken(status: HabitStatus): String {
        val habit = status.habit
        val target = status.targetToday
        val detail = when {
            habit.weeklyTarget != null -> "Nesta semana: ${status.weekDays} de ${habit.weeklyTarget}."
            target != null && habit.dailyTarget != null -> {
                val unit = habit.unit.orEmpty()
                val remaining = target - status.doneToday
                val shown = if (usesLiters(unit, target)) remaining / 1000 else remaining
                if (status.doneToday <= 0) "Meta de hoje: ${spokenAmount(target, unit, target)}."
                else "${if (shown < 2) "Falta" else "Faltam"} ${spokenAmount(remaining, unit, target)} para a meta de hoje."
            }
            target != null && target > 1 -> "${status.doneToday.toInt()} de ${target.toInt()} hoje."
            else -> ""
        }
        return "${habit.name}. $detail".trim()
    }

    /** Rótulo do botão de registro rápido, ex.: "+250 ml" ou "Feito". */
    fun quickLabel(habit: Habit): String {
        val amount = quickAmount(habit) ?: return "Feito"
        return "+${number(amount)} ${habit.unit.orEmpty()}".trim()
    }

    /** Quanto o registro rápido soma: a quantidade rápida, a meta inteira, ou nada (rotina por contagem). */
    fun quickAmount(habit: Habit): Double? = if (habit.dailyTarget != null) habit.quickAmount ?: habit.dailyTarget else null

    /** Resumo da agenda da rotina, ex.: "A cada 60 min, 08:00–22:00 · todo dia · meta 2,5 L". */
    fun schedule(habit: Habit): String {
        val whenText = when (habit.scheduleType) {
            ScheduleType.TIMES -> habit.times.sorted().joinToString(", ") { hhmm(it) }
            ScheduleType.INTERVAL ->
                "A cada ${habit.intervalMinutes} min, ${hhmm(habit.windowStart ?: LocalTime.MIN)}–${hhmm(habit.windowEnd ?: LocalTime.of(23, 59))}"
        }
        val goal = when {
            habit.weeklyTarget != null -> "${habit.weeklyTarget}x por semana"
            habit.dailyTarget != null -> {
                val unit = habit.unit.orEmpty()
                if (usesLiters(unit, habit.dailyTarget)) "meta ${number(habit.dailyTarget / 1000)} L"
                else "meta ${number(habit.dailyTarget)} $unit".trim()
            }
            else -> null
        }
        return listOfNotNull(whenText, days(habit.days), goal).joinToString(" · ")
    }

    /** "hoje às 20:00", "amanhã às 08:00" ou "sex às 11:00". */
    fun upcoming(at: LocalDateTime, today: LocalDate): String {
        val day = when (at.toLocalDate()) {
            today -> "hoje"
            today.plusDays(1) -> "amanhã"
            else -> DAY_LABELS.getValue(at.dayOfWeek)
        }
        return "$day às ${hhmm(at.toLocalTime())}"
    }

    fun hhmm(time: LocalTime): String = String.format(Locale.ROOT, "%02d:%02d", time.hour, time.minute)

    fun days(days: Set<DayOfWeek>): String = when {
        days.size == 7 -> "todo dia"
        days == WEEKDAYS -> "seg a sex"
        days == setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY) -> "fim de semana"
        else -> days.sorted().joinToString(", ") { DAY_LABELS.getValue(it) }
    }

    val WEEKDAYS: Set<DayOfWeek> = setOf(
        DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY,
    )

    val DAY_LABELS: Map<DayOfWeek, String> = mapOf(
        DayOfWeek.MONDAY to "seg", DayOfWeek.TUESDAY to "ter", DayOfWeek.WEDNESDAY to "qua",
        DayOfWeek.THURSDAY to "qui", DayOfWeek.FRIDAY to "sex", DayOfWeek.SATURDAY to "sáb",
        DayOfWeek.SUNDAY to "dom",
    )

    fun number(value: Double): String =
        NumberFormat.getNumberInstance(PT_BR).apply { maximumFractionDigits = 1 }.format(value)

    private fun usesLiters(unit: String, target: Double) = unit == "ml" && target >= 1000

    private fun spokenAmount(value: Double, unit: String, target: Double): String {
        if (usesLiters(unit, target)) {
            val liters = value / 1000
            return "${number(liters)} ${if (liters < 2) "litro" else "litros"}"
        }
        val word = when (unit) {
            "ml" -> "mililitros"
            "min" -> if (value == 1.0) "minuto" else "minutos"
            else -> unit
        }
        return "${number(value)} $word".trim()
    }
}

val AlertMode.label: String
    get() = when (this) {
        AlertMode.SOUND -> "Som"
        AlertMode.SPEAK -> "Som + fala"
        AlertMode.VIBRATE -> "Vibrar"
        AlertMode.SILENT -> "Silencioso"
    }
