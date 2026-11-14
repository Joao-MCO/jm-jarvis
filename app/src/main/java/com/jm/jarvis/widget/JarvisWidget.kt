package com.jm.jarvis.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.Button
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.jm.jarvis.MainActivity
import com.jm.jarvis.container
import com.jm.jarvis.domain.HabitStatus
import com.jm.jarvis.domain.Progress
import com.jm.jarvis.domain.TodayState

/** Widget da tela inicial: encolhido mostra o próximo aviso; aberto, o painel do dia com botões rápidos. */
class JarvisWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Responsive(setOf(SMALL, LARGE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val today = context.container.habits.todayState()
        val listen = Intent(context, MainActivity::class.java)
            .setAction(MainActivity.ACTION_LISTEN)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        provideContent {
            GlanceTheme {
                Content(today, listen)
            }
        }
    }

    @Composable
    private fun Content(today: TodayState, listen: Intent) {
        val size = LocalSize.current
        Column(
            modifier = GlanceModifier.fillMaxSize()
                .background(GlanceTheme.colors.widgetBackground)
                .cornerRadius(20.dp)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Header(today, listen)
            if (size.height >= LARGE.height && size.width >= LARGE.width) {
                val rows = ((size.height.value - 52) / 34).toInt().coerceIn(1, 6)
                today.statuses.sortedBy { it.completeToday }.take(rows).forEach { HabitRow(it) }
            }
        }
    }

    @Composable
    private fun Header(today: TodayState, listen: Intent) {
        val next = today.next?.let { "${Progress.upcoming(it.at, today.date)} · ${it.habit.name}" } ?: "Nenhum aviso pendente"
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = GlanceModifier.defaultWeight().clickable(actionStartActivity<MainActivity>())) {
                Text(
                    "J.A.R.V.I.S.",
                    style = TextStyle(color = GlanceTheme.colors.primary, fontSize = 15.sp, fontWeight = FontWeight.Bold),
                )
                Text(next, style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 12.sp), maxLines = 1)
            }
            Button(text = "Falar", onClick = actionStartActivity(listen))
        }
        Spacer(GlanceModifier.height(6.dp))
    }

    @Composable
    private fun HabitRow(status: HabitStatus) {
        Row(
            modifier = GlanceModifier.fillMaxWidth().padding(vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = GlanceModifier.defaultWeight()) {
                Text(
                    "${status.habit.name} · ${Progress.describe(status)}",
                    style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 12.sp),
                    maxLines = 1,
                )
                LinearProgressIndicator(
                    progress = status.fraction,
                    modifier = GlanceModifier.fillMaxWidth().height(4.dp),
                    color = GlanceTheme.colors.primary,
                    backgroundColor = GlanceTheme.colors.surfaceVariant,
                )
            }
            if (!status.completeToday) {
                Spacer(GlanceModifier.width(8.dp))
                Button(
                    text = Progress.quickLabel(status.habit),
                    onClick = actionRunCallback<QuickLogAction>(actionParametersOf(HABIT_ID to status.habit.id)),
                )
            }
        }
    }

    companion object {
        private val SMALL = DpSize(110.dp, 40.dp)
        private val LARGE = DpSize(250.dp, 110.dp)
        val HABIT_ID = ActionParameters.Key<Long>("habitId")

        suspend fun refresh(context: Context) = JarvisWidget().updateAll(context)
    }
}

/** Botão rápido do widget: registra sem abrir o app. */
class QuickLogAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val habitId = parameters[JarvisWidget.HABIT_ID] ?: return
        context.container.habits.logQuick(habitId)
    }
}

class JarvisWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = JarvisWidget()
}
