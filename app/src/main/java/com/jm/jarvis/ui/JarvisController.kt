package com.jm.jarvis.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jm.jarvis.AppContainer
import com.jm.jarvis.voice.Hearing
import com.jm.jarvis.voice.SpeechInput
import com.jm.jarvis.web.Source
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
        val heard = app.dialogue.hear(
            onPartial = { partial = it },
            onLevel = { level = ((it + 2f) / 12f).coerceIn(0f, 1f) },
        )
        when (heard) {
            is Hearing.Final -> answer(heard.text)
            is Hearing.Failed -> lines += ChatLine(fromUser = false, text = SpeechInput.describe(heard.code))
            else -> Unit
        }
    }

    /** Mostra a resposta enquanto ela chega e fala cada frase assim que ela fica completa. */
    private suspend fun answer(text: String) {
        partial = ""
        level = 0f
        lines += ChatLine(fromUser = true, text = text)
        phase = Phase.THINKING

        var row = -1
        fun show(line: ChatLine) {
            if (row < 0) {
                lines += line
                row = lines.lastIndex
            } else {
                lines[row] = line
            }
        }
        app.dialogue.reply(
            text,
            onPartial = { show(ChatLine(fromUser = false, text = it)) },
            onSpeaking = { phase = Phase.SPEAKING },
            onFinal = { show(ChatLine(fromUser = false, text = it.text, sources = it.sources)) },
        )
    }
}
