package com.jm.jarvis.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jm.jarvis.AppContainer
import com.jm.jarvis.R
import com.jm.jarvis.data.AlertMode
import com.jm.jarvis.data.Habit
import com.jm.jarvis.data.ScheduleType
import com.jm.jarvis.domain.DraftResult
import com.jm.jarvis.domain.GoalKind
import com.jm.jarvis.domain.Progress
import com.jm.jarvis.domain.RoutineDraft
import com.jm.jarvis.domain.label
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalTime

@Composable
fun RoutinesScreen(app: AppContainer) {
    val habits by remember { app.habits.observeHabits() }.collectAsStateWithLifecycle(initialValue = emptyList())
    var editing by remember { mutableStateOf<RoutineDraft?>(null) }
    val scope = rememberCoroutineScope()

    editing?.let { draft ->
        val base = draft.base
        RoutineEditor(
            initial = draft,
            onCancel = { editing = null },
            onSave = { habit -> scope.launch { app.habits.save(habit); editing = null } },
            onDelete = if (base == null) null else { { scope.launch { app.habits.delete(base); editing = null } } },
        )
        return
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(contentPadding = PaddingValues(bottom = 88.dp)) {
            item {
                Text(
                    "Rotinas",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(16.dp),
                )
            }
            items(habits, key = { it.id }) { habit -> RoutineRow(habit) { editing = RoutineDraft.from(habit) } }
        }
        FloatingActionButton(
            onClick = { editing = RoutineDraft() },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        ) {
            Icon(painterResource(R.drawable.ic_add), contentDescription = "Nova rotina")
        }
    }
}

