package com.jm.jarvis.domain

import com.jm.jarvis.data.Habit
import com.jm.jarvis.data.HabitLog
import com.jm.jarvis.data.ScheduleType
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/** Decide quando cada rotina deve avisar de novo. Sem estado: tudo vem dos argumentos. */
object ReminderPlanner {

    private data class Entry(val at: LocalDateTime, val amount: Double?, val skipped: Boolean)

    /** Próximo aviso depois de [now], olhando no máximo uma semana à frente; `null` se não há. */
    fun next(habit: Habit, logs: List<HabitLog>, now: LocalDateTime, zone: ZoneId): LocalDateTime? {
        if (!habit.active) return null
        val entries = logs.filter { it.habitId == habit.id }
            .map { Entry(it.at.toLocal(zone), it.amount, it.skipped) }
            .filter { it.at <= now }

        habit.snoozedUntil?.toLocal(zone)?.let { if (it > now) return it }
        val lastReminded = habit.lastRemindedAt?.toLocal(zone)

        for (offset in 0L..7L) {
            val date = now.toLocalDate().plusDays(offset)
            if (date.dayOfWeek !in habit.days || dayComplete(habit, entries, date)) continue
            val slot = when (habit.scheduleType) {
                ScheduleType.TIMES -> nextFixedSlot(habit, entries, date, now)
                ScheduleType.INTERVAL -> nextIntervalSlot(habit, entries, lastReminded, date, now)
            }
            if (slot != null) return slot
        }
        return null
    }

    /** Dia em que a meta já foi cumprida e nenhum aviso é necessário. */
    private fun dayComplete(habit: Habit, entries: List<Entry>, date: LocalDate): Boolean {
        val done = entries.filter { !it.skipped }
        habit.weeklyTarget?.let { target ->
            if (done.any { it.at.toLocalDate() == date }) return true
            val monday = date.with(DayOfWeek.MONDAY)
            val daysThisWeek = done.map { it.at.toLocalDate() }.filter { it in monday..date }.distinct().size
            return daysThisWeek >= target
        }
        habit.dailyTarget?.let { target ->
            return done.filter { it.at.toLocalDate() == date }.sumOf { it.amount ?: 0.0 } >= target
        }
        return false
    }

    /**
     * Um horário fixo é dispensado quando a rotina já foi feita perto dele: um registro conta para
     * o horário mais próximo, então escovar às 12:50 dispensa o aviso das 13:00, mas responder às
     * 09:05 ao aviso das 09:00 não dispensa o das 13:00. Em rotinas com meta, só "Pular" dispensa;
     * a meta cumprida é tratada em [dayComplete].
     */
    private fun nextFixedSlot(habit: Habit, entries: List<Entry>, date: LocalDate, now: LocalDateTime): LocalDateTime? {
        val times = habit.times.distinct().sorted()
        if (times.isEmpty()) return null
        val anyEntryCounts = habit.dailyTarget == null && habit.weeklyTarget == null

        for ((i, time) in times.withIndex()) {
            val slot = date.atTime(time)
            if (slot <= now) continue
            val previous = if (i > 0) date.atTime(times[i - 1]) else date.minusDays(1).atTime(times.last())
            val nearerThisSlot = previous.plus(Duration.between(previous, slot).dividedBy(2))
            val handled = entries.any { it.at > nearerThisSlot && (anyEntryCounts || it.skipped) }
            if (!handled) return slot
        }
        return null
    }

    /** Avisa [Habit.intervalMinutes] depois do último registro ou aviso, dentro da janela do dia. */
    private fun nextIntervalSlot(
        habit: Habit,
        entries: List<Entry>,
        lastReminded: LocalDateTime?,
        date: LocalDate,
        now: LocalDateTime,
    ): LocalDateTime? {
        val minutes = habit.intervalMinutes?.takeIf { it > 0 }?.toLong() ?: return null
        val start = date.atTime(habit.windowStart ?: LocalTime.MIN)
        val end = date.atTime(habit.windowEnd ?: LocalTime.of(23, 59))
        val lastEvent = (entries.map { it.at } + listOfNotNull(lastReminded))
            .filter { it.toLocalDate() == date }
            .maxOrNull()

        val candidate = when {
            lastEvent != null -> lastEvent.plusMinutes(minutes)
            // Primeiro uso no meio do dia: começa um intervalo depois de agora, não na hora.
            date == now.toLocalDate() && now >= start -> now.plusMinutes(minutes)
            else -> start
        }
        // Aviso atrasado (celular desligado, por exemplo) sai em um minuto.
        val slot = maxOf(candidate, start, now.plusMinutes(1))
        return slot.takeIf { it <= end }
    }

    private fun Long.toLocal(zone: ZoneId): LocalDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(this), zone)
}
