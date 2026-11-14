package com.jm.jarvis.brain

import com.jm.jarvis.data.Habit
import com.jm.jarvis.domain.HabitMatcher
import com.jm.jarvis.domain.SpokenTime

/**
 * Comandos de frase certa que não precisam da IA: o Kotlin entende e executa na hora, sem esperar
 * o modelo (que leva uns 10 s e às vezes erra). Só casa quando há certeza; qualquer dúvida cai para a IA.
 */
sealed interface Shortcut {
    fun run(tools: JarvisTools): String

    data class Torch(val on: Boolean) : Shortcut {
        override fun run(tools: JarvisTools) = tools.celular("lanterna", null, if (on) "ligar" else "desligar")
    }

    data class Volume(val value: String) : Shortcut {
        override fun run(tools: JarvisTools) = tools.celular("volume", null, value)
    }

    data class OpenApp(val name: String) : Shortcut {
        override fun run(tools: JarvisTools) = tools.celular("abrir", name)
    }

    data class Timer(val minutes: Int, val seconds: Int) : Shortcut {
        override fun run(tools: JarvisTools) = tools.relogio("timer", minutos = minutes, segundos = seconds)
    }

    data class Alarm(val time: String) : Shortcut {
        override fun run(tools: JarvisTools) = tools.relogio("despertador", horario = time)
    }

    data class Stopwatch(val action: String) : Shortcut {
        override fun run(tools: JarvisTools) = tools.cronometro(action)
    }

    data class LogHabit(val query: String, val amount: Double?) : Shortcut {
        override fun run(tools: JarvisTools) = tools.registrarHabito(query, amount)
    }

