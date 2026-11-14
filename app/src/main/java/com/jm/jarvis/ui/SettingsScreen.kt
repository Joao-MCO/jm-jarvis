package com.jm.jarvis.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jm.jarvis.AppContainer
import com.jm.jarvis.data.Settings
import com.jm.jarvis.domain.Progress
import com.jm.jarvis.voice.Speaker
import com.jm.jarvis.voice.WakeService
import kotlinx.coroutines.launch

/** Qual horário o seletor está editando. */
private enum class QuietEdge { START, END, BRIEFING }

@Composable
fun SettingsScreen(app: AppContainer) {
    val context = LocalContext.current
    val settings by remember { app.settings.flow }.collectAsStateWithLifecycle(initialValue = null)
    val facts by remember { app.memory.observe() }.collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()

    // Permissões podem mudar fora do app: relê ao voltar para a tela.
    var resumes by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        resumes++
        onPauseOrDispose { }
    }
    val calendarGranted = remember(resumes) { app.calendar.hasPermission() }
    val notificationsOn = remember(resumes) { app.notifications.enabled() }
    val batteryFree = remember(resumes) {
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
    }
    var nextCalendar by remember { mutableStateOf<String?>(null) }
    var voiceProblem by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf<QuietEdge?>(null) }

    val calendarPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        resumes++
        scope.launch { app.calendar.refresh() }
    }
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { resumes++ }
    val locationGranted = remember(resumes) { app.web.hasLocationPermission() }
    val contactsGranted = remember(resumes) { app.control.contactsAllowed() }
    val uriHandler = LocalUriHandler.current
    val current = settings ?: return
    var keyDraft by remember(current.tavilyKey) { mutableStateOf(current.tavilyKey) }
    var picovoiceDraft by remember(current.picovoiceKey) { mutableStateOf(current.picovoiceKey) }
    var cloudKeyDraft by remember(current.cloudKey) { mutableStateOf(current.cloudKey) }
    var cloudModelDraft by remember(current.cloudModel) { mutableStateOf(current.cloudModel) }
    var cloudUrlDraft by remember(current.cloudUrl) { mutableStateOf(current.cloudUrl) }
    LaunchedEffect(resumes, current.calendarSpeech) { nextCalendar = app.calendar.describeNext() }

    fun update(transform: (Settings) -> Settings) {
        scope.launch {
            app.settings.update(transform)
            app.briefing.schedule()
            app.calendar.refresh()
            nextCalendar = app.calendar.describeNext()
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Ajustes", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)

        SwitchRow("Silenciar JARVIS", "Os avisos continuam aparecendo, mas sem som e sem fala.", current.muted) { on ->
            update { it.copy(muted = on) }
        }

        SwitchRow(
            "Horário de silêncio",
            "Sem som e sem fala das ${Progress.hhmm(current.quietStart)} às ${Progress.hhmm(current.quietEnd)}.",
            current.quietEnabled,
        ) { on -> update { it.copy(quietEnabled = on) } }
        if (current.quietEnabled) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Das")
                TextButton(onClick = { picking = QuietEdge.START }) { Text(Progress.hhmm(current.quietStart)) }
                Text("às")
                TextButton(onClick = { picking = QuietEdge.END }) { Text(Progress.hhmm(current.quietEnd)) }
            }
        }

        SwitchRow(
            "Briefing da manhã",
            "Às ${Progress.hhmm(current.briefingTime)}: clima, agenda, rotinas e lembretes do dia, falados e na notificação.",
            current.briefingEnabled,
        ) { on -> update { it.copy(briefingEnabled = on) } }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (current.briefingEnabled) {
                Text("Horário")
                TextButton(onClick = { picking = QuietEdge.BRIEFING }) { Text(Progress.hhmm(current.briefingTime)) }
            }
            OutlinedButton(onClick = {
                scope.launch { app.speaker.speak(app.briefing.text(), Speaker.Purpose.REPLY, timeoutMs = 40_000) }
            }) { Text("Ouvir briefing agora") }
        }

        SwitchRow(
            "Falar os eventos da agenda",
            "No horário do lembrete de cada evento. A notificação continua sendo a do Google Agenda.",
            current.calendarSpeech && calendarGranted,
        ) { on ->
            if (on && !calendarGranted) calendarPermission.launch(Manifest.permission.READ_CALENDAR)
            update { it.copy(calendarSpeech = on) }
        }
        if (current.calendarSpeech && calendarGranted) {
            Text(
                nextCalendar ?: "Nenhum lembrete de evento nos próximos 7 dias.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        OutlinedButton(onClick = {
            scope.launch {
                voiceProblem = null
                val quiet = app.speaker.shouldStayQuiet()
                val ok = app.speaker.speak("Olá! Eu sou o JARVIS.", Speaker.Purpose.REPLY)
                voiceProblem = when {
                    !ok -> "A voz em português não respondeu. Confira em Ajustes do Android → Gerenciamento geral → Texto para fala."
                    quiet -> "O celular está no vibrar, no silencioso ou no Não perturbe: as respostas falam no volume de mídia, mas os avisos de rotina só falam com o som ligado."
                    else -> null
                }
            }
        }) { Text("Testar voz") }
        Text(
            "As respostas da conversa usam o volume de mídia; os avisos de rotina, o volume de notificação.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        voiceProblem?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        HorizontalDivider()
        Text("O que o JARVIS sabe sobre você", style = MaterialTheme.typography.titleMedium)
        if (facts.isEmpty()) {
            Text(
                "Nada ainda. Diga, por exemplo: \"lembra que eu sou alérgico a camarão\".",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        facts.forEach { fact ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(fact.text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { scope.launch { app.memory.forget(fact.id) } }) { Text("Apagar") }
            }
        }

        HorizontalDivider()
        Text("Ei, Jarvis", style = MaterialTheme.typography.titleMedium)
        val wakeProblem = WakeService.unavailable(context, current.picovoiceKey)
        SwitchRow(
            "Ouvir \"Ei, Jarvis\"",
            "Fica ouvindo no aparelho (o áudio não sai do celular) e mostra uma notificação fixa. Gasta um pouco de bateria. Depois de religar o celular, abra o app uma vez.",
            current.wakeEnabled,
        ) { on ->
            if (on && wakeProblem != null) return@SwitchRow
            update { it.copy(wakeEnabled = on) }
            if (on) WakeService.start(context) else WakeService.stop(context)
        }
        if (wakeProblem != null) {
            Text(wakeProblem, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        OutlinedTextField(
            value = picovoiceDraft,
            onValueChange = { picovoiceDraft = it.trim() },
            label = { Text("Chave da Picovoice") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { update { it.copy(picovoiceKey = picovoiceDraft) } }, enabled = picovoiceDraft != current.picovoiceKey) { Text("Salvar") }
            TextButton(onClick = { uriHandler.openUri("https://console.picovoice.ai") }) { Text("Criar chave em picovoice.ai") }
        }

        HorizontalDivider()
        Text("IA online", style = MaterialTheme.typography.titleMedium)
        SwitchRow(
            "Usar IA online quando houver internet",
            "Responde em 1 a 2 segundos e erra bem menos. Sem internet, ou se falhar, usa o modelo do celular. A frase, suas rotinas e os fatos guardados vão para o provedor.",
            current.cloudEnabled,
        ) { on -> update { it.copy(cloudEnabled = on) } }
        OutlinedTextField(
            value = cloudKeyDraft,
            onValueChange = { cloudKeyDraft = it.trim() },
            label = { Text("Chave da API (Gemini, Groq...)") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = cloudModelDraft,
            onValueChange = { cloudModelDraft = it.trim() },
            label = { Text("Modelo") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = cloudUrlDraft,
            onValueChange = { cloudUrlDraft = it.trim() },
            label = { Text("Endereço (formato OpenAI)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                onClick = { update { it.copy(cloudKey = cloudKeyDraft, cloudModel = cloudModelDraft, cloudUrl = cloudUrlDraft) } },
                enabled = cloudKeyDraft != current.cloudKey || cloudModelDraft != current.cloudModel || cloudUrlDraft != current.cloudUrl,
            ) { Text("Salvar") }
            TextButton(onClick = { uriHandler.openUri("https://aistudio.google.com/apikey") }) { Text("Criar chave grátis no Google AI Studio") }
        }

        HorizontalDivider()
        Text("Pesquisa na internet", style = MaterialTheme.typography.titleMedium)
        Text(
            if (current.tavilyKey.isBlank()) "Para pesquisar notícias, preços e placares, crie uma chave grátis na Tavily (1.000 buscas por mês) e cole aqui. A Wikipedia e o clima funcionam sem chave."
            else "Chave da Tavily salva. Só a sua pergunta é enviada para a busca.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = keyDraft,
                onValueChange = { keyDraft = it.trim() },
                label = { Text("Chave da Tavily") },
                placeholder = { Text("tvly-...") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { update { it.copy(tavilyKey = keyDraft) } }, enabled = keyDraft != current.tavilyKey) { Text("Salvar") }
        }
        TextButton(onClick = { uriHandler.openUri("https://app.tavily.com") }) { Text("Criar chave em tavily.com") }
        PermissionRow("Localização (para o clima)", locationGranted, "Permitir") {
            locationPermission.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        PermissionRow("Contatos (para ligar e WhatsApp)", contactsGranted, "Permitir") {
            locationPermission.launch(Manifest.permission.READ_CONTACTS)
        }

        HorizontalDivider()
        Text("Para os avisos chegarem na hora", style = MaterialTheme.typography.titleMedium)

        PermissionRow("Notificações", notificationsOn, "Ativar") {
            context.startActivity(
                Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(AndroidSettings.EXTRA_APP_PACKAGE, context.packageName),
            )
        }
        PermissionRow("Rodar em segundo plano", batteryFree, "Liberar") {
            context.startActivity(
                Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")),
            )
        }
    }

    picking?.let { edge ->
        TimeDialog(
            initial = when (edge) {
                QuietEdge.START -> current.quietStart
                QuietEdge.END -> current.quietEnd
                QuietEdge.BRIEFING -> current.briefingTime
            },
            onDismiss = { picking = null },
            onConfirm = { time ->
                update {
                    when (edge) {
                        QuietEdge.START -> it.copy(quietStart = time)
                        QuietEdge.END -> it.copy(quietEnd = time)
                        QuietEdge.BRIEFING -> it.copy(briefingTime = time)
                    }
                }
                picking = null
            },
        )
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun PermissionRow(title: String, granted: Boolean, actionLabel: String, onFix: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        if (granted) Text("OK", color = MaterialTheme.colorScheme.primary) else TextButton(onClick = onFix) { Text(actionLabel) }
    }
}
