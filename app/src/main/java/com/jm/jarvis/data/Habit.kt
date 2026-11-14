package com.jm.jarvis.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.DayOfWeek
import java.time.LocalTime

enum class ScheduleType { TIMES, INTERVAL }

/** Como o JARVIS chama na hora da rotina. */
enum class AlertMode { SOUND, SPEAK, VIBRATE, SILENT }

/** Uma rotina acompanhada: quando avisar e qual a meta. */
@Entity(tableName = "habits")
data class Habit(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val scheduleType: ScheduleType,
    /** Horários fixos do dia, para [ScheduleType.TIMES]. */
    val times: List<LocalTime> = emptyList(),
    /** Intervalo entre avisos e a janela do dia em que valem, para [ScheduleType.INTERVAL]. */
    val intervalMinutes: Int? = null,
    val windowStart: LocalTime? = null,
    val windowEnd: LocalTime? = null,
    val days: Set<DayOfWeek> = DayOfWeek.entries.toSet(),
    /** Meta diária em [unit], ex.: 2500 ml ou 30 min. Sem ela, cada registro conta uma vez. */
    val dailyTarget: Double? = null,
    val unit: String? = null,
    /** Quanto o botão rápido registra, ex.: 250 ml. Sem valor, registra a meta inteira. */
    val quickAmount: Double? = null,
    /** Meta de dias por semana, ex.: academia 5x. */
    val weeklyTarget: Int? = null,
    val alertMode: AlertMode = AlertMode.SOUND,
    val active: Boolean = true,
    val snoozedUntil: Long? = null,
    val lastRemindedAt: Long? = null,
)

/** Um registro de rotina feita ou de aviso dispensado. */
@Entity(
    tableName = "habit_logs",
    foreignKeys = [
        ForeignKey(entity = Habit::class, parentColumns = ["id"], childColumns = ["habitId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("habitId"), Index("at")],
)
data class HabitLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val habitId: Long,
    val at: Long,
    val amount: Double? = null,
    /** Aviso dispensado com "Pular": não conta como feito. */
    val skipped: Boolean = false,
)
