package com.jm.jarvis.brain

import com.jm.jarvis.data.Habit
import com.jm.jarvis.domain.HabitMatcher
import com.jm.jarvis.domain.SpokenTime
import java.text.Normalizer
import java.time.DayOfWeek
import java.time.LocalDateTime

/**
 * Comandos de frase certa que não precisam da IA: o Kotlin entende e executa na hora, sem esperar
 * o modelo (que leva uns 10 s e às vezes erra). Só casa quando há certeza; qualquer dúvida cai para a IA.
 * Perguntas só casam com atalhos de leitura: perguntar nunca muda nada.
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

    data class HabitProgress(val query: String) : Shortcut {
        override fun run(tools: JarvisTools) = tools.progressoHabito(query)
    }

    data class Weather(val days: Int) : Shortcut {
        override fun run(tools: JarvisTools) = tools.clima(days)
    }

    data class Agenda(val days: Int) : Shortcut {
        override fun run(tools: JarvisTools) = tools.eventosDaAgenda(days)
    }

    data object DaySummary : Shortcut {
        override fun run(tools: JarvisTools) = tools.resumoDoDia()
    }

    data object PendingHabits : Shortcut {
        override fun run(tools: JarvisTools) = tools.listarHabitos()
    }

    data object PendingReminders : Shortcut {
        override fun run(tools: JarvisTools) = tools.lembrete("listar")
    }

    data class Remind(val text: String, val whenText: String) : Shortcut {
        override fun run(tools: JarvisTools) = tools.lembrete("criar", text, whenText)
    }

    data class Remember(val fact: String) : Shortcut {
        override fun run(tools: JarvisTools) = tools.memoria("guardar", fact)
    }

    companion object {
        /** O atalho para a frase dita, ou `null` se ela precisa da IA. */
        fun match(spoken: String, habits: List<Habit>, now: LocalDateTime = LocalDateTime.now()): Shortcut? {
            val original = spoken.trim().trimEnd('.', '!', '?', ' ')
            val text = fold(original)
            val asked = Question.asked(spoken)
            val read = weather(text, now) ?: agenda(text) ?: summary(text) ?: pendingHabits(text) ?: pendingReminders(text)
                ?: stopwatch(text, readOnly = true) ?: progress(text, habits, asked)
            if (read != null || asked) return read
            return torch(text) ?: volume(text) ?: stopwatch(text, readOnly = false) ?: timer(text) ?: alarm(text)
                ?: openApp(text) ?: remind(original, text, now) ?: remember(original, text) ?: logHabit(text, habits)
        }

        /** Minúsculas e sem acentos, letra por letra: os índices continuam valendo no texto original. */
        private fun fold(text: String): String = buildString {
            for (c in text) append(Normalizer.normalize(c.toString(), Normalizer.Form.NFD).first().lowercaseChar())
        }

        // ---- leitura ----

        private val WEATHER = Regex("""\b(chover|chuva|previsao|clima|temperatura|vai fazer (frio|calor|sol))\b|\bque tempo\b|\btempo (esta|vai|faz|fica)\b""")

        private fun weather(text: String, now: LocalDateTime): Shortcut? {
            if (!WEATHER.containsMatchIn(text) || "timer" in text || "cronometro" in text) return null
            val days = when {
                "depois de amanha" in text -> 3
                "amanha" in text -> 2
                "semana" in text || "proximos dias" in text -> 7
                "hoje" in text -> 1
                else -> weekdayDistance(text, now.dayOfWeek) ?: 2
            }
            return Weather(days)
        }

        private fun weekdayDistance(text: String, today: DayOfWeek): Int? {
            val day = Regex("""\b(segunda|terca|quarta|quinta|sexta|sabado|domingo)\b""").find(text)?.value
                ?.let { SpokenTime.days(listOf(it)).firstOrNull() } ?: return null
            val diff = (day.value - today.value + 7) % 7
            return if (diff == 0) 1 else diff + 1
        }

        private val AGENDA = Regex("""\b(agenda|compromissos?|eventos?|reunioes|reuniao)\b""")
        private val AGENDA_ASK = Regex("""\b(tenho|tem|meus|minha|minhas|qual|quais|mostra|le|me fala|me diz)\b""")

        private fun agenda(text: String): Shortcut? {
            if (!AGENDA.containsMatchIn(text) || !AGENDA_ASK.containsMatchIn(text)) return null
            if (Regex("""\b(cria|criar|adiciona|marca|marcar|agenda (uma|um|para))\b""").containsMatchIn(text)) return null
            return Agenda(
                when {
                    "depois de amanha" in text -> 3
                    "amanha" in text -> 2
                    "semana" in text -> 7
                    else -> 1
                },
            )
        }

        private fun summary(text: String): Shortcut? =
            if (Regex("""\b(resumo do dia|como (esta|foi|vai) (o )?(meu )?dia|me atualiza)\b""").containsMatchIn(text)) DaySummary else null

        private fun pendingHabits(text: String): Shortcut? {
            val asks = Regex("""\b(quais|que|o que)\b.*\b(rotinas?|habitos?)\b.*\b(faltam?|falta|pendentes?|hoje)\b""").containsMatchIn(text) ||
                Regex("""\bo que (ainda )?falta (fazer )?hoje\b""").containsMatchIn(text)
            return if (asks) PendingHabits else null
        }

        private fun pendingReminders(text: String): Shortcut? =
            if (Regex("""\b(quais|meus|mostra|lista)\b.*\blembretes\b""").containsMatchIn(text)) PendingReminders else null

        private val PROGRESS_OPENING = Regex("""^(?:e )?(?:eu )?(?:quanto|quantos|quantas|ja)\b""")
        private val QUESTION_NOISE = Regex("""\b(quanto|quantos|quantas|ja|eu|hoje|agora|de|do|da|ate|aqui|ainda|tomei|bebi|fiz|fui|li|comi)\b""")

        private fun progress(text: String, habits: List<Habit>, asked: Boolean): Shortcut? {
            if (!asked || !PROGRESS_OPENING.containsMatchIn(text)) return null
            val query = text.replace(QUESTION_NOISE, " ").replace(Regex("""\s+"""), " ").trim()
            return if (query.isNotEmpty() && HabitMatcher.find(query, habits) != null) HabitProgress(query) else null
        }

        // ---- ações ----

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
            if ("volume" !in text) return null
            val value = when {
                Regex("""\b(aument\w*|sob\w*|mais)\b""").containsMatchIn(text) -> "mais"
                Regex("""\b(abaix\w*|diminu\w*|baix\w*|menos)\b""").containsMatchIn(text) -> "menos"
                Regex("""\b(mud\w*|silenc\w*)\b""").containsMatchIn(text) -> "mudo"
                Regex("""\bmax\w*""").containsMatchIn(text) -> "maximo"
                else -> Regex("""\b(\d{1,3})\s*(%|por ?cento)?""").find(text)?.groupValues?.get(1)
            }
            return value?.let(::Volume)
        }

        private fun stopwatch(text: String, readOnly: Boolean): Shortcut? {
            if ("cronometro" !in text) {
                return if (readOnly && Regex("""^quanto (tempo )?deu\b""").containsMatchIn(text)) Stopwatch("consultar") else null
            }
            if ("timer" in text) return null
            val action = when {
                Regex("""\b(zer\w*|reinici\w*)\b""").containsMatchIn(text) -> "zerar"
                Regex("""\b(inici\w*|comec\w*|dispar\w*|lig\w*)\b""").containsMatchIn(text) -> "iniciar"
                Regex("""\b(paus\w*|par[ae])\b""").containsMatchIn(text) -> "pausar"
                Regex("""\b(contin\w*|retom\w*)\b""").containsMatchIn(text) -> "continuar"
                Regex("""\b(quanto|marca\w*|consult\w*|tempo)\b""").containsMatchIn(text) -> "consultar"
                else -> return null
            }
            return if (readOnly == (action == "consultar")) Stopwatch(action) else null
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

        private const val TIME = """(?:\d{1,2})(?:\s*(?:h|:|horas?)\s*\d{0,2})?"""
        private const val DAY = """(?:depois de amanha|amanha|hoje|(?:na |no )?(?:segunda|terca|quarta|quinta|sexta|sabado|domingo)\w*)"""
        private const val WHEN = """(?:(?:daqui a|em) \d+\s*\w+(?: e \d+\s*\w+)?|$DAY(?: (?:de manha|de tarde|de noite))?(?: as $TIME)?|as $TIME)"""
        private val WHEN_WHOLE = Regex("""^$WHEN$""")
        private val WHEN_AT_END = Regex("""(?:^|\s)($WHEN)$""")
        private val WHEN_AT_START = Regex("""^($WHEN)\s+""")
        private val WHEN_ANYWHERE = Regex("""\b$WHEN\b""")
        private val REMIND = Regex("""\bme (?:lembra|lembre|avisa|avise)\b(?: de| que| para| pra)?\s+""")

        /** "me lembra de X amanhã às 15h", "daqui a 20 minutos me lembra de X": o horário é validado pelo [SpokenTime]. */
        private fun remind(original: String, text: String, now: LocalDateTime): Shortcut? {
            val verb = REMIND.find(text) ?: return null
            val before = text.substring(0, verb.range.first).trim()
            val afterStart = verb.range.last + 1
            val after = text.substring(afterStart)
            val what: IntRange
            val whenRange: IntRange
            if (before.isNotEmpty()) {
                if (!WHEN_WHOLE.matches(before)) return null
                what = afterStart until text.length
                whenRange = 0 until before.length
            } else {
                val atEnd = WHEN_AT_END.find(after)?.groups?.get(1)?.range
                val atStart = WHEN_AT_START.find(after)?.groups?.get(1)?.range
                when {
                    atEnd != null -> {
                        what = afterStart until afterStart + atEnd.first
                        whenRange = afterStart + atEnd.first..afterStart + atEnd.last
                    }
                    atStart != null -> {
                        what = afterStart + atStart.last + 1 until text.length
                        whenRange = afterStart + atStart.first..afterStart + atStart.last
                    }
                    else -> return null
                }
            }
            val whenText = original.substring(whenRange.first, whenRange.last + 1)
            val taskText = original.substring(what.first, what.last + 1).trim().trimStart { !it.isLetterOrDigit() }
                .removePrefix("de ").trim()
            if (taskText.length < 2 || SpokenTime.moment(whenText, now) == null) return null
            return Remind(taskText, whenText)
        }

        private val REMEMBER = Regex("""^(?:nao esquece que|lembra que|lembre que|anota que|anote que|guarda que|guarde que|grava que)\s+""")

        private fun remember(original: String, text: String): Shortcut? {
            val m = REMEMBER.find(text) ?: return null
            if (WHEN_ANYWHERE.containsMatchIn(text.substring(m.range.last + 1))) return null
            return original.substring(m.range.last + 1).trim().takeIf { it.length >= 3 }?.let(::Remember)
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
