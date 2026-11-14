package com.jm.jarvis.domain

import com.jm.jarvis.data.Habit
import com.jm.jarvis.data.HabitLog
import com.jm.jarvis.data.ScheduleType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset

class ReminderPlannerTest {
    private val zone = ZoneOffset.UTC
    private val mon = LocalDate.of(2026, 11, 16).also { check(it.dayOfWeek == DayOfWeek.MONDAY) }

    private val brush = Habit(
        id = 1, name = "Escovar os dentes", scheduleType = ScheduleType.TIMES,
        times = listOf(LocalTime.of(9, 0), LocalTime.of(13, 0), LocalTime.of(22, 0)),
    )
    private val study = Habit(
        id = 2, name = "Estudar", scheduleType = ScheduleType.TIMES, times = listOf(LocalTime.of(19, 0)),
        days = Progress.WEEKDAYS, dailyTarget = 30.0, unit = "min",
    )
    private val gym = Habit(id = 3, name = "Academia", scheduleType = ScheduleType.TIMES, times = listOf(LocalTime.of(11, 0)), weeklyTarget = 5)
    private val water = Habit(
        id = 4, name = "Beber água", scheduleType = ScheduleType.INTERVAL, intervalMinutes = 60,
        windowStart = LocalTime.of(8, 0), windowEnd = LocalTime.of(22, 0), dailyTarget = 2500.0, unit = "ml", quickAmount = 250.0,
    )

    private fun t(date: LocalDate, hour: Int, minute: Int = 0): LocalDateTime = date.atTime(hour, minute)
    private fun millis(at: LocalDateTime) = at.toInstant(zone).toEpochMilli()
    private fun log(habit: Habit, at: LocalDateTime, amount: Double? = null, skipped: Boolean = false) =
        HabitLog(habitId = habit.id, at = millis(at), amount = amount, skipped = skipped)
    private fun next(habit: Habit, now: LocalDateTime, vararg logs: HabitLog) = ReminderPlanner.next(habit, logs.toList(), now, zone)

    @Test
    fun `fixed times give the next slot of the day`() = assertEquals(t(mon, 9), next(brush, t(mon, 8)))

    @Test
    fun `doing it shortly before a slot skips that slot`() =
        assertEquals(t(mon, 13), next(brush, t(mon, 8, 55), log(brush, t(mon, 8, 50))))

    @Test
    fun `answering a reminder late does not skip the following slot`() =
        assertEquals(t(mon, 13), next(brush, t(mon, 9, 6), log(brush, t(mon, 9, 5))))

    @Test
    fun `skipping a reminder does not skip the following slot`() =
        assertEquals(t(mon, 13), next(brush, t(mon, 9, 2), log(brush, t(mon, 9, 1), skipped = true)))

    @Test
    fun `after the last slot comes tomorrow's first`() = assertEquals(t(mon.plusDays(1), 9), next(brush, t(mon, 22, 1)))

    @Test
    fun `weekday routine on saturday waits for monday`() =
        assertEquals(t(mon.plusDays(7), 19), next(study, t(mon.plusDays(5), 10)))

    @Test
    fun `daily target reached skips the rest of the day`() =
        assertEquals(t(mon.plusDays(1), 19), next(study, t(mon, 18, 30), log(study, t(mon, 18), amount = 30.0)))

    @Test
    fun `partial progress keeps today's reminder`() =
        assertEquals(t(mon, 19), next(study, t(mon, 18, 30), log(study, t(mon, 18), amount = 10.0)))

    @Test
    fun `weekly target met stops reminders until next week`() {
        val week = (0L..4L).map { log(gym, t(mon.plusDays(it), 8)) }.toTypedArray()
        assertEquals(t(mon.plusDays(7), 11), next(gym, t(mon.plusDays(4), 12), *week))
    }

    @Test
    fun `weekly routine done today waits for tomorrow`() {
        val tue = mon.plusDays(1)
        assertEquals(t(tue.plusDays(1), 11), next(gym, t(tue, 9, 30), log(gym, t(tue, 9))))
    }

    @Test
    fun `interval counts from the last reminder`() =
        assertEquals(t(mon, 11), next(water.copy(lastRemindedAt = millis(t(mon, 10))), t(mon, 10, 30)))

    @Test
    fun `interval counts from the last drink`() = assertEquals(
        t(mon, 11, 20),
        next(water.copy(lastRemindedAt = millis(t(mon, 10))), t(mon, 10, 30), log(water, t(mon, 10, 20), amount = 250.0)),
    )

    @Test
    fun `first use in the middle of the day starts one interval from now`() =
        assertEquals(t(mon, 20, 10), next(water, t(mon, 19, 10)))

    @Test
    fun `interval past the window moves to tomorrow morning`() =
        assertEquals(t(mon.plusDays(1), 8), next(water.copy(lastRemindedAt = millis(t(mon, 21, 30))), t(mon, 21, 40)))

    @Test
    fun `interval stops when the daily target is reached`() {
        val drinks = (0..9).map { log(water, t(mon, 9 + it / 2, (it % 2) * 30), amount = 250.0) }.toTypedArray()
        assertEquals(t(mon.plusDays(1), 8), next(water, t(mon, 15), *drinks))
    }

    @Test
    fun `snooze wins over the schedule`() =
        assertEquals(t(mon, 9, 15), next(brush.copy(snoozedUntil = millis(t(mon, 9, 15))), t(mon, 9, 1)))

    @Test
    fun `paused routine never reminds`() = assertNull(next(brush.copy(active = false), t(mon, 8)))
}
