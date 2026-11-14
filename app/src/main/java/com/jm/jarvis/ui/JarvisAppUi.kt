package com.jm.jarvis.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import com.jm.jarvis.AppContainer
import com.jm.jarvis.R
import java.time.LocalTime

private enum class Tab(val label: String, val icon: Int) {
    JARVIS("JARVIS", R.drawable.ic_mic),
    TODAY("Hoje", R.drawable.ic_today),
    ROUTINES("Rotinas", R.drawable.ic_routines),
    SETTINGS("Ajustes", R.drawable.ic_settings),
}

private val JarvisColors = darkColorScheme(
    primary = Color(0xFF4FC3F7),
    onPrimary = Color(0xFF002233),
    primaryContainer = Color(0xFF0B3A52),
    onPrimaryContainer = Color(0xFFCDEBFA),
    secondary = Color(0xFFFFB74D),
    background = Color(0xFF070D16),
    surface = Color(0xFF070D16),
    surfaceContainer = Color(0xFF101A27),
    surfaceContainerHigh = Color(0xFF152233),
)

/**
 * @param listenRequests aumenta a cada pedido para ouvir já (botão Falar do widget, botão de assistente):
 * abre a aba JARVIS e liga o microfone.
 */
@Composable
fun JarvisAppUi(app: AppContainer, listenRequests: Int) {
    var tab by rememberSaveable { mutableStateOf(Tab.JARVIS) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    // Fica acima das abas para a conversa sobreviver à troca de aba.
    val controller = remember { JarvisController(app, scope) }
    val context = LocalContext.current

    LaunchedEffect(listenRequests) {
        if (listenRequests == 0) return@LaunchedEffect
        tab = Tab.JARVIS
        val micAllowed = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (micAllowed && controller.phase == Phase.IDLE) controller.onMic()
    }

    MaterialTheme(colorScheme = JarvisColors) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                NavigationBar {
                    Tab.entries.forEach { item ->
                        NavigationBarItem(
                            selected = tab == item,
                            onClick = { tab = item },
                            icon = { Icon(painterResource(item.icon), contentDescription = null) },
                            label = { Text(item.label) },
                        )
                    }
                }
            },
        ) { padding ->
            Box(Modifier.padding(padding)) {
                when (tab) {
                    Tab.JARVIS -> JarvisScreen(app, controller)
                    Tab.TODAY -> TodayScreen(app, snackbar)
                    Tab.ROUTINES -> RoutinesScreen(app)
                    Tab.SETTINGS -> SettingsScreen(app)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeDialog(initial: LocalTime, onDismiss: () -> Unit, onConfirm: (LocalTime) -> Unit, onRemove: (() -> Unit)? = null) {
    val state = rememberTimePickerState(initialHour = initial.hour, initialMinute = initial.minute, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onConfirm(LocalTime.of(state.hour, state.minute)) }) { Text("OK") } },
        dismissButton = {
            if (onRemove != null) TextButton(onClick = onRemove) { Text("Remover") }
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        },
        text = { TimePicker(state = state) },
    )
}
