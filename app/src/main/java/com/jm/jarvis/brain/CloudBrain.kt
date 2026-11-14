package com.jm.jarvis.brain

import com.jm.jarvis.data.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Duration
import java.time.LocalDateTime

/**
 * IA online por uma API no formato OpenAI (`/chat/completions`), que o Gemini, o Groq e o OpenRouter
 * aceitam. Roda as mesmas ferramentas ([JarvisTools]) do modelo local; só muda quem decide qual chamar.
 * Qualquer falha lança exceção e o [Brain] cai para o Gemma local.
 */
class CloudBrain(private val settings: SettingsStore) {
    private val history = mutableListOf<JSONObject>()
    private var started: LocalDateTime? = null

    /** Responde à frase, executando as ferramentas pedidas. A conversa continua entre as falas. */
    suspend fun reply(text: String, persona: String, tools: JarvisTools): String = withContext(Dispatchers.IO) {
        val config = settings.current()
        require(config.cloudKey.isNotBlank()) { "sem chave da IA online" }
        val now = LocalDateTime.now()
        val begun = started
        if (begun != null && (Duration.between(begun, now) > MAX_AGE || begun.toLocalDate() != now.toLocalDate() || history.size > MAX_MESSAGES)) {
            history.clear()
            started = null
        }
        val mark = history.size
        val system = JSONObject().put("role", "system").put("content", persona)
        history += JSONObject().put("role", "user").put("content", text)
        try {
            repeat(MAX_ROUNDS) {
                val message = complete(config.cloudUrl, config.cloudKey, config.cloudModel, system)
                history += message
                val calls = message.optJSONArray("tool_calls")
                if (calls == null || calls.length() == 0) {
                    started = started ?: now
                    return@withContext message.optString("content").trim()
                }
                for (i in 0 until calls.length()) {
                    val call = calls.getJSONObject(i)
                    val function = call.getJSONObject("function")
                    val result = withContext(Dispatchers.Default) {
                        runCatching { CloudTools.call(tools, function.getString("name"), parseArguments(function.optString("arguments"))) }
                            .getOrElse { "Falhou: ${it.message}" }
                    }
                    history += JSONObject().put("role", "tool").put("tool_call_id", call.optString("id")).put("content", result)
                }
            }
            error("a IA online não terminou de usar as ferramentas")
        } catch (e: Exception) {
            // Uma troca pela metade deixaria o histórico inválido na próxima fala.
            while (history.size > mark) history.removeAt(history.lastIndex)
            throw e
        }
    }

    private fun complete(url: String, key: String, model: String, system: JSONObject): JSONObject {
        val messages = JSONArray().put(system).apply { history.forEach { put(it) } }
        val body = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("tools", CloudTools.declarations)
            .put("temperature", 0.3)
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 8_000
            connection.readTimeout = 30_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $key")
            connection.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val reply = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            check(code in 200..299) { "HTTP $code ${reply.take(200)}" }
            return JSONObject(reply).getJSONArray("choices").getJSONObject(0).getJSONObject("message")
        } finally {
            connection.disconnect()
        }
    }

    private fun parseArguments(raw: String): JSONObject = if (raw.isBlank()) JSONObject() else JSONObject(raw)

    private companion object {
        const val MAX_ROUNDS = 5
        const val MAX_MESSAGES = 40
        val MAX_AGE: Duration = Duration.ofMinutes(30)
    }
}

/** As ferramentas do [JarvisTools] no formato que a API online espera, e a chamada delas pelo nome. */
object CloudTools {
    private fun prop(type: String, description: String? = null, items: String? = null): JSONObject =
        JSONObject().put("type", type).apply {
            description?.let { put("description", it) }
            items?.let { put("items", JSONObject().put("type", it)) }
        }

    private fun fn(name: String, description: String, required: List<String> = emptyList(), vararg props: Pair<String, JSONObject>): JSONObject =
        JSONObject()
            .put("type", "function")
            .put(
                "function",
                JSONObject()
                    .put("name", name)
                    .put("description", description)
                    .put(
                        "parameters",
                        JSONObject()
                            .put("type", "object")
                            .put("properties", JSONObject().apply { props.forEach { (key, value) -> put(key, value) } })
                            .put("required", JSONArray(required)),
                    ),
            )

    private val S = "string"

