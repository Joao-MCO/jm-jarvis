package com.jm.jarvis.brain

import android.content.Context
import java.io.File

/** Onde o modelo de IA local fica guardado no aparelho. */
object LocalModel {
    const val FILE_NAME = "gemma-4-E2B-it.litertlm"

    /** Pasta privada do app em /sdcard/Android/data/com.jm.jarvis/files, acessível via adb. */
    fun file(context: Context): File = File(context.getExternalFilesDir(null), FILE_NAME)
}
