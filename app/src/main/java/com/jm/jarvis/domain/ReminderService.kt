package com.jm.jarvis.domain

import com.jm.jarvis.data.Reminder
import com.jm.jarvis.data.ReminderDao
import com.jm.jarvis.notify.Notifications
import com.jm.jarvis.notify.ReminderScheduler
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** Dono dos lembretes avulsos: cria, agenda o alarme, avisa e cancela. */
class ReminderService(
    private val dao: ReminderDao,
    private val scheduler: ReminderScheduler,
    private val notifications: Notifications,
) {
    private val zone: ZoneId get() = ZoneId.systemDefault()

    fun observePending(): Flow<List<Reminder>> = dao.observePending()

    suspend fun pending(): List<Reminder> = dao.pending()

    /** "Me lembra de [text] [whenText]". O horário é interpretado aqui, não pela IA. */
    suspend fun create(text: String, whenText: String): String {
        val what = text.trim().trimEnd('.')
        if (what.isEmpty()) return "Diga o que devo lembrar."
        val at = SpokenTime.moment(whenText, LocalDateTime.now())
            ?: return "Não entendi quando lembrar (\"$whenText\"). Diga, por exemplo, amanhã às 15h ou daqui a 20 minutos."
        val millis = at.atZone(zone).toInstant().toEpochMilli()
        val id = dao.insert(Reminder(text = what, at = millis, createdAt = System.currentTimeMillis()))
        scheduler.scheduleReminder(id, Instant.ofEpochMilli(millis))
        return "Lembrete criado: $what, ${Progress.upcoming(at, LocalDate.now(zone))}."
    }

    suspend fun describePending(): String {
        val list = dao.pending()
        if (list.isEmpty()) return "Nenhum lembrete pendente."
        val today = LocalDate.now(zone)
        return list.take(8).joinToString("; ", prefix = "Lembretes: ", postfix = ".") { "${it.text}, ${Progress.upcoming(local(it.at), today)}" }
    }

    /** Cancela o lembrete pendente cujo texto mais combina com [query]. */
    suspend fun cancel(query: String): String {
        val list = dao.pending()
        val match = TextMatch.best(query, list) { it.text } ?: return "Não achei um lembrete pendente sobre \"$query\"."
        finish(match.id)
        return "Lembrete cancelado: ${match.text}."
    }

    suspend fun cancel(id: Long) = finish(id)

    suspend fun snooze(id: Long, minutes: Long) {
        val at = System.currentTimeMillis() + minutes * 60_000
        dao.moveTo(id, at)
        notifications.cancelReminder(id)
        scheduler.scheduleReminder(id, Instant.ofEpochMilli(at))
    }

    /** Chamado pelo alarme: devolve o lembrete para avisar e o marca como feito. */
    suspend fun onDue(id: Long): Reminder? {
        val reminder = dao.reminder(id)?.takeIf { !it.done } ?: return null
        dao.markDone(id)
        return reminder
    }

    /** Depois de reiniciar o celular: reagenda; os que venceram com ele desligado avisam já. */
    suspend fun rescheduleAll() {
        val soon = System.currentTimeMillis() + 5_000
        dao.pending().forEach { scheduler.scheduleReminder(it.id, Instant.ofEpochMilli(maxOf(it.at, soon))) }
    }

    private suspend fun finish(id: Long) {
        dao.markDone(id)
        scheduler.scheduleReminder(id, null)
        notifications.cancelReminder(id)
    }

    private fun local(millis: Long) = LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), zone)
}
