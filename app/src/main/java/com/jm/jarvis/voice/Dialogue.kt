package com.jm.jarvis.voice

import com.jm.jarvis.brain.Brain
import com.jm.jarvis.brain.ReplyEvent
import com.jm.jarvis.domain.HabitService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.launch

/**
 * Uma volta de conversa sem tela: ouvir, mandar para a IA e falar a resposta frase a frase.
 * A tela do app e o serviço "Ei, Jarvis" usam a mesma, cada um mostrando o que quiser pelos callbacks.
 */
class Dialogue(
    private val brain: Brain,
    private val speaker: Speaker,
    private val speechInput: SpeechInput,
    private val habits: HabitService,
) {
    /** Ouve uma fala: Google pela internet primeiro (bem mais preciso); sem rede, o reconhecedor do aparelho. */
    suspend fun hear(onPartial: (String) -> Unit = {}, onLevel: (Float) -> Unit = {}): Hearing? {
        val hints = habits.habits().map { it.name } + SpeechInput.COMMON_HINTS
        var heard = listen(onDevice = false, hints, onPartial, onLevel)
        if (heard is Hearing.Failed && SpeechInput.networkProblem(heard.code) && speechInput.onDeviceAvailable()) {
            heard = listen(onDevice = true, hints, onPartial, onLevel)
        }
        return heard
    }

    private suspend fun listen(onDevice: Boolean, hints: List<String>, onPartial: (String) -> Unit, onLevel: (Float) -> Unit): Hearing? {
        var outcome: Hearing? = null
        speechInput.listen(onDevice, hints).collect { event ->
            when (event) {
                is Hearing.Partial -> onPartial(event.text)
                is Hearing.Level -> onLevel(event.rmsDb)
                is Hearing.Final, is Hearing.Failed -> outcome = event
            }
        }
        return outcome
    }

    /**
     * Responde a [text]: [onPartial] recebe o texto conforme chega, [onSpeaking] avisa quando a primeira
     * frase começa a ser falada e [onFinal] recebe a resposta completa antes de a última frase terminar.
     */
    suspend fun reply(
        text: String,
        onPartial: (String) -> Unit = {},
        onSpeaking: () -> Unit = {},
        onFinal: (Brain.Reply) -> Unit = {},
    ): Brain.Reply = coroutineScope {
        val toSpeak = Channel<String>(Channel.UNLIMITED)
        val voice = launch { speaker.speakAll(toSpeak.consumeAsFlow(), Speaker.Purpose.REPLY) }
        var shown = ""
        var spoken = 0
        var reply: Brain.Reply? = null
        try {
            brain.reply(text).collect { event ->
                when (event) {
                    is ReplyEvent.Partial -> {
                        shown = event.text
                        onPartial(event.text)
                        val (ready, end) = Sentences.complete(event.text, spoken)
                        spoken = end
                        ready.forEach { toSpeak.send(it) }
                        if (ready.isNotEmpty()) onSpeaking()
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
        val final = reply ?: Brain.Reply(shown.ifEmpty { "Feito." })
        onFinal(final)
        Sentences.rest(final.text, spoken)?.let { toSpeak.send(it) }
        onSpeaking()
        toSpeak.close()
        voice.join()
        final
    }
}
