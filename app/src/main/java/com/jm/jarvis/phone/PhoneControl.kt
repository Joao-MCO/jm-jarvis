package com.jm.jarvis.phone

import android.Manifest
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.provider.ContactsContract
import android.provider.MediaStore
import com.jm.jarvis.domain.SpokenTime
import com.jm.jarvis.domain.TextMatch

/** Controle do celular por voz. Nada é enviado nem ligado sozinho: ligação e WhatsApp param na tela para você confirmar. */
class PhoneControl(private val context: Context, private val actions: PhoneActions) {
    private val audio = context.getSystemService(AudioManager::class.java)

    fun torch(on: Boolean): String {
        val camera = context.getSystemService(CameraManager::class.java)
        val id = camera.cameraIdList.firstOrNull {
            camera.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return "Este celular não tem lanterna disponível."
        return runCatching { camera.setTorchMode(id, on) }
            .fold({ if (on) "Lanterna ligada." else "Lanterna desligada." }, { "Não consegui mexer na lanterna: ${it.message}" })
    }

    /** Volume de mídia: "50" (por cento), "mais", "menos" ou "mudo". */
    fun volume(value: String): String {
        val stream = AudioManager.STREAM_MUSIC
        val max = audio.getStreamMaxVolume(stream)
        val current = audio.getStreamVolume(stream)
        val v = SpokenTime.normalize(value)
        val target = when {
            "mud" in v || "silenc" in v -> 0
            "max" in v -> max
            "mais" in v || "aument" in v || "sob" in v -> current + (max / 5).coerceAtLeast(1)
            "menos" in v || "diminu" in v || "baix" in v -> current - (max / 5).coerceAtLeast(1)
            else -> v.filter(Char::isDigit).toIntOrNull()?.let { it.coerceIn(0, 100) * max / 100 }
                ?: return "Diga o volume em porcentagem, ou mais, menos ou mudo."
        }.coerceIn(0, max)
        audio.setStreamVolume(stream, target, AudioManager.FLAG_SHOW_UI)
        return "Volume de mídia em ${target * 100 / max}%."
    }

    fun openApp(name: String): String {
        val pm = context.packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
            .filter { it.second != context.packageName }
        val exact = apps.firstOrNull { SpokenTime.normalize(it.first) == SpokenTime.normalize(name) }
        val match = exact ?: TextMatch.best(name, apps) { it.first } ?: return "Não achei um app chamado $name."
        val intent = pm.getLaunchIntentForPackage(match.second) ?: return "Não consegui abrir ${match.first}."
        return if (actions.launch(intent)) "Abrindo ${match.first}." else "Não consegui abrir ${match.first}."
    }

    /** Abre o discador com o número já digitado; quem toca em ligar é você. */
    fun call(who: String): String {
        val number = numberOf(who) ?: return contactProblem(who)
        actions.launch(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(number.second)}")))
        return "Abri o discador com o número de ${number.first}. É só tocar para ligar."
    }

    /** Abre a conversa do WhatsApp com a mensagem escrita; quem envia é você. */
    fun whatsapp(who: String, message: String?): String {
        val number = numberOf(who) ?: return contactProblem(who)
        var digits = number.second.filter(Char::isDigit)
        if (digits.length in 10..11) digits = "55$digits" // número brasileiro sem o código do país
        val url = "https://wa.me/$digits" + (message?.takeIf { it.isNotBlank() }?.let { "?text=${Uri.encode(it)}" } ?: "")
        return if (actions.launch(Intent(Intent.ACTION_VIEW, Uri.parse(url)))) {
            "Abri o WhatsApp com ${number.first}" + if (message.isNullOrBlank()) "." else ", com a mensagem pronta para você enviar."
        } else {
            "Não consegui abrir o WhatsApp."
        }
    }

    fun navigate(destination: String): String {
        val navigation = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${Uri.encode(destination)}"))
        val ok = actions.launch(navigation) || actions.launch(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(destination)}")))
        return if (ok) "Traçando a rota para $destination." else "Não achei um app de mapas."
    }

    /** Toca uma música, artista ou playlist; prefere o Spotify se estiver instalado. */
    fun playMusic(query: String): String {
        val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
            .putExtra(SearchManager.QUERY, query)
            .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
        val spotify = Intent(intent).setPackage(SPOTIFY)
        val ok = actions.launch(spotify) || actions.launch(intent)
        return if (ok) "Tocando $query." else "Não achei um app de música que toque por pedido."
    }

    fun contactsAllowed(): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    /** Nome e número; um número dito direto também vale. */
    private fun numberOf(who: String): Pair<String, String>? {
        if (who.count(Char::isDigit) >= 8) return who to who
        if (!contactsAllowed()) return null
        val matches = contacts(who)
        return matches.singleOrNull() ?: matches.firstOrNull()?.takeIf { matches.map { m -> m.first }.distinct().size == 1 }
    }

    private fun contactProblem(who: String): String {
        if (who.count(Char::isDigit) < 8 && !contactsAllowed()) return "Preciso da permissão de contatos; dá para liberar em Ajustes."
        val people = contacts(who).map { it.first }.distinct()
        return if (people.size > 1) "Achei mais de um contato: ${people.take(4).joinToString(", ")}. Qual deles?"
        else "Não achei o contato $who."
    }

    private fun contacts(who: String): List<Pair<String, String>> = runCatching {
        val uri = Uri.withAppendedPath(ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI, Uri.encode(who))
        context.contentResolver.query(
            uri,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            null,
            null,
            null,
        )?.use { c -> buildList { while (c.moveToNext() && size < 10) add(c.getString(0).orEmpty() to c.getString(1).orEmpty()) } }.orEmpty()
    }.getOrDefault(emptyList())

    private companion object {
        const val SPOTIFY = "com.spotify.music"
    }
}
