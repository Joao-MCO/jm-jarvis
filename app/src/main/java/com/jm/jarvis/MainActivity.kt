package com.jm.jarvis

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.jm.jarvis.ui.JarvisAppUi
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
            app.habits.rescheduleAll()
            app.calendar.refresh()
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

        setContent { JarvisAppUi(app, listenRequests) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleListen(intent)
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
