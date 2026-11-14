package com.jm.jarvis.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextMatchTest {
    private val items = listOf("Ligar para o dentista", "Tirar o lixo", "Sou alérgico a camarão")

    @Test
    fun `finds by the important words`() {
        assertEquals("Ligar para o dentista", TextMatch.best("o do dentista", items) { it })
        assertEquals("Sou alérgico a camarão", TextMatch.best("alergia a camarão", items) { it })
        assertNull(TextMatch.best("academia", items) { it })
    }

    @Test
    fun `same fact ignoring accents, case and filler words`() =
        assertTrue(TextMatch.same("sou alergico a camarao", "Sou alérgico a camarão."))
}
