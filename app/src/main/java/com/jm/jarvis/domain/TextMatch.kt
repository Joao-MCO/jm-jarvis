package com.jm.jarvis.domain

/** Acha o item cujo texto mais combina com o que o usuário disse ("o do dentista" → "Ligar para o dentista"). */
object TextMatch {
    private val STOPWORDS = setOf("de", "do", "da", "o", "a", "os", "as", "um", "uma", "que", "meu", "minha", "e", "eu", "sou", "para", "pra", "lembrete")

    fun <T> best(query: String, items: List<T>, text: (T) -> String): T? {
        val wanted = words(query)
        if (wanted.isEmpty()) return null
        return items.map { it to wanted.count { w -> words(text(it)).any { n -> n.take(5) == w.take(5) } } }
            .filter { it.second > 0 }
            .maxByOrNull { it.second }
            ?.first
    }

    fun same(a: String, b: String): Boolean = words(a) == words(b)

    private fun words(text: String): List<String> =
        SpokenTime.normalize(text).split(Regex("[^a-z0-9]+")).filter { it.length >= 3 && it !in STOPWORDS }
}
