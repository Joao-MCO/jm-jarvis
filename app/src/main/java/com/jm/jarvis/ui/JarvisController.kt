package com.jm.jarvis.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jm.jarvis.AppContainer
import com.jm.jarvis.brain.Brain
import com.jm.jarvis.voice.Hearing
import com.jm.jarvis.web.Source
import com.jm.jarvis.voice.SpeechInput
import com.jm.jarvis.voice.Speaker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
        var heard = hear(onDevice = app.speechInput.onDeviceAvailable())
        if (heard is Hearing.Failed && SpeechInput.languageMissing(heard.code)) heard = hear(onDevice = false)
        when (heard) {
            is Hearing.Final -> answer(heard.text)
            is Hearing.Failed -> lines += ChatLine(fromUser = false, text = SpeechInput.describe(heard.code))
            else -> Unit
        }
    }

    private suspend fun hear(onDevice: Boolean): Hearing? {
        var outcome: Hearing? = null
        app.speechInput.listen(onDevice).collect { event ->
            when (event) {
                is Hearing.Partial -> partial = event.text
                is Hearing.Level -> level = ((event.rmsDb + 2f) / 12f).coerceIn(0f, 1f)
                is Hearing.Final, is Hearing.Failed -> outcome = event
            }
        }
        return outcome
    }

    private suspend fun answer(text: String) {
        partial = ""
        level = 0f
        lines += ChatLine(fromUser = true, text = text)
        phase = Phase.THINKING
        val reply = try {
            app.brain.reply(text)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Brain.Reply("Desculpe, algo deu errado: ${e.message ?: e::class.simpleName}")
        }
        lines += ChatLine(fromUser = false, text = reply.text, sources = reply.sources)
        phase = Phase.SPEAKING
        app.speaker.speak(reply.text, Speaker.Purpose.REPLY, timeoutMs = 30_000)
    }
}
