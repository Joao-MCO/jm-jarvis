package com.jm.jarvis.domain

/** Cronômetro do JARVIS. Guarda só o necessário para sobreviver ao app ser fechado. */
data class Stopwatch(
    /** Momento (relógio de parede, ms) em que voltou a correr; `null` se está parado. */
    val runningSince: Long? = null,
    /** Tempo acumulado antes da última partida. */
    val accumulatedMs: Long = 0,
) {
    val running: Boolean get() = runningSince != null

    fun elapsed(now: Long): Long = accumulatedMs + (runningSince?.let { (now - it).coerceAtLeast(0) } ?: 0)

    fun start(now: Long): Stopwatch = if (running) this else copy(runningSince = now)

    fun pause(now: Long): Stopwatch = if (!running) this else Stopwatch(runningSince = null, accumulatedMs = elapsed(now))

    fun reset(): Stopwatch = Stopwatch()

    companion object {
        /** "1 hora, 2 minutos e 5 segundos", do jeito que se fala. */
        fun spoken(ms: Long): String {
            val total = ms / 1000
            val parts = listOfNotNull(
                (total / 3600).takeIf { it > 0 }?.let { "$it ${if (it == 1L) "hora" else "horas"}" },
                (total % 3600 / 60).takeIf { it > 0 }?.let { "$it ${if (it == 1L) "minuto" else "minutos"}" },
                (total % 60).takeIf { it > 0 || total == 0L }?.let { "$it ${if (it == 1L) "segundo" else "segundos"}" },
            )
            return if (parts.size == 1) parts[0] else parts.dropLast(1).joinToString(", ") + " e " + parts.last()
        }
    }
}
