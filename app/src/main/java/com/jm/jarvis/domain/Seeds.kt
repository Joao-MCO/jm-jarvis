package com.jm.jarvis.domain

import com.jm.jarvis.data.AlertMode
import com.jm.jarvis.data.Habit
import com.jm.jarvis.data.ScheduleType
import java.time.LocalTime

/** Rotinas criadas na primeira abertura do app; depois, tudo é editável. */
object Seeds {
    val routines: List<Habit> = listOf(
        Habit(
            name = "Beber água", scheduleType = ScheduleType.INTERVAL, intervalMinutes = 60,
            windowStart = LocalTime.of(8, 0), windowEnd = LocalTime.of(22, 0),
            dailyTarget = 2500.0, unit = "ml", quickAmount = 250.0, alertMode = AlertMode.SOUND,
        ),
        Habit(name = "Tomar suplementos", scheduleType = ScheduleType.TIMES, times = listOf(LocalTime.of(8, 0)), alertMode = AlertMode.SPEAK),
        Habit(
            name = "Escovar os dentes", scheduleType = ScheduleType.TIMES,
            times = listOf(LocalTime.of(9, 0), LocalTime.of(13, 0), LocalTime.of(22, 0)), alertMode = AlertMode.SOUND,
        ),
        Habit(
            name = "Rezar", scheduleType = ScheduleType.TIMES,
            times = listOf(LocalTime.of(7, 0), LocalTime.of(21, 30)), alertMode = AlertMode.SPEAK,
        ),
        Habit(
            name = "Academia", scheduleType = ScheduleType.TIMES, times = listOf(LocalTime.of(11, 0)),
            weeklyTarget = 5, alertMode = AlertMode.SPEAK,
        ),
        Habit(
            name = "Estudar", scheduleType = ScheduleType.TIMES, times = listOf(LocalTime.of(19, 0)),
            days = Progress.WEEKDAYS, dailyTarget = 30.0, unit = "min", alertMode = AlertMode.SPEAK,
        ),
        Habit(
            name = "Ler um livro", scheduleType = ScheduleType.TIMES, times = listOf(LocalTime.of(20, 0)),
            days = Progress.WEEKDAYS, dailyTarget = 30.0, unit = "min", alertMode = AlertMode.SPEAK,
        ),
    )
}