@Composable
private fun RoutineRow(habit: Habit, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(if (habit.active) habit.name else "${habit.name} (pausada)") },
        supportingContent = { Text(Progress.schedule(habit)) },
        trailingContent = { Text(habit.alertMode.label, style = MaterialTheme.typography.labelMedium) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

private sealed interface TimeTarget {
    data object Add : TimeTarget
    data class Replace(val time: LocalTime) : TimeTarget
    data object WindowStart : TimeTarget
    data object WindowEnd : TimeTarget
}

private val DAY_CHIPS = listOf(
    DayOfWeek.MONDAY to "Seg", DayOfWeek.TUESDAY to "Ter", DayOfWeek.WEDNESDAY to "Qua",
    DayOfWeek.THURSDAY to "Qui", DayOfWeek.FRIDAY to "Sex", DayOfWeek.SATURDAY to "Sáb", DayOfWeek.SUNDAY to "Dom",
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RoutineEditor(initial: RoutineDraft, onCancel: () -> Unit, onSave: (Habit) -> Unit, onDelete: (() -> Unit)?) {
    var draft by remember { mutableStateOf(initial) }
    var error by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf<TimeTarget?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    BackHandler(onBack = onCancel)

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            if (initial.base == null) "Nova rotina" else "Editar rotina",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        OutlinedTextField(
            value = draft.name,
            onValueChange = { draft = draft.copy(name = it) },
            label = { Text("Nome") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Section("Quando avisar") {
            Choice(listOf("Horários fixos" to ScheduleType.TIMES, "Intervalo" to ScheduleType.INTERVAL), draft.type) {
                draft = draft.copy(type = it)
            }
            when (draft.type) {
                ScheduleType.TIMES -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    draft.times.sorted().forEach { time ->
                        AssistChip(onClick = { picking = TimeTarget.Replace(time) }, label = { Text(Progress.hhmm(time)) })
                    }
                    AssistChip(onClick = { picking = TimeTarget.Add }, label = { Text("+ horário") })
                }
                ScheduleType.INTERVAL -> {
                    NumberField(draft.interval, "A cada quantos minutos") { draft = draft.copy(interval = it) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Entre")
                        TextButton(onClick = { picking = TimeTarget.WindowStart }) { Text(Progress.hhmm(draft.windowStart)) }
                        Text("e")
                        TextButton(onClick = { picking = TimeTarget.WindowEnd }) { Text(Progress.hhmm(draft.windowEnd)) }
                    }
                }
            }
        }

        Section("Dias") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DAY_CHIPS.forEach { (day, label) ->
                    FilterChip(
                        selected = day in draft.days,
                        onClick = { draft = draft.copy(days = if (day in draft.days) draft.days - day else draft.days + day) },
                        label = { Text(label) },
                    )
                }
            }
        }

        Section("Meta") {
            Choice(listOf("Nenhuma" to GoalKind.NONE, "Por dia" to GoalKind.DAILY, "Por semana" to GoalKind.WEEKLY), draft.goal) {
                draft = draft.copy(goal = it)
            }
            when (draft.goal) {
                GoalKind.NONE -> Text(
                    "Cada vez que você marcar como feito conta uma.",
                    style = MaterialTheme.typography.bodySmall,
                )
                GoalKind.DAILY -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.weight(1f)) { NumberField(draft.dailyTarget, "Meta por dia", decimal = true) { draft = draft.copy(dailyTarget = it) } }
                        OutlinedTextField(
                            value = draft.unit,
                            onValueChange = { draft = draft.copy(unit = it) },
                            label = { Text("Unidade") },
                            placeholder = { Text("ml, min") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    NumberField(draft.quickAmount, "Botão rápido soma (opcional)", decimal = true) { draft = draft.copy(quickAmount = it) }
                }
                GoalKind.WEEKLY -> NumberField(draft.weeklyTarget, "Vezes por semana") { draft = draft.copy(weeklyTarget = it) }
            }
        }

        Section("Como avisar") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AlertMode.entries.forEach { mode ->
                    FilterChip(selected = draft.alertMode == mode, onClick = { draft = draft.copy(alertMode = mode) }, label = { Text(mode.label) })
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Rotina ativa", Modifier.weight(1f))
            Switch(checked = draft.active, onCheckedChange = { draft = draft.copy(active = it) })
        }

        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        Row(verticalAlignment = Alignment.CenterVertically) {
            if (onDelete != null) TextButton(onClick = { confirmDelete = true }) { Text("Excluir") }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onCancel) { Text("Cancelar") }
            Button(onClick = {
                when (val result = draft.validate()) {
                    is DraftResult.Valid -> onSave(result.habit)
                    is DraftResult.Invalid -> error = result.message
                }
            }) { Text("Salvar") }
        }
    }

    when (val target = picking) {
        null -> Unit
        TimeTarget.Add -> TimeDialog(LocalTime.of(12, 0), onDismiss = { picking = null }, onConfirm = {
            draft = draft.copy(times = (draft.times + it).distinct())
            picking = null
        })
        is TimeTarget.Replace -> TimeDialog(
            target.time,
            onDismiss = { picking = null },
            onConfirm = {
                draft = draft.copy(times = (draft.times - target.time + it).distinct())
                picking = null
            },
            onRemove = {
                draft = draft.copy(times = draft.times - target.time)
                picking = null
            },
        )
        TimeTarget.WindowStart -> TimeDialog(draft.windowStart, onDismiss = { picking = null }, onConfirm = {
            draft = draft.copy(windowStart = it)
            picking = null
        })
        TimeTarget.WindowEnd -> TimeDialog(draft.windowEnd, onDismiss = { picking = null }, onConfirm = {
            draft = draft.copy(windowEnd = it)
            picking = null
        })
    }

    if (confirmDelete && onDelete != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Excluir \"${initial.name}\"?") },
            text = { Text("O histórico desta rotina também será apagado.") },
            confirmButton = { TextButton(onClick = onDelete) { Text("Excluir") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        content()
    }
}

@Composable
private fun NumberField(value: String, label: String, decimal: Boolean = false, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> Choice(options: List<Pair<String, T>>, selected: T, onSelect: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, (label, value) ->
            SegmentedButton(
                selected = value == selected,
                onClick = { onSelect(value) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
            ) { Text(label) }
        }
    }
}