    companion object {
        /** O atalho para a frase dita, ou `null` se ela precisa da IA. */
        fun match(spoken: String, habits: List<Habit>): Shortcut? {
            val text = SpokenTime.normalize(spoken).trimEnd('.', '!', '?', ' ')
            return torch(text) ?: volume(text) ?: stopwatch(text) ?: timer(text) ?: alarm(text)
                ?: openApp(text) ?: logHabit(text, habits)
        }

        private val ON = Regex("""\b(lig\w*|acend\w*|ativ\w*)\b""")
        private val OFF = Regex("""\b(deslig\w*|apag\w*|desativ\w*)\b""")

        private fun torch(text: String): Shortcut? {
            if ("lanterna" !in text) return null
            return when {
                OFF.containsMatchIn(text) -> Torch(false)
                ON.containsMatchIn(text) -> Torch(true)
                else -> null
            }
        }

        private fun volume(text: String): Shortcut? {
            if ("volume" !in text || text.startsWith("qual")) return null
            val value = when {
                Regex("""\b(aument\w*|sob\w*|mais)\b""").containsMatchIn(text) -> "mais"
                Regex("""\b(abaix\w*|diminu\w*|baix\w*|menos)\b""").containsMatchIn(text) -> "menos"
                Regex("""\b(mud\w*|silenc\w*)\b""").containsMatchIn(text) -> "mudo"
                Regex("""\bmax\w*""").containsMatchIn(text) -> "maximo"
                else -> Regex("""\b(\d{1,3})\s*(%|por ?cento)?""").find(text)?.groupValues?.get(1)
            }
            return value?.let(::Volume)
        }

        private fun stopwatch(text: String): Shortcut? {
            if ("cronometro" !in text) return if (Regex("""^quanto (tempo )?deu\b""").containsMatchIn(text)) Stopwatch("consultar") else null
            if ("timer" in text) return null
            val action = when {
                Regex("""\b(zer\w*|reinici\w*)\b""").containsMatchIn(text) -> "zerar"
                Regex("""\b(inici\w*|comec\w*|dispar\w*|lig\w*)\b""").containsMatchIn(text) -> "iniciar"
                Regex("""\b(paus\w*|par[ae])\b""").containsMatchIn(text) -> "pausar"
                Regex("""\b(contin\w*|retom\w*)\b""").containsMatchIn(text) -> "continuar"
                Regex("""\b(quanto|marca\w*|consult\w*|tempo)\b""").containsMatchIn(text) -> "consultar"
                else -> return null
            }
            return Stopwatch(action)
        }

        private val DURATION = Regex("""(\d+)\s*(horas?|h|minutos?|mins?|segundos?|segs?|s)\b""")

        private fun timer(text: String): Shortcut? {
            if (!Regex("""\b(timer|temporizador)\b""").containsMatchIn(text)) return null
            var seconds = 0
            DURATION.findAll(text).forEach { m ->
                val n = m.groupValues[1].toInt()
                seconds += when (m.groupValues[2].first()) {
                    'h' -> n * 3600
                    'm' -> n * 60
                    else -> n
                }
            }
            return if (seconds in 1..86_400) Timer(seconds / 60, seconds % 60) else null
        }

        private val ALARM_WORDS = Regex("""\b(me acorda|me desperta|despertador|alarme)\b""")
        private val DAY_WORDS = Regex("""segunda|terca|quarta|quinta|sexta|sabado|domingo|dias uteis|\btod[oa]s?\b|fim de semana""")
        private val ALARM_TIME = Regex("""\bas\s+(\d{1,2})(?:\s*(?:h|:|horas?)\s*(\d{2})?)?""")

        private fun alarm(text: String): Shortcut? {
            if (!ALARM_WORDS.containsMatchIn(text) || DAY_WORDS.containsMatchIn(text)) return null
            val m = ALARM_TIME.find(text) ?: return null
            val hour = m.groupValues[1].toInt()
            val minute = m.groupValues[2].ifEmpty { "0" }.toInt()
            if (hour !in 0..23 || minute !in 0..59) return null
            return Alarm("%02d:%02d".format(hour, minute))
        }

        private fun openApp(text: String): Shortcut? {
            val name = Regex("""^(?:abre|abra|abrir)\s+(?:o |a |os |as )?(?:app |aplicativo )?(.+)$""").find(text)?.groupValues?.get(1)?.trim()
            return name?.takeIf { it.isNotEmpty() }?.let(::OpenApp)
        }

        private val DONE = Regex("""^(?:(?:ja|eu|agora|acabei de)\s+)*(?:bebi|tomei|escovei|fui|treinei|estudei|meditei|rezei|li|fiz|corri|caminhei)\b\s*(.*)$""")
        private val AMOUNT = Regex("""(\d+(?:[.,]\d+)?)\s*(ml|mililitros?|litros?|l|minutos?|mins?)\b""")

        private val SPOKEN_NUMBER = Regex("""\b(meio|meia|um|uma|dois|duas|tres|quatro|cinco)\s+(litros?|minutos?|mililitros?)\b""")
        private val NUMBER_WORDS = mapOf("meio" to "0.5", "meia" to "0.5", "um" to "1", "uma" to "1", "dois" to "2", "duas" to "2", "tres" to "3", "quatro" to "4", "cinco" to "5")

        private fun logHabit(spoken: String, habits: List<Habit>): Shortcut? {
            // O reconhecedor de voz escreve "um litro" por extenso: vira "1 litro".
            val text = SPOKEN_NUMBER.replace(spoken) { "${NUMBER_WORDS.getValue(it.groupValues[1])} ${it.groupValues[2]}" }
            val rest = DONE.find(text)?.groupValues?.get(1) ?: return null
            val amount = AMOUNT.find(text)?.let { m ->
                val n = m.groupValues[1].replace(',', '.').toDouble()
                if (m.groupValues[2].startsWith("l")) n * 1000 else n
            }
            val query = rest.replace(AMOUNT, " ").replace(Regex("""\b(hoje|agora|ja)\b"""), " ").trim()
            if (HabitMatcher.find(query, habits) == null) return null
            return LogHabit(query, amount)
        }
    }
}
