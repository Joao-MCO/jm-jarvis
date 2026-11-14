package com.jm.jarvis.brain

import android.os.Debug
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.BenchmarkInfo
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.tool
import com.jm.jarvis.container
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Fase 0: mede se o Gemma 4 E2B é rápido e preciso o bastante neste aparelho.
 *
 * Rode com `am instrument`, não com connectedAndroidTest: esse desinstala o app ao final
 * e apagaria o modelo de 2,6 GB.
 *   gradlew installDebug installDebugAndroidTest
 *   adb shell am instrument -w -e backends cpu com.jm.jarvis.test/androidx.test.runner.AndroidJUnitRunner
 * Relatório: logcat (tag JarvisEval) e files/eval/ no aparelho.
 */
@OptIn(ExperimentalApi::class)
@RunWith(AndroidJUnit4::class)
class FeasibilityTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val app = context.container

    /** As mesmas instruções que o app usa, com as rotinas e os fatos reais do aparelho. */
    private val persona: String = runBlocking {
        app.brain.persona(
            routines = app.habits.habits().filter { it.active }.joinToString(", ") { it.name },
            facts = app.memory.forPrompt(),
            now = LocalDateTime.now(),
        )
    }
    private val report = StringBuilder()
    private val reportFile = File(
        context.getExternalFilesDir(null),
        "eval/feasibility-${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))}.md",
    )

    @Test
    fun gemmaMeetsFeasibilityGate() {
        val model = LocalModel.file(context)
        assertTrue("Modelo não encontrado em ${model.absolutePath}", model.exists())

        val backends = (InstrumentationRegistry.getArguments().getString("backends") ?: "cpu,gpu")
            .split(',').map { it.trim().lowercase() }
        ExperimentalFlags.enableBenchmark = true

        // Um backend por vez: o aparelho não comporta dois modelos carregados.
        val results = backends.mapNotNull { measure(model, it) }
        line("")
        results.forEach { line(it.summary()) }
        saveReport()

        assertTrue(
            "Nenhum backend passou no portão (>= $MIN_DECODE_TPS tokens/s e >= ${(MIN_ACCURACY * 100).toInt()}% de acerto). Veja ${reportFile.name}",
            results.any { it.passesGate },
        )
    }

    private fun measure(model: File, name: String): BackendResult? {
        val backend = when (name) {
            "cpu" -> Backend.CPU()
            "gpu" -> Backend.GPU()
            else -> error("Backend desconhecido: $name (use cpu ou gpu)")
        }
        line("## Backend ${backend.name}")

        val started = SystemClock.elapsedRealtime()
        val engine = try {
            Engine(EngineConfig(modelPath = model.absolutePath, backend = backend, cacheDir = context.cacheDir.path))
                .apply {
                    try {
                        initialize()
                    } catch (e: Exception) {
                        close()
                        throw e
                    }
                }
        } catch (e: Exception) {
            line("Falhou ao iniciar: ${e.message}")
            saveReport()
            return null
        }

        return engine.use {
            val initSeconds = (SystemClock.elapsedRealtime() - started) / 1000.0
            val memoryMb = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }.totalPss / 1024
            line(fmt("Inicialização: %.1f s · memória do app: %d MB", initSeconds, memoryMb))

            val speed = measureSpeed(engine)
            val tools = measureTools(engine)
            BackendResult(backend.name, initSeconds, memoryMb, speed, tools).also { saveReport() }
        }
    }

    private fun measureSpeed(engine: Engine): List<BenchmarkInfo> {
        line("### Velocidade de fala")
        val config = ConversationConfig(systemInstruction = Contents.of(persona), samplerConfig = GREEDY)
        return SPEED_PROMPTS.map { prompt ->
            engine.createConversation(config).use { conversation ->
                val reply = conversation.sendMessage(prompt)
                val info = conversation.getBenchmarkInfo()
                line(
                    fmt(
                        "%d tokens · %.1f tokens/s · 1º token em %.2f s · leitura %.0f tokens/s",
                        info.lastDecodeTokenCount, info.lastDecodeTokensPerSecond,
                        info.timeToFirstTokenInSecond, info.lastPrefillTokensPerSecond,
                    ),
                )
                line("  > ${reply.toString().oneLine(160)}")
                info
            }
        }
    }

    private fun measureTools(engine: Engine): ToolScore {
        line("### Ferramentas")
        val config = ConversationConfig(
            systemInstruction = Contents.of(persona),
            // As ferramentas reais do app; com a execução automática desligada, nada é feito de verdade.
            tools = listOf(tool(app.newTools())),
            samplerConfig = GREEDY,
            automaticToolCalling = false,
        )
        var toolHits = 0
        var fullHits = 0
        val latenciesMs = mutableListOf<Long>()

        for (case in EVAL_CASES) {
            val started = SystemClock.elapsedRealtime()
            val outcome = runCatching {
                engine.createConversation(config).use { it.sendMessage(case.phrase) to it.getBenchmarkInfo() }
            }
            val elapsedMs = SystemClock.elapsedRealtime() - started
            latenciesMs += elapsedMs

            val reply: Message? = outcome.getOrNull()?.first
            val call = reply?.toolCalls?.firstOrNull()
            val toolOk = reply != null && case.toolOk(call)
            val pass = reply != null && case.passes(call)
            if (toolOk) toolHits++
            if (pass) fullHits++

            val got = when {
                reply == null -> "erro: ${outcome.exceptionOrNull()?.message}"
                call != null -> "${call.name} ${call.arguments}" +
                    if (reply.toolCalls.size > 1) " (+${reply.toolCalls.size - 1} chamadas)" else ""
                else -> "texto: ${reply.toString().oneLine(60)}"
            }
            val prefill = outcome.getOrNull()?.second?.lastPrefillTokenCount ?: 0
            line(
                fmt(
                    "%s \"%s\" | esperado: %s | obtido: %s | %.1f s, %d tokens lidos",
                    if (pass) "OK  " else "ERRO", case.phrase, case.tool ?: "nenhuma", got, elapsedMs / 1000.0, prefill,
                ),
            )
        }
        return ToolScore(toolHits, fullHits, EVAL_CASES.size, latenciesMs.median())
    }

    private fun line(text: String) {
        Log.i(TAG, text)
        report.appendLine(text)
    }

    private fun saveReport() {
        reportFile.parentFile?.mkdirs()
        reportFile.writeText(report.toString())
    }

    private data class ToolScore(val toolHits: Int, val fullHits: Int, val total: Int, val medianMs: Long) {
        val accuracy: Double get() = fullHits.toDouble() / total
    }

    private data class BackendResult(
        val backend: String,
        val initSeconds: Double,
        val memoryMb: Int,
        val speed: List<BenchmarkInfo>,
        val tools: ToolScore,
    ) {
        val decodeTps: Double = speed.map { it.lastDecodeTokensPerSecond }.median()
        val firstTokenSeconds: Double = speed.map { it.timeToFirstTokenInSecond }.median()
        val passesGate: Boolean = decodeTps >= MIN_DECODE_TPS && tools.accuracy >= MIN_ACCURACY

        fun summary() = fmt(
            "RESUMO %s: fala %.1f tokens/s (1º token %.2f s) · ferramenta certa %d/%d · com argumentos certos %d/%d (%.0f%%) · " +
                "comando em %.1f s (mediana) · inicialização %.1f s · %d MB · portão: %s",
            backend, decodeTps, firstTokenSeconds, tools.toolHits, tools.total, tools.fullHits, tools.total,
            tools.accuracy * 100, tools.medianMs / 1000.0, initSeconds, memoryMb, if (passesGate) "PASSOU" else "NÃO PASSOU",
        )
    }

    private companion object {
        const val TAG = "JarvisEval"
        const val MIN_DECODE_TPS = 8.0
        const val MIN_ACCURACY = 0.85

        /** Escolha gulosa (sempre o token mais provável): resultados repetíveis entre execuções. */
        val GREEDY = SamplerConfig(topK = 1, topP = 1.0, temperature = 1.0)

        val SPEED_PROMPTS = listOf(
            "Explique em um parágrafo de umas 80 palavras por que beber água ao longo do dia faz bem.",
            "Me dê três dicas curtas para manter a constância na academia.",
            "Escreva uma mensagem de bom dia motivadora com umas 60 palavras.",
        )

        fun fmt(pattern: String, vararg args: Any?): String = String.format(Locale.ROOT, pattern, *args)

        fun String.oneLine(max: Int): String = replace(Regex("\\s+"), " ").trim().take(max)

        fun <T : Comparable<T>> List<T>.median(): T = sorted()[size / 2]
    }
}
