package com.jm.jarvis.brain

import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet
import com.jm.jarvis.calendar.CalendarAnnouncer
import com.jm.jarvis.data.AlertMode
import com.jm.jarvis.data.Habit
import com.jm.jarvis.data.ScheduleType
import com.jm.jarvis.domain.DraftResult
import com.jm.jarvis.domain.GoalKind
import com.jm.jarvis.domain.HabitMatcher
import com.jm.jarvis.domain.HabitService
import com.jm.jarvis.domain.Progress
import com.jm.jarvis.domain.RoutineDraft
import com.jm.jarvis.domain.SpokenTime
import com.jm.jarvis.domain.Stopwatch
import com.jm.jarvis.domain.StopwatchAction
import com.jm.jarvis.domain.StopwatchService
import com.jm.jarvis.domain.parseClock
import com.jm.jarvis.phone.PhoneActions
import com.jm.jarvis.web.Lookup
import com.jm.jarvis.web.Source
import com.jm.jarvis.web.WebLookup
import kotlinx.coroutines.runBlocking
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * O que a IA pode fazer de verdade. O LiteRT-LM chama estes métodos numa thread própria, por isso o
 * `runBlocking`. A saída do modelo não é confiável: tudo é validado aqui e o texto devolvido diz o
 * que foi feito, para a resposta falada repetir o valor real registrado.
 */
