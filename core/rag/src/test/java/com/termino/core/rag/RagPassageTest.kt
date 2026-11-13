package com.termino.core.rag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RagPassageTest {
    private val filler = (1..60).joinToString(" ") { "Frase de relleno número $it sin interés." }

    @Test
    fun shortChunkIsSentWhole() {
        assertEquals("Texto corto.", bestPassage("Texto  corto.", listOf("texto"), 1500))
    }

    @Test
    fun picksWindowWithQueryTermsNotTheBeginning() {
        val content = "$filler La entropía cruzada mide la pérdida. La entropía se minimiza al entrenar. $filler"
        val passage = bestPassage(content, listOf("entropía", "pérdida"), 400)
        assertTrue(passage, passage.contains("entropía cruzada"))
        assertTrue(passage.length <= 402)
        assertTrue(passage.startsWith("…"))
    }
}
