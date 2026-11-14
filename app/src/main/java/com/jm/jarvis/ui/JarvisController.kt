package com.jm.jarvis.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jm.jarvis.AppContainer
import com.jm.jarvis.brain.Brain
import com.jm.jarvis.brain.ReplyEvent
import com.jm.jarvis.voice.Hearing
import com.jm.jarvis.voice.Sentences
import com.jm.jarvis.voice.SpeechInput
import com.jm.jarvis.voice.Speaker
import com.jm.jarvis.web.Source
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.launch

enum class Phase { IDLE, LISTENING, THINKING, SPEAKING }

data class ChatLine(val fromUser: Boolean, val text: String, val sources: List<Source> = emptyList())

/** Conduz uma conversa: ouvir, mandar para a IA, mostrar e falar a resposta. Vive enquanto a tela vive. */
class JarvisController(private val app: AppContainer, private val scope: CoroutineScope) {
    val lines = mutableStateListOf<ChatLine>()
    var phase by mutableStateOf(Phase.IDLE)
        private set
    var partial by mutableStateOf("")
        private set

    /** Volume da voz de 0 a 1, para animar o anel. */
    var level by mutableFloatStateOf(0f)
        private set

    private var job: Job? = null

    /** Toque no microfone: começa a ouvir, ou interrompe a escuta ou a fala. */
    fun onMic() {
        when (phase) {
            Phase.IDLE -> start { listenAndAnswer() }
            Phase.LISTENING, Phase.SPEAKING -> stop()
            Phase.THINKING -> Unit
        }
    }

    fun onText(text: String) {
        if (phase == Phase.IDLE && text.isNotBlank()) start { answer(text.trim()) }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private fun start(block: suspend () -> Unit) {
        job = scope.launch {
            try {
                block()
            } finally {
                phase = Phase.IDLE
                partial = ""
                level = 0f
            }
        }
    }

    private suspend fun listenAndAnswer() {
        phase = Phase.LISTENING
        val hints = app.habits.habits().map { it.name } + SpeechInput.COMMON_HINTS
        // Google pela internet primeiro (bem mais preciso); sem rede, o reconhecedor do aparelho.
        var heard = hear(onDevice = false, hints)
        if (heard is Hearing.Failed && SpeechInput.networkProblem(heard.code) && app.speechInput.onDeviceAvailable()) {
            heard = hear(onDevice = true, hints)
        }
        when (heard) {
            is Hearing.Final -> answer(heard.text)
            is Hearing.Failed -> lines += ChatLine(fromUser = false, text = SpeechInput.describe(heard.code))
            else -> Unit
        }
    }

    private suspend fun hear(onDevice: Boolean, hints: List<String>): Hearing? {
        var outcome: Hearing? = null
        app.speechInput.listen(onDevice, hints).collect { event ->
            when (event) {
                is Hearing.Partial -> partial = event.text
                is Hearing.Level -> level = ((event.rmsDb + 2f) / 12f).coerceIn(0f, 1f)
                is Hearing.Final, is Hearing.Failed -> outcome = event
            }
        }
        return outcome
    }

    /** Mostra a resposta enquanto ela chega e fala cada frase assim que ela fica completa. */
    private suspend fun answer(text: String) = coroutineScope {
        partial = ""
        level = 0f
        lines += ChatLine(fromUser = true, text = text)
        phase = Phase.THINKING

        val toSpeak = Channel<String>(Channel.UNLIMITED)
        val voice = launch { app.speaker.speakAll(toSpeak.consumeAsFlow(), Speaker.Purpose.REPLY) }
        var row = -1
        var spoken = 0
        var reply: Brain.Reply? = null

        fun show(line: ChatLine) {
            if (row < 0) {
                lines += line
                row = lines.lastIndex
            } else {
                lines[row] = line
            }
        }

        try {
            app.brain.reply(text).collect { event ->
                when (event) {
                    is ReplyEvent.Partial -> {
                        show(ChatLine(fromUser = false, text = event.text))
                        val (ready, end) = Sentences.complete(event.text, spoken)
                        spoken = end
                        ready.forEach { toSpeak.send(it) }
                        if (ready.isNotEmpty()) phase = Phase.SPEAKING
                    }
                    is ReplyEvent.Done -> reply = event.reply
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            reply = Brain.Reply("Desculpe, algo deu errado: ${e.message ?: e::class.simpleName}")
            spoken = 0
        }

        val final = reply ?: Brain.Reply(lines.getOrNull(row)?.text.orEmpty().ifEmpty { "Feito." })
        show(ChatLine(fromUser = false, text = final.text, sources = final.sources))
        Sentences.rest(final.text, spoken)?.let { toSpeak.send(it) }
        phase = Phase.SPEAKING
        toSpeak.close()
        voice.join()
    }
}
