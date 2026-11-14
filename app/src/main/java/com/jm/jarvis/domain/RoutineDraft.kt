package com.jm.jarvis.domain

import com.jm.jarvis.data.AlertMode
import com.jm.jarvis.data.Habit
import com.jm.jarvis.data.ScheduleType
import java.time.DayOfWeek
import java.time.LocalTime

enum class GoalKind { NONE, DAILY, WEEKLY }

sealed interface DraftResult {
    data class Valid(val habit: Habit) : DraftResult
    data class Invalid(val message: String) : DraftResult
}

/** O que está sendo digitado no editor de rotina; vira [Habit] só depois de validar. */
data class RoutineDraft(
    val base: Habit? = null,
    val name: String = "",
    val type: ScheduleType = ScheduleType.TIMES,
    val times: List<LocalTime> = listOf(LocalTime.of(8, 0)),
    val interval: String = "60",
    val windowStart: LocalTime = LocalTime.of(8, 0),
    val windowEnd: LocalTime = LocalTime.of(22, 0),
    val days: Set<DayOfWeek> = DayOfWeek.entries.toSet(),
    val goal: GoalKind = GoalKind.NONE,
    val dailyTarget: String = "",
    val unit: String = "",
    val quickAmount: String = "",
    val weeklyTarget: String = "",
    val alertMode: AlertMode = AlertMode.SPEAK,
    val active: Boolean = true,
) {
    fun validate(): DraftResult {
        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) return DraftResult.Invalid("Dê um nome à rotina.")
        if (days.isEmpty()) return DraftResult.Invalid("Escolha pelo menos um dia.")

        var intervalMinutes: Int? = null
        when (type) {
            ScheduleType.TIMES -> if (times.isEmpty()) return DraftResult.Invalid("Adicione pelo menos um horário.")
            ScheduleType.INTERVAL -> {
                intervalMinutes = interval.trim().toIntOrNull()?.takeIf { it in 5..720 }
                    ?: return DraftResult.Invalid("O intervalo deve ser de 5 a 720 minutos.")
                if (windowEnd <= windowStart) return DraftResult.Invalid("O fim da janela deve ser depois do início.")
            }
        }

        var target: Double? = null
        var unitText: String? = null
        var quick: Double? = null
        var weekly: Int? = null
        when (goal) {
            GoalKind.NONE -> Unit
            GoalKind.DAILY -> {
                target = dailyTarget.toPositiveNumber() ?: return DraftResult.Invalid("Informe a meta por dia.")
                unitText = unit.trim().ifEmpty { return DraftResult.Invalid("Informe a unidade da meta (ex.: ml, min).") }
                if (quickAmount.isNotBlank()) {
                    quick = quickAmount.toPositiveNumber() ?: return DraftResult.Invalid("O botão rápido precisa de um número maior que zero.")
                }
            }
            GoalKind.WEEKLY -> weekly = weeklyTarget.trim().toIntOrNull()?.takeIf { it in 1..7 }
                ?: return DraftResult.Invalid("A meta semanal deve ser de 1 a 7 vezes.")
        }

        val fields = (base ?: Habit(name = trimmedName, scheduleType = type)).copy(
            name = trimmedName,
            scheduleType = type,
            times = if (type == ScheduleType.TIMES) times.distinct().sorted() else emptyList(),
            intervalMinutes = intervalMinutes,
            windowStart = windowStart.takeIf { type == ScheduleType.INTERVAL },
            windowEnd = windowEnd.takeIf { type == ScheduleType.INTERVAL },
            days = days,
            dailyTarget = target,
            unit = unitText,
            quickAmount = quick,
            weeklyTarget = weekly,
            alertMode = alertMode,
            active = active,
        )
        return DraftResult.Valid(fields)
    }

    companion object {
        fun from(habit: Habit) = RoutineDraft(
            base = habit,
            name = habit.name,
            type = habit.scheduleType,
            times = habit.times.ifEmpty { listOf(LocalTime.of(8, 0)) },
            interval = habit.intervalMinutes?.toString() ?: "60",
            windowStart = habit.windowStart ?: LocalTime.of(8, 0),
            windowEnd = habit.windowEnd ?: LocalTime.of(22, 0),
            days = habit.days,
            goal = when {
                habit.weeklyTarget != null -> GoalKind.WEEKLY
                habit.dailyTarget != null -> GoalKind.DAILY
                else -> GoalKind.NONE
            },
            dailyTarget = habit.dailyTarget?.let(::plain).orEmpty(),
            unit = habit.unit.orEmpty(),
            quickAmount = habit.quickAmount?.let(::plain).orEmpty(),
            weeklyTarget = habit.weeklyTarget?.toString().orEmpty(),
            alertMode = habit.alertMode,
            active = habit.active,
        )

        private fun plain(value: Double): String =
            if (value % 1.0 == 0.0) value.toLong().toString() else value.toString().replace('.', ',')
    }
}

private fun String.toPositiveNumber(): Double? = trim().replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 }

private val CLOCK = Regex("""^(\d{1,2})(?:\s*(?:[:h]|horas?)\s*(\d{2})?)?\s*(?:min)?$""")

/** Horário dito ou digitado: "08:00", "8:30", "8h", "20h30", "8". `null` se não for um horário. */
fun parseClock(text: String): LocalTime? {
    val match = CLOCK.matchEntire(text.trim().lowercase()) ?: return null
    val hour = match.groupValues[1].toInt()
    val minute = match.groupValues[2].ifEmpty { "0" }.toInt()
    return if (hour in 0..23 && minute in 0..59) LocalTime.of(hour, minute) else null
}