@Suppress("unused")
class JarvisTools(
    private val habits: HabitService,
    private val calendar: CalendarAnnouncer,
    private val web: WebLookup,
    private val phone: PhoneActions,
    private val stopwatch: StopwatchService,
) : ToolSet {

    @Tool(description = "Relógio do celular: criar despertador, iniciar timer ou mostrar os despertadores.")
    fun relogio(
        @ToolParam(description = "despertador, timer ou ver_despertadores") acao: String,
        @ToolParam(description = "Horário do despertador, ex.: 06:30") horario: String? = null,
        @ToolParam(description = "Minutos do timer") minutos: Int? = null,
        @ToolParam(description = "Segundos do timer") segundos: Int? = null,
        @ToolParam(description = "Dias em que o despertador repete, ex.: [\"seg\", \"qua\"] ou [\"dias úteis\"]") dias: List<String>? = null,
        @ToolParam(description = "Nome do despertador ou do timer, se o usuário disser") rotulo: String? = null,
    ): String {
        val action = SpokenTime.normalize(acao)
        return when {
            "desperta" in action || "alarme" in action -> {
                val time = horario?.let(::parseClock) ?: return "Não entendi o horário do despertador. Use HH:mm."
                val days = SpokenTime.days(dias.orEmpty())
                if (!phone.setAlarm(time, days, rotulo)) return "Não achei um app de relógio que aceite despertadores."
                val repeat = if (days.isEmpty()) "" else ", ${Progress.days(days)}"
                "Despertador criado para ${Progress.hhmm(time)}$repeat no app Relógio."
            }
            "timer" in action || "tempor" in action -> {
                val total = (minutos ?: 0) * 60 + (segundos ?: 0)
                if (total !in 1..86_400) return "Diga a duração do timer, de 1 segundo a 24 horas."
                if (!phone.setTimer(total, rotulo)) return "Não achei um app de relógio que aceite timers."
                "Timer de ${Stopwatch.spoken(total * 1000L)} iniciado."
            }
            "ver" in action || "mostr" in action || "list" in action ->
                if (phone.showAlarms()) "Abri seus despertadores no app Relógio." else "Não achei um app de relógio."
            else -> "Ação do relógio desconhecida: use despertador, timer ou ver_despertadores."
        }
    }

    @Tool(description = "Cronômetro: iniciar, pausar, continuar, zerar ou consultar o tempo.")
    fun cronometro(
        @ToolParam(description = "iniciar, pausar, continuar, zerar ou consultar") acao: String,
    ): String = runBlocking {
        val action = SpokenTime.normalize(acao)
        // "reiniciar" contém "iniciar": zerar é testado antes.
        val chosen = when {
            "zer" in action || "reinic" in action -> StopwatchAction.RESET
            "inici" in action || "comec" in action || "dispar" in action -> StopwatchAction.START
            "paus" in action || "par" in action -> StopwatchAction.PAUSE
            "contin" in action || "retom" in action -> StopwatchAction.RESUME
            else -> StopwatchAction.STATUS
        }
        stopwatch.apply(chosen)
    }
    /** Fontes consultadas na resposta em andamento; o [Brain] limpa antes e lê depois de cada pergunta. */
    val sources = mutableListOf<Source>()

    @Tool(description = "Pesquisa na internet: notícias, preços, placares, horários de jogos e qualquer informação recente ou que você não sabe.")
    fun pesquisarInternet(
        @ToolParam(description = "O que pesquisar, em poucas palavras") consulta: String,
    ): String = runBlocking { use(web.search(consulta)) }

    @Tool(description = "Consulta a Wikipedia sobre pessoas, lugares, conceitos e fatos históricos.")
    fun consultarWikipedia(
        @ToolParam(description = "O termo, ex.: Santos Dumont") termo: String,
    ): String = runBlocking { use(web.wikipedia(termo)) }

    @Tool(description = "Clima agora e a previsão do tempo dos próximos dias onde o usuário está.")
    fun clima(
        @ToolParam(description = "Quantos dias de previsão contando hoje, de 1 a 7. Padrão: 2") dias: Int = 2,
    ): String = runBlocking { use(web.weather(dias.coerceIn(1, 7))) }

    private fun use(lookup: Lookup): String {
        sources += lookup.sources
        return lookup.text
    }

    @Tool(description = "Registra que o usuário acabou de fazer uma rotina: beber água, escovar os dentes, tomar suplementos, rezar, ir à academia, estudar, ler etc.")
    fun registrarHabito(
        @ToolParam(description = "Nome da rotina, ex.: água, escovar os dentes, academia") habito: String,
        @ToolParam(description = "Quantidade, se o usuário disser, ex.: 500 para 500 ml ou 20 para 20 minutos") quantidade: Double? = null,
    ): String = runBlocking {
        val habit = find(habito) ?: return@runBlocking notFound(habito)
        val target = habit.dailyTarget
        val amount = when {
            target == null -> null
            quantidade == null -> Progress.quickAmount(habit)
            quantidade <= 0 || quantidade > target * 4 ->
                return@runBlocking "Não registrei: ${Progress.number(quantidade)} ${habit.unit.orEmpty()} parece um valor errado para ${habit.name}."
            else -> quantidade
        }
        habits.log(habit.id, amount)
        val status = habits.statusOf(habit.id)?.let { Progress.describe(it) }.orEmpty()
        val added = amount?.let { " (+${Progress.number(it)} ${habit.unit.orEmpty()})" }.orEmpty()
        "Registrado: ${habit.name}$added. Hoje: $status."
    }

    @Tool(description = "Lista as rotinas de hoje e quais ainda faltam.")
    fun listarHabitos(): String = runBlocking { todayLines() }

    @Tool(description = "Cria uma rotina nova. Informe só um tipo de frequência: intervalo, horários ou vezes por semana.")
    fun criarHabito(
        @ToolParam(description = "Nome da rotina, ex.: tomar remédio") nome: String,
        @ToolParam(description = "Repetir a cada N minutos, ex.: 45") intervaloMinutos: Int? = null,
        @ToolParam(description = "Horários no formato HH:mm, ex.: [\"08:00\", \"20:00\"]") horarios: List<String>? = null,
        @ToolParam(description = "Quantas vezes por semana, ex.: 5") vezesPorSemana: Int? = null,
    ): String = runBlocking {
        if (habits.habits().any { HabitMatcher.sameName(it.name, nome) }) return@runBlocking "Já existe uma rotina chamada $nome."
        val times = horarios?.map { parseClock(it) ?: return@runBlocking "Não entendi o horário \"$it\". Use HH:mm." }
        val draft = RoutineDraft(
            name = nome.trim().replaceFirstChar(Char::uppercase),
            type = if (intervaloMinutos != null) ScheduleType.INTERVAL else ScheduleType.TIMES,
            times = times?.takeIf { it.isNotEmpty() } ?: listOf(DEFAULT_TIME),
            interval = intervaloMinutos?.toString() ?: "60",
            goal = if (vezesPorSemana != null) GoalKind.WEEKLY else GoalKind.NONE,
            weeklyTarget = vezesPorSemana?.toString().orEmpty(),
            alertMode = AlertMode.SPEAK,
        )
        when (val result = draft.validate()) {
            is DraftResult.Invalid -> "Não criei a rotina: ${result.message}"
            is DraftResult.Valid -> {
                habits.save(result.habit)
                val note = if (times.isNullOrEmpty() && intervaloMinutos == null) " Coloquei às ${Progress.hhmm(DEFAULT_TIME)}; dá para mudar em Rotinas." else ""
                "Rotina criada: ${result.habit.name}, ${Progress.schedule(result.habit)}.$note"
            }
        }
    }

    @Tool(description = "Adia o próximo lembrete de uma rotina.")
    fun adiarLembrete(
        @ToolParam(description = "Nome da rotina, ex.: água") habito: String,
        @ToolParam(description = "Quantos minutos adiar") minutos: Int,
    ): String = runBlocking {
        val habit = find(habito) ?: return@runBlocking notFound(habito)
        if (minutos !in 1..240) return@runBlocking "Só consigo adiar de 1 a 240 minutos."
        habits.snooze(habit.id, minutos.toLong())
        "Lembrete de ${habit.name} adiado para ${Progress.hhmm(LocalTime.now().plusMinutes(minutos.toLong()))}."
    }

    @Tool(description = "Resume o dia do usuário: rotinas feitas e pendentes, próximo aviso e eventos de hoje na agenda.")
    fun resumoDoDia(): String = runBlocking {
        val today = habits.todayState()
        val next = today.next?.let { "Próximo aviso: ${it.habit.name}, ${Progress.upcoming(it.at, today.date)}." }.orEmpty()
        "${todayLines()} $next ${agenda(1)}".trim()
    }

    @Tool(description = "Lista os eventos da agenda do usuário a partir de agora.")
    fun eventosDaAgenda(
        @ToolParam(description = "Quantos dias olhar, de 1 (só hoje) a 7. Padrão: 1") dias: Int = 1,
    ): String = runBlocking { agenda(dias) }

    private suspend fun find(name: String): Habit? = HabitMatcher.find(name, habits.habits())

    private suspend fun notFound(name: String): String =
        "Não achei a rotina \"$name\". Rotinas existentes: ${habits.habits().joinToString(", ") { it.name }}."

    private suspend fun todayLines(): String {
        val statuses = habits.todayState().statuses
        if (statuses.isEmpty()) return "Nenhuma rotina para hoje."
        return statuses.joinToString("; ", postfix = ".") { status ->
            "${status.habit.name}: ${Progress.describe(status)}" + if (status.completeToday) " (completa)" else " (pendente)"
        }
    }

    private suspend fun agenda(days: Int): String {
        if (!calendar.hasPermission()) return "Sem acesso à agenda; dá para liberar em Ajustes."
        val events = calendar.upcomingEvents(days.coerceIn(1, 7))
        if (events.isEmpty()) return if (days <= 1) "Nenhum evento na agenda hoje." else "Nenhum evento nos próximos $days dias."
        val zone = ZoneId.systemDefault()
        return "Agenda: " + events.take(MAX_EVENTS).joinToString("; ", postfix = ".") { event ->
            if (event.allDay) {
                val day = event.begin.atZone(zone).toLocalDate()
                "${if (day == LocalDate.now(zone)) "hoje" else Progress.DAY_LABELS.getValue(day.dayOfWeek)}, o dia todo: ${event.title}"
            } else {
                "${Progress.upcoming(LocalDateTime.ofInstant(event.begin, zone), LocalDate.now(zone))}: ${event.title}"
            }
        }
    }

    private companion object {
        val DEFAULT_TIME: LocalTime = LocalTime.of(9, 0)
        const val MAX_EVENTS = 8
    }
}
