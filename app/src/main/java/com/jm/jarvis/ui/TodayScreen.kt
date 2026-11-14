package com.jm.jarvis.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jm.jarvis.AppContainer
import com.jm.jarvis.data.Settings
import com.jm.jarvis.domain.HabitStatus
import com.jm.jarvis.domain.PT_BR
import com.jm.jarvis.domain.Progress
import com.jm.jarvis.domain.TodayState
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun TodayScreen(app: AppContainer, snackbar: SnackbarHostState) {
    val state by remember { app.habits.observeToday() }.collectAsStateWithLifecycle(initialValue = null)
    val settings by remember { app.settings.flow }.collectAsStateWithLifecycle(initialValue = Settings())
    val reminders by remember { app.reminders.observePending() }.collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    val today = state ?: return

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Header(today, settings.muted) }
        if (reminders.isNotEmpty()) {
            item { Text("Lembretes", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary) }
            items(reminders, key = { "r${it.id}" }) { reminder ->
                val at = LocalDateTime.ofInstant(Instant.ofEpochMilli(reminder.at), ZoneId.systemDefault())
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(reminder.text, style = MaterialTheme.typography.bodyLarge)
                            Text(Progress.upcoming(at, today.date), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = { scope.launch { app.reminders.cancel(reminder.id) } }) { Text("Cancelar") }
                    }
                }
            }
            item { Text("Rotinas", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary) }
        }
        items(today.statuses.sortedBy { it.completeToday }, key = { it.habit.id }) { status ->
            HabitCard(status) {
                scope.launch {
                    val logId = app.habits.logQuick(status.habit.id) ?: return@launch
                    val result = snackbar.showSnackbar(
                        message = "${status.habit.name}: registrado",
                        actionLabel = "Desfazer",
                        duration = SnackbarDuration.Short,
                    )
                    if (result == SnackbarResult.ActionPerformed) app.habits.undo(logId, status.habit.id)
                }
            }
        }
        if (today.statuses.isEmpty()) item { Text("Nenhuma rotina para hoje.") }
    }
}

@Composable
private fun Header(today: TodayState, muted: Boolean) {
    Column {
        Text("Hoje", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
        Text(today.date.format(DateTimeFormatter.ofPattern("EEEE, d 'de' MMMM", PT_BR)), style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(4.dp))
        val next = today.next?.let { "Próximo aviso: ${Progress.upcoming(it.at, today.date)} · ${it.habit.name}" }
            ?: "Nenhum aviso pendente"
        Text(next, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (muted) {
            Spacer(Modifier.height(4.dp))
            AssistChip(onClick = {}, label = { Text("JARVIS silenciado") })
        }
    }
}

@Composable
private fun HabitCard(status: HabitStatus, onQuick: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(status.habit.name, style = MaterialTheme.typography.titleMedium)
                Text(Progress.describe(status), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(progress = { status.fraction }, modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.width(12.dp))
            if (status.completeToday) {
                Text("Feito", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
            } else {
                FilledTonalButton(onClick = onQuick) { Text(Progress.quickLabel(status.habit)) }
            }
        }
    }
}
