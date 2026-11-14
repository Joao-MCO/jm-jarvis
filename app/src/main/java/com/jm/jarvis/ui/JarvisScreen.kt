package com.jm.jarvis.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.jm.jarvis.AppContainer
import com.jm.jarvis.R

@Composable
fun JarvisScreen(app: AppContainer, controller: JarvisController) {
    val context = LocalContext.current
    var typed by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) controller.onMic()
    }
    val onMic = {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) controller.onMic()
        else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    LaunchedEffect(Unit) { app.brain.warmUp() }
    LaunchedEffect(controller.lines.size) {
        if (controller.lines.isNotEmpty()) listState.animateScrollToItem(controller.lines.lastIndex)
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("J.A.R.V.I.S.", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (controller.lines.isEmpty()) {
                item {
                    Text(
                        "Toque no microfone e fale, por exemplo: \"bebi 500 ml de água\", \"o que falta hoje?\", \"vai chover amanhã?\" ou \"quem ganhou o jogo do Flamengo?\".",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
            }
            items(controller.lines) { Bubble(it) }
            if (controller.partial.isNotEmpty()) item { Bubble(ChatLine(fromUser = true, text = controller.partial), faded = true) }
        }

        Text(
            when (controller.phase) {
                Phase.IDLE -> "Toque para falar"
                Phase.LISTENING -> "Ouvindo…"
                Phase.THINKING -> "Pensando…"
                Phase.SPEAKING -> "Falando… toque para parar"
            },
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp),
        )
        Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
            HudRing(controller.phase, controller.level)
            FilledIconButton(
                onClick = onMic,
                enabled = controller.phase != Phase.THINKING,
                modifier = Modifier.size(84.dp),
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            ) {
                Icon(
                    painterResource(if (controller.phase == Phase.IDLE) R.drawable.ic_mic else R.drawable.ic_stop),
                    contentDescription = if (controller.phase == Phase.IDLE) "Falar com o JARVIS" else "Parar",
                    modifier = Modifier.size(36.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = typed,
                onValueChange = { typed = it },
                placeholder = { Text("Ou digite aqui") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = {
                    controller.onText(typed)
                    typed = ""
                }),
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = {
                    controller.onText(typed)
                    typed = ""
                },
                enabled = typed.isNotBlank() && controller.phase == Phase.IDLE,
            ) {
                Icon(painterResource(R.drawable.ic_send), contentDescription = "Enviar")
            }
        }
    }
}

@Composable
private fun Bubble(line: ChatLine, faded: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    val uriHandler = LocalUriHandler.current
    Box(Modifier.fillMaxWidth(), contentAlignment = if (line.fromUser) Alignment.CenterEnd else Alignment.CenterStart) {
        Column(
            Modifier
                .widthIn(max = 300.dp)
                .background(
                    if (line.fromUser) colors.primaryContainer else colors.surfaceContainerHigh,
                    RoundedCornerShape(16.dp),
                )
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(
                line.text,
                color = if (line.fromUser) colors.onPrimaryContainer.copy(alpha = if (faded) 0.6f else 1f) else colors.onSurface,
            )
            line.sources.forEach { source ->
                Text(
                    "↗ ${source.title.ifBlank { source.url }}",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.primary,
                    maxLines = 1,
                    modifier = Modifier.padding(top = 6.dp).clickable { uriHandler.openUri(source.url) },
                )
            }
        }
    }
}

/** Anéis em volta do microfone: pulsam com a voz ao ouvir e giram enquanto pensa. */
@Composable
private fun HudRing(phase: Phase, level: Float) {
    val primary = MaterialTheme.colorScheme.primary
    val pulse by animateFloatAsState(if (phase == Phase.LISTENING) 1f + level * 0.35f else 1f, label = "pulso")
    val spin by rememberInfiniteTransition(label = "giro").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(if (phase == Phase.THINKING) 1_200 else 9_000, easing = LinearEasing), RepeatMode.Restart),
        label = "angulo",
    )
    Canvas(Modifier.size(150.dp)) {
        val radius = size.minDimension / 2
        drawCircle(primary.copy(alpha = 0.25f), radius = radius * 0.72f * pulse, style = Stroke(width = 3.dp.toPx()))
        drawCircle(primary.copy(alpha = 0.12f), radius = radius * 0.92f * pulse, style = Stroke(width = 2.dp.toPx()))
        val arc = radius * 0.84f
        drawArc(
            color = primary.copy(alpha = if (phase == Phase.IDLE) 0.35f else 0.9f),
            startAngle = spin,
            sweepAngle = 70f,
            useCenter = false,
            topLeft = center.copy(x = center.x - arc, y = center.y - arc),
            size = Size(arc * 2, arc * 2),
            style = Stroke(width = 4.dp.toPx()),
        )
    }
}
