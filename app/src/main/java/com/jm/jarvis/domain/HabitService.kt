package com.jm.jarvis.domain

import android.content.Context
import com.jm.jarvis.data.Habit
import com.jm.jarvis.data.HabitDao
import com.jm.jarvis.data.HabitLog
import com.jm.jarvis.data.SettingsStore
import com.jm.jarvis.notify.Notifications
import com.jm.jarvis.notify.ReminderScheduler
import com.jm.jarvis.widget.JarvisWidget
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

data class Upcoming(val habit: Habit, val at: LocalDateTime)

data class TodayState(val date: LocalDate, val statuses: List<HabitStatus>, val next: Upcoming?)

/**
 * Único dono das mudanças nas rotinas. Tela, widget, notificação e (na Fase 2) a IA passam por
 * aqui, e cada mudança reagenda o alarme da rotina e atualiza o widget.
 */
class HabitService(
    private val context: Context,
    private val dao: HabitDao,
    private val scheduler: ReminderScheduler,
    private val notifications: Notifications,
) {
    private val zone: ZoneId get() = ZoneId.systemDefault()

    fun observeHabits(): Flow<List<Habit>> = dao.observeHabits()

    /** Estado de hoje, recalculado a cada registro e a cada minuto. */
    fun observeToday(): Flow<TodayState> =
        combine(dao.observeHabits(), dao.observeLogsSince(recentSince()), minuteTicks()) { habits, logs, now ->
            today(habits, logs, now)
        }

    suspend fun todayState(): TodayState = today(dao.habits(), dao.logsSince(recentSince()), LocalDateTime.now())

    suspend fun habits(): List<Habit> = dao.habits()

    /** Estado de hoje de uma rotina, mesmo que hoje não seja um dia dela. */
    suspend fun statusOf(habitId: Long): HabitStatus? =
        dao.habit(habitId)?.let { Progress.status(it, dao.logsSince(recentSince()), LocalDate.now(), zone) }

    /** Registra o botão rápido ("+250 ml", "Feito") e devolve o id do registro, para desfazer. */
    suspend fun logQuick(habitId: Long): Long? {
        val habit = dao.habit(habitId) ?: return null
        return addLog(habit, Progress.quickAmount(habit), skipped = false)
    }

    /** Registra uma quantidade dita pelo usuário; em rotinas sem meta em quantidade, ela é ignorada. */
    suspend fun log(habitId: Long, amount: Double?): Long? {
        val habit = dao.habit(habitId) ?: return null
        return addLog(habit, amount.takeIf { habit.dailyTarget != null }, skipped = false)
    }

    suspend fun skip(habitId: Long) {
        dao.habit(habitId)?.let { addLog(it, amount = null, skipped = true) }
    }

    suspend fun undo(logId: Long, habitId: Long) {
        dao.deleteLog(logId)
        afterChange(habitId)
    }

    suspend fun snooze(habitId: Long, minutes: Long) {
        dao.snooze(habitId, System.currentTimeMillis() + minutes * 60_000)
        notifications.cancel(habitId)
        afterChange(habitId)
    }

    suspend fun save(habit: Habit): Long {
        val id = if (habit.id == 0L) dao.insert(habit) else habit.id.also { dao.update(habit.copy(snoozedUntil = null)) }
        afterChange(id)
        return id
    }

    suspend fun delete(habit: Habit) {
        scheduler.scheduleHabit(habit.id, null)
        notifications.cancel(habit.id)
        dao.delete(habit)
        JarvisWidget.refresh(context)
    }

    /**
     * Chamado pelo alarme. Confirma que o aviso ainda vale (a rotina pode ter sido feita depois do
     * agendamento), agenda o próximo e devolve o estado para a notificação, ou `null` se não vale.
     */
    suspend fun onReminderDue(habitId: Long): HabitStatus? {
        val habit = dao.habit(habitId) ?: return null
        val now = LocalDateTime.now()
        val logs = dao.logsSince(recentSince())
        val pending = ReminderPlanner.next(habit, logs, now.minusMinutes(2), zone)
        val due = pending != null && pending <= now.plusMinutes(1)
        if (due) dao.markReminded(habitId, System.currentTimeMillis())
        afterChange(habitId)
        return if (due) Progress.status(habit, logs, now.toLocalDate(), zone) else null
    }

    /** Só os alarmes; quem chama atualiza o widget no fim (ver AppContainer.rearmAll). */
    suspend fun rescheduleAll() {
        dao.habits().forEach { reschedule(it.id) }
    }

    suspend fun seedIfNeeded(settings: SettingsStore) {
        if (settings.isSeeded()) return
        if (dao.habits().isEmpty()) dao.insertAll(Seeds.routines)
        settings.markSeeded()
    }

    private suspend fun addLog(habit: Habit, amount: Double?, skipped: Boolean): Long {
        val id = dao.insertLog(HabitLog(habitId = habit.id, at = System.currentTimeMillis(), amount = amount, skipped = skipped))
        notifications.cancel(habit.id)
        afterChange(habit.id)
        return id
    }

    private suspend fun afterChange(habitId: Long) {
        reschedule(habitId)
        JarvisWidget.refresh(context)
    }

    private suspend fun reschedule(habitId: Long) {
        val at = dao.habit(habitId)?.let { ReminderPlanner.next(it, dao.logsSince(recentSince()), LocalDateTime.now(), zone) }
        scheduler.scheduleHabit(habitId, at?.atZone(zone)?.toInstant())
    }

    private fun today(habits: List<Habit>, logs: List<HabitLog>, now: LocalDateTime): TodayState {
        val date = now.toLocalDate()
        val statuses = habits.filter { it.active && date.dayOfWeek in it.days }
            .map { Progress.status(it, logs, date, zone) }
        val next = habits.mapNotNull { habit -> ReminderPlanner.next(habit, logs, now, zone)?.let { Upcoming(habit, it) } }
            .minByOrNull { it.at }
        return TodayState(date, statuses, next)
    }

    /** Oito dias de registros bastam: a semana atual e a margem do planejador. */
    private fun recentSince(): Long = System.currentTimeMillis() - 8 * 24 * 3_600_000L

    private fun minuteTicks(): Flow<LocalDateTime> = flow {
        while (true) {
            emit(LocalDateTime.now())
            delay(60_000 - System.currentTimeMillis() % 60_000)
        }
    }
}
