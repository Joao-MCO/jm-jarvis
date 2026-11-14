package com.jm.jarvis.domain

import com.jm.jarvis.data.Habit
import java.text.Normalizer

/** Acha a rotina a que uma frase se refere: "escovei os dentes" → "Escovar os dentes". */
object HabitMatcher {
    private val STOPWORDS = setOf(
        "de", "do", "da", "dos", "das", "o", "a", "os", "as", "um", "uma", "meu", "minha", "meus", "minhas",
        "e", "no", "na", "em", "pra", "para", "ao", "com",
    )

    fun find(query: String, habits: List<Habit>): Habit? {
        val wanted = words(query)
        if (wanted.isEmpty()) return null
        return habits.map { it to score(wanted, words(it.name)) }
            .filter { it.second > 0 }
            .maxByOrNull { it.second }
            ?.first
    }

    fun sameName(a: String, b: String): Boolean = normalize(a).trim() == normalize(b).trim()

    /** Quantas palavras da frase batem com o nome, comparando o começo (escovei/escovar, suplemento/suplementos). */
    private fun score(wanted: List<String>, name: List<String>): Int =
        wanted.count { word -> name.any { it.take(STEM) == word.take(STEM) || it.startsWith(word) || word.startsWith(it) } }

    private fun words(text: String): List<String> =
        normalize(text).split(Regex("[^a-z0-9]+")).filter { it.length >= 3 && it !in STOPWORDS }

    private fun normalize(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()

    private const val STEM = 5
}
