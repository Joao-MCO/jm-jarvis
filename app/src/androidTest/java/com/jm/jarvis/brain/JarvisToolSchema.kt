package com.jm.jarvis.brain

import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet

/**
 * Ferramentas planejadas para a Fase 2, usadas aqui só para medir se o modelo escolhe a certa.
 * A avaliação desliga a execução automática, então os corpos nunca rodam.
 * O LiteRT-LM expõe métodos e parâmetros ao modelo em snake_case: `registrarHabito` vira
 * `registrar_habito` e `intervaloMinutos` vira `intervalo_minutos`.
 */
@Suppress("unused", "UNUSED_PARAMETER")
class JarvisToolSchema : ToolSet {

    @Tool(description = "Registra que o usuário acabou de fazer um hábito: beber água, escovar os dentes, tomar suplementos, rezar, ir à academia etc.")
    fun registrarHabito(
        @ToolParam(description = "Nome do hábito, ex.: água, escovar os dentes, academia") habito: String,
        @ToolParam(description = "Quantidade, se o usuário disser, ex.: 500 para 500 ml") quantidade: Double? = null,
    ): String = NOT_EXECUTED

    @Tool(description = "Lista os hábitos de hoje e quais ainda faltam.")
    fun listarHabitos(): String = NOT_EXECUTED

    @Tool(description = "Cria um hábito novo para acompanhar. Use só um dos campos de frequência.")
    fun criarHabito(
        @ToolParam(description = "Nome do hábito, ex.: beber água") nome: String,
        @ToolParam(description = "Repetir a cada N minutos, ex.: 45") intervaloMinutos: Int? = null,
        @ToolParam(description = "Horários fixos no formato HH:mm, ex.: [\"08:00\", \"20:00\"]") horarios: List<String>? = null,
        @ToolParam(description = "Quantas vezes por semana, ex.: 5") vezesPorSemana: Int? = null,
    ): String = NOT_EXECUTED

    @Tool(description = "Adia o próximo lembrete de um hábito.")
    fun adiarLembrete(
        @ToolParam(description = "Nome do hábito, ex.: água") habito: String,
        @ToolParam(description = "Quantos minutos adiar") minutos: Int,
    ): String = NOT_EXECUTED

    @Tool(description = "Cria uma meta ou desafio de longo prazo.")
    fun criarMeta(
        @ToolParam(description = "Descrição curta da meta") titulo: String,
        @ToolParam(description = "Prazo, exatamente como o usuário disse, se disser") prazo: String? = null,
    ): String = NOT_EXECUTED

    @Tool(description = "Registra um avanço numa meta existente.")
    fun registrarProgressoMeta(
        @ToolParam(description = "Nome da meta, ex.: ler livros") meta: String,
        @ToolParam(description = "Quanto avançou, ex.: 30 para 30 páginas") valor: Double,
    ): String = NOT_EXECUTED

    @Tool(description = "Mostra como estão as metas do usuário.")
    fun resumoMetas(): String = NOT_EXECUTED

    @Tool(description = "Cria um lembrete avulso para um momento específico.")
    fun criarLembrete(
        @ToolParam(description = "O que lembrar") texto: String,
        @ToolParam(description = "Quando, exatamente como o usuário disse, ex.: amanhã às 15h, daqui a 20 minutos") quando: String,
    ): String = NOT_EXECUTED

    @Tool(description = "Guarda um fato ou preferência do usuário para lembrar no futuro.")
    fun lembrarFato(
        @ToolParam(description = "O fato, em uma frase") fato: String,
    ): String = NOT_EXECUTED

    @Tool(description = "Procura algo que o usuário pediu para guardar antes.")
    fun buscarMemoria(
        @ToolParam(description = "O que procurar") consulta: String,
    ): String = NOT_EXECUTED

    @Tool(description = "Faz um resumo do dia do usuário: hábitos feitos, metas e lembretes.")
    fun resumoDoDia(): String = NOT_EXECUTED

    private companion object {
        const val NOT_EXECUTED = "não executado na avaliação"
    }
}
