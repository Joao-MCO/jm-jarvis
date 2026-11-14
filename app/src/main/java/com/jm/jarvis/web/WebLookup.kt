package com.jm.jarvis.web

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.CancellationSignal
import android.util.Log
import com.jm.jarvis.data.SettingsStore
import com.jm.jarvis.domain.PT_BR
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.util.Locale
import kotlin.coroutines.resume

/**
 * O que o JARVIS busca fora do celular: pesquisa (Tavily), Wikipedia e clima (Open-Meteo).
 * Só a pergunta sai do aparelho; quem raciocina continua sendo a IA local.
 */
class WebLookup(private val context: Context, private val settings: SettingsStore) {

    suspend fun search(query: String): Lookup {
        val key = settings.current().tavilyKey.trim()
        if (key.isEmpty()) return Lookup("A pesquisa na internet não está configurada. Peça ao usuário para colar a chave da Tavily em Ajustes.")
        return attempt("pesquisar") {
            val body = JSONObject()
                .put("query", query)
                .put("search_depth", "basic")
                .put("max_results", 3)
                .put("include_answer", true)
            val json = JSONObject(post("https://api.tavily.com/search", body, mapOf("Authorization" to "Bearer $key")))
            val hits = json.optJSONArray("results").objects().map {
                SearchHit(it.optString("title"), it.optString("url"), it.optString("content"))
            }
            Lookup(
                text = LookupText.search(json.optString("answer").takeIf { it.isNotBlank() && it != "null" }, hits),
                sources = hits.take(3).map { Source(it.title, it.url) },
            )
        }
    }

    suspend fun wikipedia(term: String): Lookup = attempt("consultar a Wikipedia") {
        val found = JSONObject(get("$WIKI/w/rest.php/v1/search/page?q=${Uri.encode(term)}&limit=1"))
            .optJSONArray("pages").objects().firstOrNull()
            ?: return@attempt Lookup("A Wikipedia não tem um artigo sobre \"$term\".")
        val summary = JSONObject(get("$WIKI/api/rest_v1/page/summary/${Uri.encode(found.optString("key"))}"))
        val title = summary.optString("title", found.optString("title"))
        val url = summary.optJSONObject("content_urls")?.optJSONObject("desktop")?.optString("page")
            ?: "$WIKI/wiki/${Uri.encode(found.optString("key"))}"
        Lookup(LookupText.wikipedia(title, summary.optString("extract")), listOf(Source("Wikipedia: $title", url)))
    }

    /** Clima agora e a previsão de [days] dias contando hoje (de 1 a 7). */
    suspend fun weather(days: Int = 2): Lookup {
        val location = location()
            ?: return Lookup("Não consegui a localização do celular. Peça ao usuário para permitir a localização em Ajustes e deixá-la ligada.")
        return attempt("consultar o clima") {
            val url = String.format(
                Locale.ROOT,
                "https://api.open-meteo.com/v1/forecast?latitude=%.3f&longitude=%.3f" +
                    "&current=temperature_2m,apparent_temperature,weather_code" +
                    "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
                    "&timezone=auto&forecast_days=${days.coerceIn(1, 7)}",
                location.latitude,
                location.longitude,
            )
            val json = JSONObject(get(url))
            val current = json.getJSONObject("current")
            val now = WeatherNow(current.getDouble("temperature_2m"), current.getDouble("apparent_temperature"), current.getInt("weather_code"))
            val daily = json.getJSONObject("daily")
            val dates = daily.getJSONArray("time")
            val forecast = (0 until dates.length()).map { i ->
                DayForecast(
                    date = LocalDate.parse(dates.getString(i)),
                    code = daily.getJSONArray("weather_code").getInt(i),
                    min = daily.getJSONArray("temperature_2m_min").getDouble(i),
                    max = daily.getJSONArray("temperature_2m_max").getDouble(i),
                    rainChance = daily.optJSONArray("precipitation_probability_max")?.optInt(i, -1)?.takeIf { it >= 0 },
                )
            }
            Lookup(LookupText.weather(now, forecast, city(location), LocalDate.now()), listOf(Source("Open-Meteo", "https://open-meteo.com")))
        }
    }

    fun hasLocationPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Erro de rede vira uma frase para a IA repassar, em vez de derrubar a conversa. */
    private suspend fun attempt(what: String, block: suspend () -> Lookup): Lookup =
        try {
            withContext(Dispatchers.IO) { block() }
        } catch (e: Exception) {
            Log.w(TAG, "falha ao $what", e)
            Lookup("Não consegui $what agora (${e.message ?: "sem conexão"}). Diga isso ao usuário.")
        }

    @SuppressLint("MissingPermission")
    private suspend fun location(): Location? {
        if (!hasLocationPermission()) return null
        val manager = context.getSystemService(LocationManager::class.java)
        val provider = if (manager.hasProvider(LocationManager.FUSED_PROVIDER)) LocationManager.FUSED_PROVIDER else LocationManager.NETWORK_PROVIDER
        val fresh = runCatching {
            withTimeoutOrNull(8_000) {
                suspendCancellableCoroutine { cont ->
                    val signal = CancellationSignal()
                    manager.getCurrentLocation(provider, signal, context.mainExecutor) { cont.resume(it) }
                    cont.invokeOnCancellation { signal.cancel() }
                }
            }
        }.getOrNull()
        return fresh ?: manager.allProviders
            .mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
    }

    private suspend fun city(location: Location): String? {
        if (!Geocoder.isPresent()) return null
        return withTimeoutOrNull(4_000) {
            suspendCancellableCoroutine { cont ->
                Geocoder(context, PT_BR).getFromLocation(location.latitude, location.longitude, 1, object : Geocoder.GeocodeListener {
                    override fun onGeocode(addresses: MutableList<android.location.Address>) {
                        cont.resume(addresses.firstOrNull()?.let { it.locality ?: it.subAdminArea })
                    }

                    override fun onError(errorMessage: String?) {
                        cont.resume(null)
                    }
                })
            }
        }
    }

    private fun get(url: String): String = request(url, "GET", null, emptyMap())

    private fun post(url: String, body: JSONObject, headers: Map<String, String>): String =
        request(url, "POST", body.toString(), headers + ("Content-Type" to "application/json"))

    private fun request(url: String, method: String, body: String?, headers: Map<String, String>): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            // Sem dados pessoais: só o nome genérico do app, como pede a Wikimedia.
            connection.setRequestProperty("User-Agent", "JarvisPessoal/0.3 (Android)")
            headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            if (body != null) {
                connection.doOutput = true
                connection.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IllegalStateException(if (code == 401) "chave da Tavily inválida" else "HTTP $code")
            return text
        } finally {
            connection.disconnect()
        }
    }

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

    private companion object {
        const val TAG = "JarvisWeb"
        const val WIKI = "https://pt.wikipedia.org"
    }
}
