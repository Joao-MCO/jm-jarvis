package com.jm.jarvis.brain

import com.jm.jarvis.domain.SpokenTime

/**
 * Distingue pergunta de comando. O reconhecedor de voz nem sempre escreve "?", então a palavra
 * interrogativa no começo pesa tanto quanto o ponto de interrogação. Na dúvida, é afirmação.
 * "Pode ligar a lanterna?" e "me diz as horas" ficam de fora de propósito: são pedidos, não perguntas.
 */
object Question {
    private val OPENING = Regex(
        """^(?:(?:ei |oi |ola )?jarvis[, ]+)?(?:e |mas )?(?:qual|quais|quanto|quantos|quantas|quando|quem|onde|como|porque|por que|o que|que horas|que dia|sera que|sera|tem |tenho |ha |vai |vou |ja )""",
    )

    fun asked(spoken: String): Boolean {
        val text = SpokenTime.normalize(spoken)
        if ('?' in text) return true
        // "já bebi água" (sem ?) é afirmação; "já" só abre pergunta quando há "?", tratado acima.
        val match = OPENING.find(text) ?: return false
        return !match.value.endsWith("ja ")
    }
}
