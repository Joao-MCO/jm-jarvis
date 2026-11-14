package com.jm.jarvis.voice

/** Corta a resposta em frases enquanto ela é gerada, para a primeira já ser falada. */
object Sentences {
    /** Fim de frase: pontuação seguida de espaço. "2.500 ml" e "1,5" não cortam. */
    private val END = Regex("[.!?…]+\\s")

    /** Frases completas depois da posição [from], e a posição onde a última terminou. */
    fun complete(text: String, from: Int): Pair<List<String>, Int> {
        val found = mutableListOf<String>()
        var start = from
        END.findAll(text, from.coerceAtMost(text.length)).forEach { match ->
            val end = match.range.last + 1
            text.substring(start, end).trim().takeIf { it.isNotEmpty() }?.let(found::add)
            start = end
        }
        return found to start
    }

    /** O que sobrou depois de [from], ao fim da resposta. */
    fun rest(text: String, from: Int): String? = text.substring(from.coerceAtMost(text.length)).trim().ifEmpty { null }
}
