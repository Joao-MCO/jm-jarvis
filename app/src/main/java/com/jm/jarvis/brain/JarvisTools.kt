package com.jm.jarvis.brain

import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet
import com.jm.jarvis.calendar.CalendarAnnouncer
import com.jm.jarvis.data.AlertMode
import com.jm.jarvis.data.Habit
import com.jm.jarvis.data.ScheduleType
import com.jm.jarvis.domain.BriefingService
import com.jm.jarvis.domain.DraftResult
import com.jm.jarvis.domain.GoalKind
import com.jm.jarvis.domain.HabitMatcher
import com.jm.jarvis.domain.HabitService
import com.jm.jarvis.domain.MemoryService
import com.jm.jarvis.domain.Progress
import com.jm.jarvis.domain.ReminderService
import com.jm.jarvis.domain.RoutineDraft
import com.jm.jarvis.domain.SpokenTime
import com.jm.jarvis.domain.Stopwatch
import com.jm.jarvis.domain.StopwatchAction
import com.jm.jarvis.domain.StopwatchService
import com.jm.jarvis.domain.parseClock
import com.jm.jarvis.phone.PhoneActions
import com.jm.jarvis.phone.PhoneControl
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
    private val reminders: ReminderService,
    private val memory: MemoryService,
    private val briefing: BriefingService,
    private val control: PhoneControl,
) : ToolSet {

    @Tool(description = "Controla o celular: lanterna, volume, abrir app, ligar, WhatsApp, rota ou música.")
    fun celular(
        @ToolParam(description = "lanterna, volume, abrir, ligar, whatsapp, rota, musica") acao: String,
        @ToolParam(description = "Contato, app, destino ou música") alvo: String? = null,
        @ToolParam(description = "ligar/desligar; 0 a 100, mais ou menos; mensagem do WhatsApp") valor: String? = null,
    ): String { executed++
        val action = SpokenTime.normalize(acao)
        val target = alvo?.trim().orEmpty()
        return when {
            "lanterna" in action || "flash" in action ->
                control.torch(on = valor?.let { "deslig" !in SpokenTime.normalize(it) && "apag" !in SpokenTime.normalize(it) } ?: true)
            "volume" in action || "som" in action -> control.volume(valor ?: target)
            "whats" in action || "mensag" in action -> if (target.isEmpty()) "Para quem é a mensagem?" else control.whatsapp(target, valor)
            "lig" in action || "chama" in action -> if (target.isEmpty()) "Para quem devo ligar?" else control.call(target)
            "rota" in action || "naveg" in action || "mapa" in action -> if (target.isEmpty()) "Para onde?" else control.navigate(target)
            "music" in action || "toca" in action -> if (target.isEmpty()) "Qual música?" else control.playMusic(target)
            "abr" in action || "app" in action -> if (target.isEmpty()) "Qual app?" else control.openApp(target)
            else -> "Ação do celular desconhecida: use lanterna, volume, abrir, ligar, whatsapp, rota ou musica."
        }
    }

    @Tool(description = "Lembrete avulso com hora: criar, listar ou cancelar.")
    fun lembrete(
        @ToolParam(description = "criar, listar ou cancelar") acao: String,
        @ToolParam(description = "O que lembrar") texto: String? = null,
        @ToolParam(description = "Quando, como o usuário disse") quando: String? = null,
    ): String = runBlocking { executed++;
        val action = SpokenTime.normalize(acao)
        when {
            "cancel" in action || "apag" in action || "remov" in action ->
                texto?.let { reminders.cancel(it) } ?: "Diga qual lembrete cancelar."
            "list" in action || "ver" in action || "quais" in action -> reminders.describePending()
            else -> {
                if (texto.isNullOrBlank()) return@runBlocking "Diga o que devo lembrar."
                if (quando.isNullOrBlank()) return@runBlocking "Diga quando devo lembrar."
                reminders.create(texto, quando)
            }
        }
    }

    @Tool(description = "Guarda, esquece ou lista fatos sobre o usuário.")
    fun memoria(
        @ToolParam(description = "guardar, esquecer ou listar") acao: String,
        @ToolParam(description = "O fato, em uma frase") fato: String? = null,
    ): String = runBlocking { executed++;
        val action = SpokenTime.normalize(acao)
        when {
            "esquec" in action || "apag" in action -> fato?.let { memory.forget(it) } ?: "Diga o que devo esquecer."
            "list" in action || "ver" in action -> memory.describe()
            else -> fato?.let { memory.remember(it) } ?: "Diga o que devo guardar."
        }
    }

    @Tool(description = "Despertador ou timer do relógio.")
    fun relogio(
        @ToolParam(description = "despertador, timer ou ver_despertadores") acao: String,
        @ToolParam(description = "HH:mm") horario: String? = null,
        @ToolParam(description = "Minutos do timer") minutos: Int? = null,
        @ToolParam(description = "Segundos do timer") segundos: Int? = null,
        @ToolParam(description = "Dias de repetição") dias: List<String>? = null,
        @ToolParam(description = "Nome, se o usuário disser") rotulo: String? = null,
    ): String { executed++
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

    @Tool(description = "Cronômetro: iniciar, pausar, continuar, zerar ou consultar.")
    fun cronometro(
        @ToolParam(description = "iniciar, pausar, continuar, zerar ou consultar") acao: String,
    ): String = runBlocking { executed++;
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
    /** Quantas ferramentas a IA executou na resposta em andamento; o [Brain] usa para pegar "Registrado" sem ação. */
    @Volatile var executed = 0

    /** Fontes consultadas na resposta em andamento; o [Brain] limpa antes e lê depois de cada pergunta. */
    val sources = mutableListOf<Source>()

    @Tool(description = "Pesquisa na internet: notícias, preços, placares e informação recente.")
    fun pesquisarInternet(
        @ToolParam(description = "O que pesquisar, em poucas palavras") consulta: String,
    ): String = runBlocking { executed++; use(web.search(consulta)) }

    @Tool(description = "Wikipedia: pessoas, lugares, conceitos e história.")
    fun consultarWikipedia(
        @ToolParam(description = "O termo") termo: String,
    ): String = runBlocking { executed++; use(web.wikipedia(termo)) }

    @Tool(description = "Clima e previsão dos próximos dias.")
    fun clima(
        @ToolParam(description = "Dias de previsão, 1 a 7") dias: Int = 2,
    ): String = runBlocking { executed++; use(web.weather(dias.coerceIn(1, 7))) }

    private fun use(lookup: Lookup): String {
        sources += lookup.sources
        return lookup.text
    }

    @Tool(description = "Registra que o usuário fez uma rotina (água, dentes, academia etc.).")
    fun registrarHabito(
        @ToolParam(description = "Nome da rotina") habito: String,
        @ToolParam(description = "Quantidade, se disser") quantidade: Double? = null,
    ): String = runBlocking { executed++;
        val habit = find(habito) ?: return@runBlocking notFound(habito)
        val target = habit.dailyTarget
        // "1 litro" chega como 1: numa rotina em ml, até 10 só pode ser litros.
        val quantidade = if (habit.unit.equals("ml", ignoreCase = true) && quantidade != null && quantidade > 0 && quantidade <= 10) quantidade * 1000 else quantidade
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

    @Tool(description = "Rotinas de hoje e o que falta.")
    fun listarHabitos(): String = runBlocking { executed++; todayLines() }

    @Tool(description = "Cria uma rotina nova, com só um tipo de frequência.")
    fun criarHabito(
        @ToolParam(description = "Nome da rotina") nome: String,
        @ToolParam(description = "A cada N minutos") intervaloMinutos: Int? = null,
        @ToolParam(description = "Horários HH:mm") horarios: List<String>? = null,
        @ToolParam(description = "Vezes por semana") vezesPorSemana: Int? = null,
    ): String = runBlocking { executed++;
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

    @Tool(description = "Adia o aviso de uma rotina.")
    fun adiarLembrete(
        @ToolParam(description = "Nome da rotina") habito: String,
        @ToolParam(description = "Minutos") minutos: Int,
    ): String = runBlocking { executed++;
        val habit = find(habito) ?: return@runBlocking notFound(habito)
        if (minutos !in 1..240) return@runBlocking "Só consigo adiar de 1 a 240 minutos."
        habits.snooze(habit.id, minutos.toLong())
        "Lembrete de ${habit.name} adiado para ${Progress.hhmm(LocalTime.now().plusMinutes(minutos.toLong()))}."
    }

    @Tool(description = "Resumo do dia: clima, agenda, rotinas e lembretes.")
    fun resumoDoDia(): String = runBlocking { executed++;
        val today = habits.todayState()
        val next = today.next?.let { "Próximo aviso: ${it.habit.name}, ${Progress.upcoming(it.at, today.date)}." }.orEmpty()
        "${briefing.text()} $next".trim()
    }

    @Tool(description = "Eventos da agenda.")
    fun eventosDaAgenda(
        @ToolParam(description = "Dias, 1 a 7") dias: Int = 1,
    ): String = runBlocking { executed++; agenda(dias) }

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
