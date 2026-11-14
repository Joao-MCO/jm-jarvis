package com.jm.jarvis.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** O que o ouvido do JARVIS percebe enquanto você fala. */
sealed interface Hearing {
    data class Partial(val text: String) : Hearing
    data class Level(val rmsDb: Float) : Hearing
    data class Final(val text: String) : Hearing
    data class Failed(val code: Int) : Hearing
}

/** Reconhecimento de fala do Android em português, de preferência o que roda no próprio aparelho. */
class SpeechInput(private val context: Context) {

    fun onDeviceAvailable(): Boolean = SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    /** Escuta uma frase e termina com [Hearing.Final] ou [Hearing.Failed]. Colete na thread principal. */
    fun listen(onDevice: Boolean): Flow<Hearing> = callbackFlow {
        val recognizer = if (onDevice) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            SpeechRecognizer.createSpeechRecognizer(context)
        }
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) {
                trySend(Hearing.Level(rmsdB))
            }

            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onError(error: Int) {
                trySend(Hearing.Failed(error))
                close()
            }

            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                trySend(if (text.isBlank()) Hearing.Failed(SpeechRecognizer.ERROR_NO_MATCH) else Hearing.Final(text))
                close()
            }

            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?.let { trySend(Hearing.Partial(it)) }
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        recognizer.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true),
        )
        awaitClose { recognizer.destroy() }
    }

    companion object {
        /** O reconhecedor do aparelho existe, mas sem o pacote de português: vale tentar o outro. */
        fun languageMissing(code: Int): Boolean =
            code == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED || code == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE

        fun describe(code: Int): String = when (code) {
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Não ouvi nada. Toque no microfone e fale."
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Preciso da permissão do microfone."
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER ->
                "O reconhecimento de fala precisa de internet neste celular. Você pode digitar abaixo."
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "O microfone está ocupado. Tente de novo."
            else -> "Não consegui ouvir (erro $code). Tente de novo ou digite abaixo."
        }
    }
}