    val declarations: JSONArray = JSONArray()
        .put(fn("registrar_habito", "Registra que o usuário acabou de fazer uma rotina (água, dentes, academia, suplementos etc.).", listOf("habito"),
            "habito" to prop(S, "Nome da rotina"), "quantidade" to prop("number", "Quantidade, se o usuário disser: ml de água, minutos etc.")))
        .put(fn("listar_habitos", "Lista as rotinas de hoje e quais ainda faltam."))
        .put(fn("criar_habito", "Cria uma rotina nova, com só um tipo de frequência.", listOf("nome"),
            "nome" to prop(S), "intervalo_minutos" to prop("integer", "Repetir a cada N minutos"),
            "horarios" to prop("array", "Horários HH:mm", S), "vezes_por_semana" to prop("integer")))
        .put(fn("adiar_lembrete", "Adia o próximo aviso de uma rotina.", listOf("habito", "minutos"),
            "habito" to prop(S), "minutos" to prop("integer")))
        .put(fn("resumo_do_dia", "Resumo do dia: clima, agenda, rotinas pendentes e lembretes."))
        .put(fn("eventos_da_agenda", "Eventos da agenda do usuário.", emptyList(), "dias" to prop("integer", "De 1 (hoje) a 7")))
        .put(fn("pesquisar_internet", "Pesquisa na internet: notícias, preços, placares e informação recente.", listOf("consulta"),
            "consulta" to prop(S)))
        .put(fn("consultar_wikipedia", "Wikipedia: pessoas, lugares, conceitos e história.", listOf("termo"), "termo" to prop(S)))
        .put(fn("clima", "Clima e previsão do tempo dos próximos dias.", emptyList(), "dias" to prop("integer", "De 1 a 7, contando hoje")))
        .put(fn("relogio", "Despertador ou timer no app Relógio, ou mostrar os despertadores.", listOf("acao"),
            "acao" to prop(S, "despertador, timer ou ver_despertadores"), "horario" to prop(S, "HH:mm"),
            "minutos" to prop("integer"), "segundos" to prop("integer"),
            "dias" to prop("array", "Dias de repetição do despertador", S), "rotulo" to prop(S)))
        .put(fn("cronometro", "Cronômetro do JARVIS.", listOf("acao"), "acao" to prop(S, "iniciar, pausar, continuar, zerar ou consultar")))
        .put(fn("lembrete", "Lembrete avulso com hora: criar, listar ou cancelar.", listOf("acao"),
            "acao" to prop(S, "criar, listar ou cancelar"), "texto" to prop(S, "O que lembrar"),
            "quando" to prop(S, "Quando, como o usuário disse: amanhã às 15h, daqui a 20 minutos")))
        .put(fn("memoria", "Guarda, esquece ou lista fatos sobre o usuário.", listOf("acao"),
            "acao" to prop(S, "guardar, esquecer ou listar"), "fato" to prop(S, "O fato, em uma frase")))
        .put(fn("celular", "Controla o celular: lanterna, volume, abrir app, ligar, WhatsApp, rota ou música.", listOf("acao"),
            "acao" to prop(S, "lanterna, volume, abrir, ligar, whatsapp, rota ou musica"),
            "alvo" to prop(S, "Contato, app, destino ou música"),
            "valor" to prop(S, "ligar/desligar; 0 a 100, mais ou menos; mensagem do WhatsApp")))

    fun call(tools: JarvisTools, name: String, args: JSONObject): String {
        fun str(key: String): String? = args.optString(key).takeIf { args.has(key) && !args.isNull(key) && it.isNotBlank() }
        fun int(key: String): Int? = if (args.has(key) && !args.isNull(key)) args.optInt(key) else null
        fun list(key: String): List<String>? = args.optJSONArray(key)?.let { array -> (0 until array.length()).map { array.getString(it) } }
        return when (name) {
            "registrar_habito" -> tools.registrarHabito(str("habito").orEmpty(), if (args.has("quantidade") && !args.isNull("quantidade")) args.optDouble("quantidade") else null)
            "listar_habitos" -> tools.listarHabitos()
            "criar_habito" -> tools.criarHabito(str("nome").orEmpty(), int("intervalo_minutos"), list("horarios"), int("vezes_por_semana"))
            "adiar_lembrete" -> tools.adiarLembrete(str("habito").orEmpty(), int("minutos") ?: 0)
            "resumo_do_dia" -> tools.resumoDoDia()
            "eventos_da_agenda" -> tools.eventosDaAgenda(int("dias") ?: 1)
            "pesquisar_internet" -> tools.pesquisarInternet(str("consulta").orEmpty())
            "consultar_wikipedia" -> tools.consultarWikipedia(str("termo").orEmpty())
            "clima" -> tools.clima(int("dias") ?: 2)
            "relogio" -> tools.relogio(str("acao").orEmpty(), str("horario"), int("minutos"), int("segundos"), list("dias"), str("rotulo"))
            "cronometro" -> tools.cronometro(str("acao").orEmpty())
            "lembrete" -> tools.lembrete(str("acao").orEmpty(), str("texto"), str("quando"))
            "memoria" -> tools.memoria(str("acao").orEmpty(), str("fato"))
            "celular" -> tools.celular(str("acao").orEmpty(), str("alvo"), str("valor"))
            else -> "Ferramenta desconhecida: $name."
        }
    }
}
