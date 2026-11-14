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

/**
 * Frases do dia a dia contra as ferramentas reais do [JarvisTools]. O LiteRT-LM expõe métodos e
 * parâmetros em snake_case: `registrarHabito` vira `registrar_habito`.
 */
val EVAL_CASES = listOf(
    // Rotinas
    EvalCase("Bebi 500 ml de água agora", "registrar_habito", listOf(has("habito", "agua"), num("quantidade", 500))),
    EvalCase("Já escovei os dentes", "registrar_habito", listOf(has("habito", "dente", "escov"))),
    EvalCase("Tomei os suplementos", "registrar_habito", listOf(has("habito", "suplement"))),
    EvalCase("Fui na academia hoje", "registrar_habito", listOf(has("habito", "academia", "treino"))),
    EvalCase("Quero criar o hábito de meditar 5 vezes por semana", "criar_habito", listOf(has("nome", "medit"), num("vezes_por_semana", 5))),
    EvalCase("Adia a água por 20 minutos", "adiar_lembrete", listOf(has("habito", "agua"), num("minutos", 20))),
    EvalCase("Quais rotinas ainda faltam hoje?", "listar_habitos"),
    EvalCase("Como está o meu dia?", "resumo_do_dia"),
    // Lembretes e memória
    EvalCase("Me lembra de ligar para o dentista amanhã às 15h", "lembrete", listOf(has("acao", "cri"), has("texto", "dentista"), has("quando", "amanha"))),
    EvalCase("Daqui a 20 minutos me lembra de tirar o bolo do forno", "lembrete", listOf(has("texto", "bolo"), has("quando", "20"))),
    EvalCase("Quais lembretes eu tenho?", "lembrete", listOf(has("acao", "list", "ver"))),
    EvalCase("Lembra que eu sou alérgico a camarão", "memoria", listOf(has("acao", "guard"), has("fato", "camarao"))),
    // Relógio
    EvalCase("Me acorda amanhã às 6h30", "relogio", listOf(has("acao", "desperta", "alarme"), has("horario", "6"))),
    EvalCase("Coloca um despertador às 7h de segunda a sexta", "relogio", listOf(has("acao", "desperta", "alarme"), has("horario", "7"))),
    EvalCase("Timer de 10 minutos", "relogio", listOf(has("acao", "timer"), num("minutos", 10))),
    EvalCase("Inicia o cronômetro", "cronometro", listOf(has("acao", "inici", "comec"))),
    EvalCase("Pausa o cronômetro", "cronometro", listOf(has("acao", "paus", "par"))),
    // Celular
    EvalCase("Liga a lanterna", "celular", listOf(has("acao", "lanterna"))),
    EvalCase("Abaixa o volume", "celular", listOf(has("acao", "volume"))),
    EvalCase("Abre o Spotify", "celular", listOf(has("acao", "abr"), has("alvo", "spotify"))),
    EvalCase("Liga para a Maria", "celular", listOf(has("acao", "lig"), has("alvo", "maria"))),
    EvalCase("Manda um WhatsApp para o Pedro dizendo que vou me atrasar", "celular", listOf(has("acao", "whats"), has("alvo", "pedro"))),
    EvalCase("Traça uma rota para o aeroporto", "celular", listOf(has("acao", "rota"), has("alvo", "aeroporto"))),
    EvalCase("Toca Legião Urbana", "celular", listOf(has("acao", "music", "toca"), has("alvo", "legiao"))),
    // Informação
    EvalCase("Vai chover amanhã?", "clima"),
    EvalCase("Quem foi Santos Dumont?", "consultar_wikipedia", listOf(has("termo", "dumont"))),
    EvalCase("Quem ganhou o último jogo do Flamengo?", "pesquisar_internet", listOf(has("consulta", "flamengo"))),
    EvalCase("O que eu tenho na agenda hoje?", "eventos_da_agenda"),
    // Conversa sem ferramenta
    EvalCase("Bom dia, Jarvis!", null),
    EvalCase("Obrigado, era só isso", null),
)
