package com.jm.jarvis.domain

import com.jm.jarvis.data.MemoryDao
import com.jm.jarvis.data.MemoryFact
import kotlinx.coroutines.flow.Flow

/** O que o JARVIS sabe sobre o usuário: fatos curtos que entram nas instruções de cada conversa. */
class MemoryService(private val dao: MemoryDao) {

    fun observe(): Flow<List<MemoryFact>> = dao.observeFacts()

    /** Os fatos mais recentes, no limite do que cabe nas instruções sem atrasar a resposta. */
    suspend fun forPrompt(): List<String> = dao.facts().take(MAX_IN_PROMPT).map { it.text }

    suspend fun remember(fact: String): String {
        val text = fact.trim().trimEnd('.').replaceFirstChar(Char::uppercase)
        if (text.isEmpty()) return "Diga o que devo guardar."
        if (dao.facts().any { TextMatch.same(it.text, text) }) return "Eu já sabia disso: $text."
        dao.insert(MemoryFact(text = text, createdAt = System.currentTimeMillis()))
        return "Guardado: $text."
    }

    suspend fun forget(query: String): String {
        val match = TextMatch.best(query, dao.facts()) { it.text } ?: return "Não achei nada guardado sobre \"$query\"."
        dao.delete(match.id)
        return "Esqueci: ${match.text}."
    }

    suspend fun forget(id: Long) = dao.delete(id)

    suspend fun describe(): String {
        val facts = dao.facts()
        return if (facts.isEmpty()) "Ainda não guardei nada sobre o usuário." else facts.joinToString("; ", prefix = "Sei que: ", postfix = ".") { it.text }
    }

    private companion object {
        const val MAX_IN_PROMPT = 30
    }
}
