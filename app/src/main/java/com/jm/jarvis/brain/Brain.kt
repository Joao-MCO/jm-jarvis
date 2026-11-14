package com.jm.jarvis.brain

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.tool
import com.jm.jarvis.data.SettingsStore
import com.jm.jarvis.domain.HabitService
import com.jm.jarvis.domain.MemoryService
import com.jm.jarvis.domain.PT_BR
import com.jm.jarvis.web.Source
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
    private val memory: MemoryService,
    private val settings: SettingsStore,
    private val cloud: CloudBrain,
    /** Ferramentas novas para cada conversa (guardam as fontes consultadas nela). */
    private val newTools: () -> JarvisTools,
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
            // Comandos claros não esperam a IA: são entendidos e executados na hora.
            Shortcut.match(text, habits.habits())?.let { shortcut ->
                emit(ReplyEvent.Done(Reply(runCatching { shortcut.run(newTools()) }.getOrElse { "Não consegui: ${it.message}" })))
                return@withLock
            }
            // Com internet e chave, a IA online responde; se falhar, cai para o Gemma local.
            cloudReply(text)?.let { emit(ReplyEvent.Done(it)); return@withLock }
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

    private suspend fun cloudReply(text: String): Reply? {
        val config = settings.current()
        if (!config.cloudEnabled || config.cloudKey.isBlank() || !online()) return null
        return try {
            val toolSet = newTools()
            val persona = persona(habits.habits().filter { it.active }.joinToString(", ") { it.name }, memory.forPrompt(), LocalDateTime.now())
            val answer = clean(cloud.reply(text, persona, toolSet))
            val checked = if (toolSet.executed == 0 && CLAIM.containsMatchIn(answer)) NOT_DONE else answer
            Reply(checked.ifEmpty { "Feito." }, toolSet.sources.distinctBy { it.url })
        } catch (e: Exception) {
            Log.w(TAG, "IA online falhou; usando o modelo local", e)
            null
        }
    }

    private fun online(): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

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
            // "Registrado" sem ter chamado nenhuma ferramenta é invenção do modelo: não deixa sair.
            if (cleaned.isNotEmpty() && !(toolSet.executed == 0 && CLAIM.containsMatchIn(cleaned))) onText(cleaned)
        }
        if (toolSet.executed == 0 && CLAIM.containsMatchIn(clean(raw.toString()))) onText(NOT_DONE)
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
        val facts = memory.forPrompt()
        val toolSet = newTools().also { tools = it }
        return loaded.createConversation(
            ConversationConfig(
                systemInstruction = Contents.of(persona(routines, facts, now)),
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

    /** As instruções da conversa. Público para o teste de acerto usar exatamente as mesmas. */
    fun persona(routines: String, facts: List<String>, now: LocalDateTime) = """
        Você é JARVIS, o assistente pessoal do usuário: educado, direto, com um toque de humor de mordomo britânico. Trate por "você".
        Responda em português do Brasil, em no máximo duas frases curtas, sem emojis, listas ou links, pois será falado em voz alta. Não diga data nem hora, a menos que perguntem.
        Para fazer algo (registrar, criar, adiar, lembrar, guardar, consultar), chame a ferramenta; nunca diga que fez sem chamá-la. Nunca invente dados do usuário.
        Pessoas, lugares e conceitos: Wikipedia. Notícias, preços, placares e fatos recentes: internet. Tempo: clima.
        Agora é ${now.format(NOW)}.
        Rotinas do usuário: $routines.
    """.trimIndent() + if (facts.isEmpty()) "" else "\nO que você sabe sobre o usuário: ${facts.joinToString("; ")}."

    private companion object {
        const val TAG = "JarvisBrain"
        /** Frases que afirmam uma ação feita; só valem se alguma ferramenta rodou. */
        val CLAIM = Regex("\\b(registrad[oa]|registrei|pronto|feito|criei|criad[oa]|anotei|anotad[oa]|guardei|guardad[oa]|adiei|adiad[oa]|iniciei|iniciad[oa]|configurei|liguei|desliguei|abri|lembrete criado)\\b", RegexOption.IGNORE_CASE)
        const val NOT_DONE = "Não consegui fazer isso. Pode pedir de outro jeito?"
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
