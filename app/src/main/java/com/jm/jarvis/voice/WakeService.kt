package com.jm.jarvis.voice

import ai.picovoice.porcupine.PorcupineException
import ai.picovoice.porcupine.PorcupineManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import com.jm.jarvis.R
import com.jm.jarvis.container
import com.jm.jarvis.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Fica ouvindo "Ei, Jarvis" com o Porcupine (no aparelho, sem enviar áudio). Ao ouvir, pausa a
 * detecção, escuta o pedido, responde em voz alta e volta a esperar pela palavra.
 *
 * O Android só deixa iniciar um serviço de microfone com o app na tela, e não o reinicia sozinho
 * depois de religar o celular: ele volta a ouvir quando o app é aberto.
 */
class WakeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var porcupine: PorcupineManager? = null
    private var turn: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            scope.launch { container.settings.update { it.copy(wakeEnabled = false) } }
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, notification("Ouvindo \"Ei, Jarvis\""), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        if (porcupine == null) scope.launch { startDetector() }
        return START_NOT_STICKY
    }

    private suspend fun startDetector() {
        val settings = container.settings.current()
        val problem = unavailable(this, settings.picovoiceKey)
        if (problem != null) {
            Log.w(TAG, problem)
            container.settings.update { it.copy(wakeEnabled = false) }
            stopSelf()
            return
        }
        try {
            porcupine = PorcupineManager.Builder()
                .setAccessKey(settings.picovoiceKey)
                .setKeywordPath("$ASSETS/$KEYWORD")
                .setModelPath("$ASSETS/$MODEL")
                .setSensitivity(0.7f)
                .setErrorCallback { Log.w(TAG, "erro no detector", it) }
                .build(this) { onWakeWord() }
                .also { it.start() }
        } catch (e: PorcupineException) {
            Log.w(TAG, "não consegui iniciar o Porcupine", e)
            container.settings.update { it.copy(wakeEnabled = false) }
            stopSelf()
        }
    }

    private fun onWakeWord() {
        if (turn?.isActive == true) return
        turn = scope.launch {
            val app = container
            // Celular no silencioso, no "Não perturbe" ou em ligação: não é hora de responder em voz alta.
            if (app.speaker.shouldStayQuiet()) return@launch
            porcupine?.stop() // libera o microfone para o reconhecedor de voz
            try {
                ToneGenerator(AudioManager.STREAM_MUSIC, 60).startTone(ToneGenerator.TONE_PROP_BEEP, 150)
                when (val heard = app.dialogue.hear()) {
                    is Hearing.Final -> app.dialogue.reply(heard.text)
                    is Hearing.Failed -> Log.i(TAG, "não ouvi o pedido: ${SpeechInput.describe(heard.code)}")
                    else -> Unit
                }
            } finally {
                runCatching { porcupine?.start() }.onFailure { Log.w(TAG, "não voltei a ouvir", it) }
            }
        }
    }

    override fun onDestroy() {
        turn?.cancel()
        scope.cancel()
        runCatching { porcupine?.stop() }
        runCatching { porcupine?.delete() }
        porcupine = null
        super.onDestroy()
    }

    private fun notification(text: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "JARVIS ouvindo", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, WakeService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("JARVIS")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Parar", stop).build())
            .build()
    }

    companion object {
        private const val TAG = "JarvisWake"
        private const val CHANNEL = "wake"
        private const val NOTIFICATION_ID = 7001
        private const val ACTION_STOP = "com.jm.jarvis.action.STOP_WAKE"
        private const val ASSETS = "wake"
        const val KEYWORD = "ei_jarvis.ppn"
        const val MODEL = "porcupine_params_pt.pv"

        /** Por que não dá para ouvir a palavra de ativação, ou `null` se está tudo pronto. */
        fun unavailable(context: Context, accessKey: String): String? {
            if (accessKey.isBlank()) return "Falta a chave da Picovoice."
            val files = context.assets.list(ASSETS).orEmpty()
            if (KEYWORD !in files || MODEL !in files) return "Faltam $KEYWORD e $MODEL em app/src/main/assets/$ASSETS/."
            return null
        }

        /** Chamar com o app na tela. */
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, WakeService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WakeService::class.java))
        }
    }
}
