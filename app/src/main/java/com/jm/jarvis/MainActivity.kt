package com.jm.jarvis

import android.Manifest
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.jm.jarvis.brain.ReplyEvent
import com.jm.jarvis.ui.JarvisAppUi
import com.jm.jarvis.voice.WakeService
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private var listenRequests by mutableIntStateOf(0)

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        container.scope.launch { container.calendar.refresh() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = container

        // Cria as rotinas na primeira abertura e garante que todos os alarmes estão armados.
        app.scope.launch {
            app.habits.seedIfNeeded(app.settings)
            app.rearmAll()
            app.web.rememberLocation()
            // O Android só deixa iniciar o serviço de microfone com o app na tela; religar o celular exige abrir o app.
            if (app.settings.current().wakeEnabled) WakeService.start(this@MainActivity)
        }

        if (savedInstanceState == null) {
            val missing = listOf(
                Manifest.permission.POST_NOTIFICATIONS,
                Manifest.permission.READ_CALENDAR,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ).filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
            if (missing.isNotEmpty()) permissions.launch(missing.toTypedArray())
            handleListen(intent)
        }
        handleDebugText(intent)

        setContent { JarvisAppUi(app, listenRequests) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleListen(intent)
        handleDebugText(intent)
    }

    /**
     * Só em build de depuração: `adb shell am start -n ... --es jarvis_texto "frase"` roda a frase pelo
     * mesmo caminho da conversa (atalhos, IA, ferramentas) e escreve a resposta no log `JarvisTeste`.
     * No app de verdade isto não existe, então nenhum outro app consegue acionar comandos.
     */
    private fun handleDebugText(intent: Intent?) {
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        val text = intent?.getStringExtra("jarvis_texto")?.takeIf { debuggable } ?: return
        container.scope.launch {
            container.brain.reply(text).collect { event ->
                if (event is ReplyEvent.Done) Log.i("JarvisTeste", "«$text» -> ${event.reply.text}")
            }
        }
    }

    /** O modelo ocupa ~600 MB: sai da memória quando o app sai da tela e volta na próxima conversa. */
    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) container.scope.launch { container.brain.release() }
    }

    private fun handleListen(intent: Intent?) {
        if (intent?.action == ACTION_LISTEN || intent?.action == Intent.ACTION_ASSIST) listenRequests++
    }

    companion object {
        /** Abre direto ouvindo; usado pelo botão Falar do widget. */
        const val ACTION_LISTEN = "com.jm.jarvis.action.LISTEN"
    }
}
