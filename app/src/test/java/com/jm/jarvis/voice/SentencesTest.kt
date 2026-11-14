package com.jm.jarvis.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SentencesTest {
    @Test
    fun `complete sentences are released as they arrive`() {
        val (first, end) = Sentences.complete("Registrado. Faltam 1,5 litro", 0)
        assertEquals(listOf("Registrado."), first)
        val (second, end2) = Sentences.complete("Registrado. Faltam 1,5 litro para a meta! Boa", end)
        assertEquals(listOf("Faltam 1,5 litro para a meta!"), second)
        assertEquals("Boa", Sentences.rest("Registrado. Faltam 1,5 litro para a meta! Boa", end2))
    }

    @Test
    fun `numbers with dots do not cut the sentence`() =
        assertEquals(emptyList<String>(), Sentences.complete("Meta de 2.500 ml hoje", 0).first)

    @Test
    fun `nothing left after the last sentence`() = assertNull(Sentences.rest("Feito. ", 6))
}
