package com.jm.jarvis.brain

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.tool
import com.jm.jarvis.calendar.CalendarAnnouncer
import com.jm.jarvis.domain.HabitService
import com.jm.jarvis.domain.PT_BR
import com.jm.jarvis.web.Source
import com.jm.jarvis.web.WebLookup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** O que a resposta vai mostrando enquanto é gerada. */
sealed interface ReplyEvent {
    /** O texto gerado até agora, já limpo para leitura em voz alta. */
    data class Partial(val text: String) : ReplyEvent
    data class Done(val reply: Brain.Reply) : ReplyEvent
}

/**
 * A IA local (Gemma 4 E2B na CPU, que usa ~600 MB contra ~2 GB da GPU). O modelo é carregado uma
 * vez e a conversa continua entre as falas, então as instruções só são lidas na primeira.
 */
class Brain(
    private val context: Context,
    private val habits: HabitService,
    private val calendar: CalendarAnnouncer,
    private val web: WebLookup,
) {
    /** A resposta falada e de onde vieram as informações, se a IA consultou a internet. */
    data class Reply(val text: String, val sources: List<Source> = emptyList())

    private val mutex = Mutex()
    private var engine: Engine? = null
    private var conversation: Conversation? = null
    private var conversationStart: LocalDateTime? = null
    private var tools: JarvisTools? = null

    fun installed(): Boolean = LocalModel.file(context).exists()

    /** Carrega o modelo antes da primeira pergunta, para a resposta sair mais rápido. */
    suspend fun warmUp() {
        if (!installed()) return
        mutex.withLock {
            withContext(Dispatchers.Default) {
                runCatching { conversation() }.onFailure { Log.w(TAG, "falha ao carregar o modelo", it) }
            }
        }
    }

    /** Responde ao que o usuário disse, executando as ferramentas que a IA pedir, frase a frase. */
    fun reply(text: String): Flow<ReplyEvent> = flow {
        mutex.withLock {
            if (!installed()) {
                emit(ReplyEvent.Done(Reply("Meu modelo de IA ainda não está instalado neste celular.")))
                return@withLock
            }
            var shown = ""
            val sources = try {
                stream(text) { shown = it; emit(ReplyEvent.Partial(it)) }
            } catch (e: Exception) {
                // Conversa cheia ou em mau estado. Se nada saiu ainda, recomeça uma vez do zero.
                if (shown.isNotEmpty()) throw e
                Log.w(TAG, "conversa reiniciada", e)
                closeConversation()
                stream(text) { shown = it; emit(ReplyEvent.Partial(it)) }
            }
            emit(ReplyEvent.Done(Reply(shown.ifEmpty { "Feito." }, sources)))
        }
    }.flowOn(Dispatchers.Default)

    /** Libera os ~600 MB do modelo quando o app sai da tela. */
    suspend fun release() = mutex.withLock {
        closeConversation()
        engine?.close()
        engine = null
    }

    private suspend fun stream(text: String, onText: suspend (String) -> Unit): List<Source> {
        val current = conversation()
        val toolSet = checkNotNull(tools)
        toolSet.sources.clear()
        val raw = StringBuilder()
        current.sendMessageAsync(text).collect { chunk ->
            raw.append(chunk.toString())
            val cleaned = clean(raw.toString())
            if (cleaned.isNotEmpty()) onText(cleaned)
        }
        return toolSet.sources.distinctBy { it.url }
    }

    private suspend fun conversation(): Conversation {
        val now = LocalDateTime.now()
        val started = conversationStart
        // A data e a hora vão nas instruções; a conversa é renovada quando elas ficam velhas.
        if (started != null && (Duration.between(started, now) > MAX_AGE || started.toLocalDate() != now.toLocalDate())) {
            closeConversation()
        }
        conversation?.let { return it }
        val loaded = engine ?: Engine(
            EngineConfig(
                modelPath = LocalModel.file(context).absolutePath,
                backend = Backend.CPU(),
                cacheDir = context.cacheDir.path,
            ),
        ).also {
            it.initialize()
            engine = it
        }
        val routines = habits.habits().filter { it.active }.joinToString(", ") { it.name }
        val toolSet = JarvisTools(habits, calendar, web).also { tools = it }
        return loaded.createConversation(
            ConversationConfig(
                systemInstruction = Contents.of(persona(routines, now)),
                tools = listOf(tool(toolSet)),
                // Escolha gulosa: foi assim que o teste da Fase 0 mediu 96% de acerto nas ferramentas.
                samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 1.0),
            ),
        ).also {
            conversation = it
            conversationStart = now
        }
    }

    private fun closeConversation() {
        conversation?.close()
        conversation = null
        conversationStart = null
        tools = null
    }

    private fun persona(routines: String, now: LocalDateTime) = """
        Você é JARVIS, o assistente pessoal do usuário: educado, direto e com um toque de humor, como um mordomo britânico. Trate o usuário por "você", sem "senhor" nem "senhora".
        Responda sempre em português do Brasil, em no máximo duas frases curtas, sem emojis, listas, links ou formatação, porque sua resposta será falada em voz alta. Não diga a data nem a hora, a menos que perguntem.
        Use as ferramentas para registrar, criar, adiar ou consultar rotinas e para consultar a agenda. Nunca invente dados do usuário.
        Para fatos sobre pessoas, lugares e conceitos, consulte a Wikipedia. Para notícias, preços, placares, resultados ou qualquer coisa recente, pesquise na internet. Para tempo e temperatura, use o clima. Não chute: se não souber, pesquise.
        Depois de usar uma ferramenta, responda com base no resultado dela.
        Agora é ${now.format(NOW)}.
        Rotinas do usuário: $routines.
    """.trimIndent()

    private companion object {
        const val TAG = "JarvisBrain"
        val MAX_AGE: Duration = Duration.ofMinutes(30)
        val NOW: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE, d 'de' MMMM 'de' yyyy, HH:mm", PT_BR)

        /** Tira o que não deve ser lido em voz alta: data repetida no começo, links, formatação e espaços. */
        fun clean(text: String): String = text
            .replace(Regex("^\\s*\\[[^\\]]*\\]\\s*"), "")
            .replace(Regex("https?://\\S+"), "")
            .replace(Regex("[*_#`]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
