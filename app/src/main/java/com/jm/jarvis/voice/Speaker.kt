package com.jm.jarvis.voice

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

/** Voz do JARVIS: o mecanismo de fala do próprio Android, em português, sem internet. */
class Speaker(private val context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val notificationManager = context.getSystemService(NotificationManager::class.java)

    /** Por que o JARVIS está falando: define o volume que vale. */
    enum class Purpose(val usage: Int) {
        /** Aviso de rotina ou agenda: segue o volume de notificação e cala com o celular no vibrar. */
        REMINDER(AudioAttributes.USAGE_NOTIFICATION_EVENT),

        /** Resposta a quem acabou de falar com ele: segue o volume de mídia, como um assistente. */
        REPLY(AudioAttributes.USAGE_ASSISTANT),
    }

    /** Celular no silencioso, no vibrar, no "Não perturbe" ou em ligação: não é hora de falar sozinho. */
    fun shouldStayQuiet(): Boolean =
        audio.ringerMode != AudioManager.RINGER_MODE_NORMAL ||
            notificationManager.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL ||
            audio.mode == AudioManager.MODE_IN_CALL ||
            audio.mode == AudioManager.MODE_IN_COMMUNICATION

    /**
     * Fala [text] e espera terminar, abaixando a música enquanto fala. Devolve `false` se a voz em
     * português não está instalada ou se a fala não terminou em [timeoutMs].
     */
    suspend fun speak(text: String, purpose: Purpose = Purpose.REMINDER, timeoutMs: Long = 8_000): Boolean =
        withContext(Dispatchers.Main) {
            val attributes = AudioAttributes.Builder()
                .setUsage(purpose.usage)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            withTimeoutOrNull(timeoutMs) { speakNow(text, attributes) } ?: false.also { Log.w(TAG, "fala não terminou em $timeoutMs ms") }
        }

    private suspend fun speakNow(text: String, attributes: AudioAttributes): Boolean {
        val tts = openEngine(attributes) ?: return false
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes)
            .build()
        audio.requestAudioFocus(focus)
        return try {
            suspendCancellableCoroutine { cont ->
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit
                    override fun onDone(utteranceId: String?) {
                        if (cont.isActive) cont.resume(true)
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        Log.w(TAG, "erro ao falar")
                        if (cont.isActive) cont.resume(false)
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        Log.w(TAG, "erro ao falar: código $errorCode")
                        if (cont.isActive) cont.resume(false)
                    }
                })
                val started = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
                if (started != TextToSpeech.SUCCESS) {
                    Log.w(TAG, "speak recusou: $started")
                    if (cont.isActive) cont.resume(false)
                }
            }
        } finally {
            audio.abandonAudioFocusRequest(focus)
            tts.shutdown()
        }
    }

    private suspend fun openEngine(attributes: AudioAttributes): TextToSpeech? = suspendCancellableCoroutine { cont ->
        var engine: TextToSpeech? = null
        engine = TextToSpeech(context) { status ->
            val tts = engine
            val language = if (tts != null && status == TextToSpeech.SUCCESS) tts.setLanguage(PT_BR) else null
            Log.i(TAG, "mecanismo ${tts?.defaultEngine}: status $status, idioma pt-BR $language")
            if (tts != null && language != null && language >= TextToSpeech.LANG_AVAILABLE) {
                tts.setAudioAttributes(attributes)
                if (cont.isActive) cont.resume(tts) else tts.shutdown()
            } else {
                tts?.shutdown()
                if (cont.isActive) cont.resume(null)
            }
        }
        cont.invokeOnCancellation { engine.shutdown() }
    }

    private companion object {
        const val TAG = "JarvisVoz"
        val PT_BR: Locale = Locale.forLanguageTag("pt-BR")
        const val UTTERANCE_ID = "jarvis"
    }
}
