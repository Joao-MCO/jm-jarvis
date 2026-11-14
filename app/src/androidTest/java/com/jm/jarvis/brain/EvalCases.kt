package com.jm.jarvis.brain

import com.google.ai.edge.litertlm.ToolCall
import java.text.Normalizer
import kotlin.math.abs

/** Uma frase do usuário e a ferramenta esperada; `tool = null` quer dizer que nenhuma deve ser chamada. */
data class EvalCase(val phrase: String, val tool: String?, val checks: List<ArgCheck> = emptyList()) {
    fun toolOk(call: ToolCall?): Boolean = call?.name == tool
    fun passes(call: ToolCall?): Boolean =
        toolOk(call) && (call == null || checks.all { it.matches(call.arguments[it.key]) })
}

sealed interface ArgCheck {
    val key: String
    fun matches(value: Any?): Boolean

    /** O texto do argumento contém algum dos trechos, ignorando acentos e maiúsculas. */
    data class Contains(override val key: String, val anyOf: List<String>) : ArgCheck {
        override fun matches(value: Any?): Boolean {
            val text = when (value) {
                null -> return false
                is Iterable<*> -> value.joinToString(" ")
                else -> value.toString()
            }
            return anyOf.any { normalize(text).contains(it) }
        }
    }

    data class Number(override val key: String, val expected: Double) : ArgCheck {
        override fun matches(value: Any?): Boolean {
            val actual = (value as? kotlin.Number)?.toDouble()
                ?: value?.toString()?.trim()?.replace(',', '.')?.toDoubleOrNull()
            return actual != null && abs(actual - expected) < 0.01
        }
    }
}

private fun has(key: String, vararg anyOf: String) = ArgCheck.Contains(key, anyOf.map(::normalize))
private fun num(key: String, expected: Int) = ArgCheck.Number(key, expected.toDouble())

fun normalize(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()

val EVAL_CASES = listOf(
    EvalCase("Bebi 500 ml de água agora", "registrar_habito", listOf(has("habito", "agua"), num("quantidade", 500))),
    EvalCase("Já escovei os dentes", "registrar_habito", listOf(has("habito", "dente"))),
    EvalCase("Tomei os suplementos", "registrar_habito", listOf(has("habito", "suplement"))),
    EvalCase("Fui na academia hoje", "registrar_habito", listOf(has("habito", "academia", "treino"))),
    EvalCase("Acabei de rezar o terço", "registrar_habito", listOf(has("habito", "reza", "terco", "oracao"))),
    EvalCase("Me lembra de beber água a cada 45 minutos", "criar_habito", listOf(has("nome", "agua"), num("intervalo_minutos", 45))),
    EvalCase("Quero criar o hábito de ir à academia 5 vezes por semana", "criar_habito", listOf(has("nome", "academia"), num("vezes_por_semana", 5))),
    EvalCase("Cria um hábito de tomar creatina às 8h", "criar_habito", listOf(has("nome", "creatina"), has("horarios", "8"))),
    EvalCase("Adia o lembrete da água por 20 minutos", "adiar_lembrete", listOf(has("habito", "agua"), num("minutos", 20))),
    EvalCase("Minha meta é ler 12 livros este ano", "criar_meta", listOf(has("titulo", "livro", "ler"))),
    EvalCase("Quero correr uma meia maratona até junho", "criar_meta", listOf(has("titulo", "maratona"))),
    EvalCase("Li 30 páginas do livro hoje", "registrar_progresso_meta", listOf(has("meta", "livro", "ler", "leitura"), num("valor", 30))),
    EvalCase("Como estão minhas metas?", "resumo_metas"),
    EvalCase("Me lembra de ligar para o dentista amanhã às 15h", "criar_lembrete", listOf(has("texto", "dentista"), has("quando", "amanha", "14"))),
    EvalCase("Daqui a 20 minutos me lembra de tirar o bolo do forno", "criar_lembrete", listOf(has("texto", "bolo"), has("quando", "20"))),
    EvalCase("Lembre que eu sou alérgico a camarão", "lembrar_fato", listOf(has("fato", "camarao"))),
    EvalCase("Anota que meu aniversário é 3 de março", "lembrar_fato", listOf(has("fato", "marco", "03"))),
    EvalCase("Qual é o nome da minha academia?", "buscar_memoria", listOf(has("consulta", "academia"))),
    EvalCase("Como foi meu dia?", "resumo_do_dia"),
    EvalCase("Quais hábitos ainda faltam hoje?", "listar_habitos"),
    EvalCase("Bom dia, Jarvis!", null),
    EvalCase("Me conta uma curiosidade sobre o espaço", null),
    EvalCase("Obrigado, era só isso", null),
)
